package neth.iecal.curbox.hardcoded

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Hardcoded entry points for the "autostart" or "protected apps" screens that aggressive OEM skins
 * (MIUI, ColorOS, Funtouch, EMUI, OneUI and others) hide in their own settings. Android has no
 * standard intent for these, so we keep a known list and try each one for the current maker.
 */
object OemAutostartIntents {

    private val candidates: List<ComponentName> = listOf(
        // Xiaomi / MIUI
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // Oppo / ColorOS
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // Vivo / Funtouch
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // Huawei / EMUI
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // Samsung / OneUI
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        // OnePlus / Oxygen
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        // Letv, Asus
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity")
    )

    /** True when the device looks like one of the makers that needs the extra autostart step. */
    fun isLikelyAggressiveOem(): Boolean {
        val maker = Build.MANUFACTURER.lowercase()
        return listOf("xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo", "huawei", "honor", "oneplus", "samsung", "letv", "asus")
            .any { maker.contains(it) }
    }

    /** Returns an intent to the OEM autostart screen that actually exists on this device, or null. */
    fun resolveAutostartIntent(context: Context): Intent? {
        val pm = context.packageManager
        for (component in candidates) {
            val intent = Intent().setComponent(component)
            if (pm.resolveActivity(intent, 0) != null) {
                return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }
}
