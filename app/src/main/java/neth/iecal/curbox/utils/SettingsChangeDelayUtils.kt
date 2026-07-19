package neth.iecal.curbox.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.GatedSettingsField

/**
 * Small helpers around the settings change delay. The gate itself lives in
 * [DataStoreManager.applyDuePendingChanges] and DataStoreManager's updateGated.
 */
object SettingsChangeDelayUtils {

    private val sweepScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Fire and forget sweep for callers without a coroutine scope, like Application.onCreate. */
    fun applyDuePendingChangesAsync(context: Context) {
        val appContext = context.applicationContext
        sweepScope.launch {
            try {
                DataStoreManager(appContext).applyDuePendingChanges()
            } catch (_: Exception) {
            }
        }
    }

    fun fieldLabel(context: Context, fieldName: String): String {
        val field = runCatching { GatedSettingsField.valueOf(fieldName) }.getOrNull()
        return context.getString(
            when (field) {
                GatedSettingsField.APP_GROUPS -> R.string.change_delay_field_app_groups
                GatedSettingsField.AUTO_DND_GROUPS -> R.string.change_delay_field_auto_dnd
                GatedSettingsField.REEL_BLOCKER -> R.string.change_delay_field_reel_blocker
                GatedSettingsField.KEYWORD_BLOCKER -> R.string.change_delay_field_keyword_blocker
                GatedSettingsField.REEL_COUNTER -> R.string.change_delay_field_reel_counter
                GatedSettingsField.GRAYSCALE_GROUPS -> R.string.change_delay_field_grayscale
                GatedSettingsField.MINDFUL_MESSAGES -> R.string.change_delay_field_mindful_messages
                GatedSettingsField.UI_HIDER -> R.string.change_delay_field_ui_hider
                GatedSettingsField.APP_USAGE_TRACKING -> R.string.app_usage_tracking
                GatedSettingsField.WEBSITE_USAGE_TRACKING -> R.string.website_usage_tracking
                GatedSettingsField.CHANGE_DELAY, null -> R.string.change_delay_field_change_delay
            }
        )
    }

    fun formatRemaining(context: Context, remainingMs: Long): String {
        val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (days > 0) {
            context.getString(R.string.anti_uninstall_remaining_with_days, days, hours, minutes, seconds)
        } else {
            context.getString(R.string.anti_uninstall_remaining, hours, minutes, seconds)
        }
    }
}
