package neth.iecal.curbox.anti_stimulants

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.overlay.MindfulMessageOverlayManager
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.isActivePackage

class MindfulMessage {

    companion object {
        /**
         * must be provided packagename, duration to display and intent text
         */
        const val ADD_NEW_INTENT = "neth.iecal.curbox.ACTION_NEW_INTENT"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }
    private lateinit var service: BaseBlockingService
    private lateinit var overlayManager: MindfulMessageOverlayManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private var screenOn = false
    private var destroyed = false
    private var screenReceiverRegistered = false
    private var config = MindfulMessageConfig()
    private var appGroups = emptyList<AppGroup>()
    private var currentPkg: String? = null
    private var ignoredpackages = listOf<String>()

    private val activeIntents = mutableMapOf<String, Pair<String, Long>>()
    private val foregroundRecheck = Runnable {
        try {
            if (destroyed || !canDisplay()) return@Runnable
            val root = service.rootInActiveWindow ?: return@Runnable
            val pkg = try {
                root.packageName?.toString()
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            } ?: return@Runnable
            updateForegroundPackage(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger(service).logNonFatalError(e)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (destroyed) return
            try {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        mainHandler.removeCallbacks(foregroundRecheck)
                        endSession()
                    }
                    Intent.ACTION_SCREEN_ON -> screenOn = true
                    Intent.ACTION_USER_PRESENT -> {
                        screenOn = true
                        mainHandler.removeCallbacks(foregroundRecheck)
                        mainHandler.postDelayed(foregroundRecheck, 300)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(service).logNonFatalError(e)
            }
        }
    }
    private val intentReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == ADD_NEW_INTENT) {
                val pkg = intent.getStringExtra("package_name") ?: return
                val text = intent.getStringExtra("intent_text") ?: return
                val duration = intent.getLongExtra("duration_ms", 0)
                activeIntents[pkg] = Pair(text, System.currentTimeMillis() + duration)
                Log.d("active intent", activeIntents.toString())
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setup(service: BaseBlockingService) {
        this.service = service
        this.overlayManager = MindfulMessageOverlayManager(service)
        powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        screenOn = powerManager.isInteractive
        try {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            service.registerReceiver(screenReceiver, screenFilter)
            screenReceiverRegistered = true
        } catch (e: Exception) {
            CrashLogger(service).logNonFatalError(e)
        }

        val filter = IntentFilter().apply {
            addAction(ADD_NEW_INTENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(intentReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(intentReceiver, filter)
        }

        ignoredpackages = listOf(getCurrentKeyboardPackageName(service) ?: "com.google.android.inputmethod.latin",
            "com.android.systemui",
            "com.google.android.apps.wellbeing")
        scope.launch {
            try {
                service.dataStoreManager.settings.collectLatest { settings ->
                    config = settings.mindfulMessageConfig
                    appGroups = settings.blockedAppGroups
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(service).logNonFatalError(e)
            }
        }
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (destroyed || !canDisplay()) return
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val pkg = event.packageName?.toString() ?: return

        if (pkg in ignoredpackages || currentPkg == pkg) return
        if (!service.isActivePackage(pkg)) return

        updateForegroundPackage(pkg)
    }

    private fun canDisplay(): Boolean {
        if (screenOn && powerManager.isInteractive && !keyguardManager.isKeyguardLocked) return true
        endSession()
        return false
    }

    private fun endSession() {
        currentPkg = null
        overlayManager.removeOverlay()
    }

    private fun updateForegroundPackage(pkg: String) {
        if (destroyed || !canDisplay() || pkg in ignoredpackages || currentPkg == pkg) return

        activeIntents.entries.removeIf { System.currentTimeMillis() > it.value.second }

        val activeIntent = activeIntents[pkg]
        val shouldShowOverlay = activeIntent != null || (config.isActive && config.selectedApps.contains(pkg))

        if (shouldShowOverlay) {
            val displayConfig = if (activeIntent != null) {
                config.copy(messages = "Your Intent:\n${activeIntent.first}\n\nTime used today: {app_usage_today}")
            } else {
                config
            }

            if (pkg != currentPkg) {
                overlayManager.removeOverlay()
                currentPkg = pkg
            }
            if (Settings.canDrawOverlays(service) && !overlayManager.isOverlayVisible) {
                overlayManager.startDisplaying(pkg, displayConfig, appGroups)
            }
        } else if (overlayManager.isOverlayVisible) {
            currentPkg = null
            overlayManager.removeOverlay()
        }
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        mainHandler.removeCallbacks(foregroundRecheck)
        scope.cancel()
        if (screenReceiverRegistered) {
            try {
                service.unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                CrashLogger(service).logNonFatalError(e)
            }
            screenReceiverRegistered = false
        }
        try {
            service.unregisterReceiver(intentReceiver)
        } catch(e: Exception) {}
        endSession()
    }
}
