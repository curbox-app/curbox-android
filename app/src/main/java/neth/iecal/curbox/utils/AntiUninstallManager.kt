package neth.iecal.curbox.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.data.models.AntiUninstallMode
import neth.iecal.curbox.receivers.AdminReceiver
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Wraps the device admin used for uninstall protection and the small bits of timing and
 * password logic that both the settings screen and the blocker need to share.
 */
object AntiUninstallManager {

    /** The fixed wait used by [AntiUninstallMode.COOLDOWN]. */
    val COOLDOWN_DURATION_MS: Long = TimeUnit.DAYS.toMillis(1)

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, AdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(context: Context): Boolean =
        dpm(context).isAdminActive(adminComponent(context))

    /** Removes the device admin so the app can be uninstalled again. Safe to call when inactive. */
    fun removeProtection(context: Context) {
        val dpm = dpm(context)
        val component = adminComponent(context)
        if (dpm.isAdminActive(component)) {
            dpm.removeActiveAdmin(component)
        }
    }

    fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** How long the wait lasts before protection lifts for the given config. */
    fun unlockDurationMs(config: AntiUninstallConfig): Long = when (config.mode) {
        AntiUninstallMode.TIMED -> TimeUnit.DAYS.toMillis(config.timedUnlockDays.toLong())
        else -> COOLDOWN_DURATION_MS
    }

    /** Real time millis when a pending unlock finishes, or null if no unlock is pending. */
    fun unlockCompletesAt(config: AntiUninstallConfig): Long? {
        if (config.unlockRequestedAtMs <= 0L) return null
        return config.unlockRequestedAtMs + unlockDurationMs(config)
    }

    fun isUnlockComplete(config: AntiUninstallConfig): Boolean {
        val completesAt = unlockCompletesAt(config) ?: return false
        return System.currentTimeMillis() >= completesAt
    }
}
