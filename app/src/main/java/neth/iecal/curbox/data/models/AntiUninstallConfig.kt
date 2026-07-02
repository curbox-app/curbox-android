package neth.iecal.curbox.data.models

/**
 * The ways the user can later switch uninstall protection back off.
 */
enum class AntiUninstallMode {
    /** Protection lifts the moment the correct password is entered. */
    PASSWORD,

    /** Protection lifts on its own after a fixed number of days. */
    TIMED,

    /** Protection lifts one day after the user asks, and the request can be taken back anytime. */
    COOLDOWN
}

data class AntiUninstallConfig(
    val isEnabled: Boolean = false,
    val mode: AntiUninstallMode = AntiUninstallMode.PASSWORD,

    /** SHA-256 hash of the password. Only used in [AntiUninstallMode.PASSWORD]. */
    val passwordHash: String = "",

    /** How many days to wait before protection lifts. Only used in [AntiUninstallMode.TIMED]. */
    val timedUnlockDays: Int = 7,

    /**
     * Real time millis when the user asked to turn protection off.
     * 0 means no request is pending. Used by [AntiUninstallMode.TIMED] and [AntiUninstallMode.COOLDOWN].
     */
    val unlockRequestedAtMs: Long = 0L
)
