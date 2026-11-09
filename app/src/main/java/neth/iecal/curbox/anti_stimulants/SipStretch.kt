package neth.iecal.curbox.anti_stimulants

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.models.SipStretchConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.SipStretchBreakActivity
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import neth.iecal.curbox.utils.isActivePackage

/**
 * Counts time spent in the selected apps. Once it passes the limit, every app is blocked for
 * the break length so the user gets up, drinks water and stretches. Time spent away from the
 * selected apps (including with the screen off) counts as rest, and a rest as long as a break
 * resets the count.
 *
 * All state is touched only on the main thread.
 */
class SipStretch {

    companion object {
        private const val BREAK_SCREEN_THROTTLE_MS = 1000L
    }

    private lateinit var service: BaseBlockingService
    private lateinit var crashLogger: CrashLogger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var config = SipStretchConfig()
    private var breakEndsAtMs = 0L
    private var allowedDuringBreak = emptySet<String>()
    private var ignoredPackages = emptySet<String>()
    private var destroyed = false
    private var screenReceiverRegistered = false

    private var currentPkg: String? = null
    private var usedMs = 0L
    private var countingSince = 0L
    private var restingSince = 0L
    private var lastBreakScreenAt = 0L

    private val limitCheck = Runnable {
        runSafely {
            stopCounting()
            if (usedMs >= limitMs()) {
                startBreak()
            } else {
                currentPkg?.let { if (it in config.selectedApps) startCounting() }
            }
        }
    }

    private val breakEnd = Runnable {
        runSafely { endBreak() }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (destroyed || intent?.action != Intent.ACTION_SCREEN_OFF) return
            runSafely {
                stopCounting()
                currentPkg = null
            }
        }
    }

    fun setup(service: BaseBlockingService) {
        this.service = service
        crashLogger = CrashLogger(service)

        ignoredPackages = setOfNotNull(
            "com.android.systemui",
            getCurrentKeyboardPackageName(service)
        )
        val dialer = try {
            (service.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } catch (e: Exception) {
            null
        }
        allowedDuringBreak = ignoredPackages + setOfNotNull(
            service.packageName,
            getDefaultLauncherPackageName(service.packageManager),
            dialer
        )

        try {
            ContextCompat.registerReceiver(
                service,
                screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        } catch (e: Exception) {
            crashLogger.logNonFatalError(e)
        }

        scope.launch {
            try {
                service.dataStoreManager.settings.collectLatest { settings ->
                    applySettings(settings.sipStretchConfig, settings.sipStretchBreakEndsAtMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                crashLogger.logNonFatalError(e)
            }
        }
    }

    private fun applySettings(newConfig: SipStretchConfig, newBreakEndsAtMs: Long) {
        val wasActive = config.isActive
        config = newConfig
        if (!newConfig.isActive) {
            stopCounting()
            usedMs = 0L
            currentPkg = null
            handler.removeCallbacks(breakEnd)
            breakEndsAtMs = 0L
            if (wasActive && newBreakEndsAtMs != 0L) persistBreakEnd(0L)
            return
        }

        if (newBreakEndsAtMs != breakEndsAtMs) {
            breakEndsAtMs = newBreakEndsAtMs
            handler.removeCallbacks(breakEnd)
            val remaining = breakEndsAtMs - System.currentTimeMillis()
            if (breakEndsAtMs != 0L) {
                if (remaining > 0) handler.postDelayed(breakEnd, remaining) else endBreak()
            }
        }

        // Selected apps or the limit may have changed, so restart the running count.
        stopCounting()
        currentPkg?.let { if (!isOnBreak() && it in config.selectedApps) startCounting() }
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (destroyed || !config.isActive) return
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages) return

        if (isOnBreak()) {
            if (pkg !in allowedDuringBreak && service.isActivePackage(pkg)) showBreakScreen()
            return
        }

        if (pkg == currentPkg || !service.isActivePackage(pkg)) return
        stopCounting()
        currentPkg = pkg
        if (pkg in config.selectedApps) startCounting()
    }

    private fun isOnBreak() = breakEndsAtMs > System.currentTimeMillis()

    private fun limitMs() = config.usageLimitMinutes.coerceAtLeast(1) * 60_000L

    private fun breakMs() = config.breakMinutes.coerceAtLeast(1) * 60_000L

    private fun startCounting() {
        if (countingSince != 0L) return
        val now = SystemClock.elapsedRealtime()
        if (restingSince != 0L && now - restingSince >= breakMs()) usedMs = 0L
        restingSince = 0L
        countingSince = now
        handler.postDelayed(limitCheck, (limitMs() - usedMs).coerceAtLeast(0L))
    }

    private fun stopCounting() {
        handler.removeCallbacks(limitCheck)
        if (countingSince == 0L) return
        val now = SystemClock.elapsedRealtime()
        usedMs += now - countingSince
        countingSince = 0L
        restingSince = now
    }

    private fun startBreak() {
        usedMs = 0L
        restingSince = 0L
        breakEndsAtMs = System.currentTimeMillis() + breakMs()
        handler.removeCallbacks(breakEnd)
        handler.postDelayed(breakEnd, breakMs())
        persistBreakEnd(breakEndsAtMs)
        showBreakScreen()
    }

    private fun endBreak() {
        handler.removeCallbacks(breakEnd)
        breakEndsAtMs = 0L
        usedMs = 0L
        // The next event decides whether counting starts again.
        currentPkg = null
        persistBreakEnd(0L)
    }

    private fun persistBreakEnd(endsAtMs: Long) {
        scope.launch {
            try {
                service.dataStoreManager.updateSipStretchBreakEndsAt(endsAtMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                crashLogger.logNonFatalError(e)
            }
        }
    }

    private fun showBreakScreen() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBreakScreenAt < BREAK_SCREEN_THROTTLE_MS) return
        lastBreakScreenAt = now
        service.pressHome()
        val intent = Intent(service, SipStretchBreakActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(SipStretchBreakActivity.EXTRA_BREAK_ENDS_AT_MS, breakEndsAtMs)
        }
        handler.postDelayed({ runSafely { service.startActivity(intent) } }, 100)
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            crashLogger.logNonFatalError(e)
        }
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacks(limitCheck)
        handler.removeCallbacks(breakEnd)
        scope.cancel()
        if (screenReceiverRegistered) {
            try {
                service.unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                crashLogger.logNonFatalError(e)
            }
            screenReceiverRegistered = false
        }
    }
}
