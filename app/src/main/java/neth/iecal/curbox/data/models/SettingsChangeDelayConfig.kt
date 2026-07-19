package neth.iecal.curbox.data.models

/**
 * Which parts of [Settings] the change delay watches. The enum name is stored inside
 * [PendingSettingsChange.field], so renaming an entry silently drops any pending change
 * that was saved under the old name.
 */
enum class GatedSettingsField {
    APP_GROUPS,
    AUTO_DND_GROUPS,
    REEL_BLOCKER,
    KEYWORD_BLOCKER,
    REEL_COUNTER,
    GRAYSCALE_GROUPS,
    MINDFUL_MESSAGES,
    UI_HIDER,
    APP_USAGE_TRACKING,
    WEBSITE_USAGE_TRACKING,
    CHANGE_DELAY
}

/**
 * A settings change that weakens a restriction and is waiting out the delay.
 * [newValueJson] is the Gson form of the field's new value, captured at the moment
 * the user asked for the change.
 */
data class PendingSettingsChange(
    val field: String = "",
    val newValueJson: String = "",
    val requestedAtMs: Long = 0L,
    val appliesAtMs: Long = 0L
)

/**
 * The payload stored when the change delay's own settings are reduced. Kept separate from
 * [SettingsChangeDelayConfig] so a pending change never snapshots the pending list itself.
 */
data class SettingsChangeDelayPrefs(
    val isEnabled: Boolean = false,
    val delayMinutes: Int = SettingsChangeDelayConfig.DEFAULT_DELAY_MINUTES,
    val requireTamperProtectionOff: Boolean = false
)

data class SettingsChangeDelayConfig(
    val isEnabled: Boolean = false,
    val delayMinutes: Int = DEFAULT_DELAY_MINUTES,
    val pendingChanges: List<PendingSettingsChange> = emptyList(),
    /**
     * When on, a weakening change stays parked for as long as tamper protection is on,
     * regardless of [delayMinutes]. It only releases once the user turns tamper protection off.
     */
    val requireTamperProtectionOff: Boolean = false
) {
    companion object {
        /** No wait by default; the gate only defers changes once the user picks a duration. */
        const val DEFAULT_DELAY_MINUTES = 0
        /** Two days. Keeps the user from locking themselves out with an absurd delay. */
        const val MAX_DELAY_MINUTES = 2880
    }
}
