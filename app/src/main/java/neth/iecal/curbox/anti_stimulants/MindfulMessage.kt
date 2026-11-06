package neth.iecal.curbox.anti_stimulants

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.overlay.MindfulMessageOverlayManager
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.isActivePackage

class MindfulMessage(private val remainingUnlockMillis: (String) -> Long) {

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
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private var config = MindfulMessageConfig()
    private var showUnlockCountdown = false
    private var appGroups = emptyList<AppGroup>()
    private var currentPkg: String? = null
    private var ignoredpackages = listOf<String>()

    private val activeIntents = mutableMapOf<String, Pair<String, Long>>()
    private val intentReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == ADD_NEW_INTENT) {
                val pkg = intent.getStringExtra("package_name") ?: return
                val text = intent.getStringExtra("intent_text") ?: return
                val duration = intent.getLongExtra("duration_ms", 0)
                activeIntents[pkg] = Pair(text, System.currentTimeMillis() + duration)
                refreshOverlay()
            } else if (intent.action == Intent.ACTION_USER_PRESENT) {
                refreshOverlay(updateForegroundPackage = true)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setup(service: BaseBlockingService) {
        this.service = service
        this.overlayManager = MindfulMessageOverlayManager(service, remainingUnlockMillis)
        powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager


        val filter = IntentFilter().apply {
            addAction(ADD_NEW_INTENT)
            addAction(Intent.ACTION_USER_PRESENT)
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
            service.dataStoreManager.settings.collectLatest { settings ->
                config = settings.mindfulMessageConfig
                appGroups = settings.blockedAppGroups
                showUnlockCountdown = appGroups.any {
                    it.isActive && !it.warningScreenConfig.isOnOpenConfig && it.warningScreenConfig.showUnlockCountdown
                } ||
                    (settings.keywordBlockerConfig.isActive && settings.keywordBlockerConfig.keywordGroups.any {
                        it.isActive && it.warningScreenConfig.showUnlockCountdown
                    }) || (settings.reelBlockerConfig.isActive &&
                    settings.reelBlockerConfig.warningScreenConfig.showUnlockCountdown)
                refreshOverlay()
                while (showUnlockCountdown) {
                    delay(1000)
                    refreshOverlay()
                }
            }
        }
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val pkg = event.packageName?.toString() ?: return

        if (pkg in ignoredpackages || currentPkg == pkg) return
        if (!service.isActivePackage(pkg)) return

        activeIntents.entries.removeIf { System.currentTimeMillis() > it.value.second }
        currentPkg = pkg
        overlayManager.removeOverlay()
        refreshOverlay()
    }

    @Suppress("DEPRECATION")
    private fun refreshOverlay(updateForegroundPackage: Boolean = false) {
        try {
            if (updateForegroundPackage) {
                val root = service.rootInActiveWindow
                val pkg = try {
                    root?.packageName?.toString()?.takeUnless { it in ignoredpackages }
                } finally {
                    root?.recycle()
                }
                if (pkg != currentPkg) {
                    activeIntents.entries.removeIf { System.currentTimeMillis() > it.value.second }
                    currentPkg = pkg
                    overlayManager.removeOverlay()
                }
            }
            val pkg = currentPkg
            if (pkg == null || !powerManager.isInteractive || keyguardManager.isKeyguardLocked ||
                !Settings.canDrawOverlays(service)) {
                overlayManager.removeOverlay()
                return
            }

            val activeIntent = activeIntents[pkg]
            var messages = when {
                activeIntent != null -> service.getString(R.string.mindful_intent_message, activeIntent.first)
                config.isActive && pkg in config.selectedApps -> config.messages
                else -> ""
            }
            if (showUnlockCountdown && !messages.contains("{remaining_unlock_time}") &&
                remainingUnlockMillis(pkg) > 0L) {
                messages = listOf(messages, service.getString(R.string.remaining_unlock_message))
                    .filter(String::isNotEmpty).joinToString("\n\n")
            }
            if (messages.isEmpty()) {
                overlayManager.removeOverlay()
            } else {
                overlayManager.startDisplaying(pkg, config.copy(messages = messages), appGroups)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CrashLogger(service).logNonFatalError(error)
        }
    }

    fun onDestroy() {
        try {
            service.unregisterReceiver(intentReceiver)
        } catch(e: Exception) {}
        scope.cancel()
        overlayManager.removeOverlay()
    }
}
