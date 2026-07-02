package neth.iecal.curbox.blockers

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.uihider.NodeFinder
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.ViewUtils

/**
 * Keeps Curbox installed and running by bouncing the user away from the screens that could undo
 * protection while it is locked: the device admin deactivation screen and the accessibility service
 * disable screens. When a timed or cooldown unlock finishes, the admin is removed so the user can
 * leave freely.
 */
class AntiUninstallBlocker : BaseBlocker() {

    private companion object {
        // The AOSP activity that activates or deactivates a device admin.
        const val DEVICE_ADMIN_SCREEN_CLASS = "DeviceAdminAdd"
        const val SETTINGS_PACKAGE = "com.android.settings"
        val SERVICE_LABELS = listOf("App Blocker", "Usage Tracker")
    }

    @Volatile private var config: AntiUninstallConfig = AntiUninstallConfig()
    private var lastScreenScan = 0L
    private lateinit var service: AppBlockerService
    private var settingsJob: kotlinx.coroutines.Job? = null

    fun setupBlocker(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { config = it.antiUninstallConfig }
        }
    }

    fun onDestroy() {
        settingsJob?.cancel()
    }

    fun doAntiUninstallCheck(event: AccessibilityEvent?) {
        event ?: return
        val current = config
        if (!current.isEnabled || !AntiUninstallManager.isAdminActive(service)) return

        // A finished timed or cooldown unlock lifts protection for good.
        if (AntiUninstallManager.isUnlockComplete(current)) {
            finishUnlock()
            return
        }

        if (event.className?.toString()?.contains(DEVICE_ADMIN_SCREEN_CLASS) == true) {
            service.pressBack()
            service.pressHome()
            return
        }

        if(event.packageName == SETTINGS_PACKAGE) {
            // Bounce the moment the user taps Curbox's row in accessibility settings.
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && clickHitsOurService(
                event.source
            )) {
                service.pressBack()
                service.pressHome()
                return
            }

            val nodes = service.rootInActiveWindow.findAccessibilityNodeInfosByText(service.getString(R.string.accessibility_permission_app_blocker))
            val nodes2 = service.rootInActiveWindow.findAccessibilityNodeInfosByText(service.getString(R.string.accessibility_permission_usage_tracker))

            if(!(nodes.isNullOrEmpty() || nodes2.isNullOrEmpty())){
                service.pressBack()
                service.pressHome()
            }
        }

    }

    /** True if the clicked node, or anything under it, is one of Curbox's accessibility services. */
    private fun clickHitsOurService(source: AccessibilityNodeInfo?): Boolean {
        source ?: return false
        try {
            return SERVICE_LABELS.any { label ->
                val matches = source.findAccessibilityNodeInfosByText(label)
                val found = !matches.isNullOrEmpty()
                matches?.forEach { NodeFinder.recycle(it) }
                Log.d("Anti Uninstall","click found")

                found
            }
        } finally {
            NodeFinder.recycle(source)
        }
    }

    private fun finishUnlock() {
        AntiUninstallManager.removeProtection(service)
        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.updateAntiUninstallConfig {
                it.copy(isEnabled = false, unlockRequestedAtMs = 0L)
            }
        }
    }
}
