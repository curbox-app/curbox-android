package neth.iecal.curbox.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import neth.iecal.curbox.receivers.AdminReceiver
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.UsageTrackingService

/**
 * The brain behind the Service Protection screen and the watchdog. It checks how well protected the
 * services are and, when Shizuku is available, repairs the things an OEM battery manager tends to
 * break: it turns the accessibility services back on and re keeps the app out of doze and background
 * limits.
 *
 * Nothing here can make the app truly un killable. A force stop still wins. The goal is only to make
 * the services hard to kill and fast to bring back.
 */
object ServiceProtectionManager {

    private const val TAG = "ServiceProtection"

    fun isAppBlockerEnabled(context: Context): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)

    fun isUsageTrackerEnabled(context: Context): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(context, UsageTrackingService::class.java)

    fun areBothServicesEnabled(context: Context): Boolean =
        isAppBlockerEnabled(context) && isUsageTrackerEnabled(context)

    /** Shizuku is the only thing that lets us silently turn services back on, so it gates self healing. */
    fun canSelfHeal(): Boolean =
        PermissionUtils.isShizukuAvailable() && PermissionUtils.hasShizukuPermission()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** Intent that asks the user to drop battery optimization for the app. */
    fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Checks both services and, if either is off and Shizuku can help, turns them back on. Always
     * re applies the doze and background exemptions so they survive system house keeping.
     *
     * Safe to call from anywhere. The actual shell work runs on its own thread inside [ShizukuRunner].
     */
    fun healNow(context: Context) {
        try {
            if (!canSelfHeal()) return
            reinforceBackgroundExecution(context)
            if (!areBothServicesEnabled(context)) {
                reEnableAccessibilityServices(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "healNow failed", e)
        }
    }

    /** Adds any missing Curbox accessibility service back into the secure settings list, via Shizuku. */
    private fun reEnableAccessibilityServices(context: Context) {
        val wanted = listOf(
            ComponentName(context, AppBlockerService::class.java).flattenToString(),
            ComponentName(context, UsageTrackingService::class.java).flattenToString()
        )

        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        val existing = linkedSetOf<String>()
        if (!current.isNullOrEmpty()) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(current)
            while (splitter.hasNext()) existing.add(splitter.next())
        }
        existing.addAll(wanted)

        val merged = existing.joinToString(":")
        runShell(
            "settings put secure enabled_accessibility_services '$merged'; " +
                "settings put secure accessibility_enabled 1"
        )
        Log.i(TAG, "Re enabled accessibility services via Shizuku")
    }

    /** Keeps the app out of doze and lets it run freely in the background. Needs Shizuku. */
    fun reinforceBackgroundExecution(context: Context) {
        if (!canSelfHeal()) return
        val pkg = context.packageName
        runShell(
            "dumpsys deviceidle whitelist +$pkg; " +
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; " +
                "cmd appops set $pkg RUN_IN_BACKGROUND allow"
        )
    }

    /**
     * The strongest tier. Makes Curbox a device owner, which survives force stop and blocks uninstall
     * outright. Only works on a device with no added accounts and is hard to undo, so it stays behind
     * a clear warning in the UI. Needs Shizuku.
     */
    fun setDeviceOwner(context: Context) {
        if (!canSelfHeal()) return
        val admin = ComponentName(context, AdminReceiver::class.java).flattenToString()
        runShell("dpm set-device-owner '$admin'")
    }

    private fun runShell(command: String) {
        ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
            override fun onCommandError(error: String) {
                Log.e(TAG, "shell error: $error")
            }
        })
    }
}
