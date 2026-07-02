package neth.iecal.curbox.data.models

/**
 * Settings for the layers that keep the accessibility services alive and bring them back when the
 * system kills them. None of these make the app truly un killable, they only make it very hard to
 * kill and quick to recover.
 */
data class ServiceProtectionConfig(
    /** Master switch. When off, the watchdog and self healing do nothing. */
    val isEnabled: Boolean = false,

    /**
     * When Shizuku is available, let the watchdog silently turn the accessibility services back on
     * if the system or an OEM battery manager switched them off.
     */
    val selfHealWithShizuku: Boolean = true,

    /**
     * Last real time millis each service reported it was alive. The two services run in different
     * processes, so each one watches the other's heartbeat and revives it when it goes stale.
     */
    val appBlockerLastAliveMs: Long = 0L,
    val usageTrackerLastAliveMs: Long = 0L
)
