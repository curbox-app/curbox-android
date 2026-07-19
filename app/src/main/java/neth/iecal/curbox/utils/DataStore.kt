package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.SettingsChangeDelayPrefs
import neth.iecal.curbox.hardcoded.normalized
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import kotlin.jvm.java

class GsonSerializer<T>(
    private val gson: Gson,
    private val type: Type,
    override val defaultValue: T
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T {
        return try {
            gson.fromJson(input.readBytes().decodeToString(), type) ?: defaultValue
        } catch (e: Exception) {
            e.printStackTrace()
            defaultValue
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(gson.toJson(t).toByteArray())
    }
}

class DataStoreManager(private val context: Context) {
    private val gson = Gson()

    companion object {
        @Volatile
        private var INSTANCE: androidx.datastore.core.DataStore<Settings>? = null

        fun getSettingsDataStore(context: Context, gson: Gson): androidx.datastore.core.DataStore<Settings> {
            // Double checked locking: the inner re-check is essential. Without it,
            // two threads racing the first access each build a DataStore for the
            // same file, which crashes with "There are multiple DataStores active
            // for the same file". This is easy to hit on a cold start (e.g. right
            // after a reinstall) when several coroutines touch settings at once.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MultiProcessDataStoreFactory.create(
                    serializer = GsonSerializer(
                        gson = gson,
                        type = Settings::class.java,
                        defaultValue = Settings()
                    ),
                    produceFile = { File(context.applicationContext.filesDir, "datastore/settings.json") }
                ).also { INSTANCE = it }
            }
        }
    }

    private val settingsDataStore = getSettingsDataStore(context, gson)

    val settings = settingsDataStore.data

    suspend fun updateAppGroups(newGroups: List<AppGroup>) {
        updateGated(GatedSettingsField.APP_GROUPS) { newGroups }
    }

    suspend fun updateManualFocusGroups(newGroup: List<ManualFocusGroup>){
        settingsDataStore.updateData { it.copy(manualFocusGroups = newGroup) }
    }

    suspend fun updateAutoDndGroups(newGroups: List<neth.iecal.curbox.data.models.AutoDndGroup>) {
        updateGated(GatedSettingsField.AUTO_DND_GROUPS) { newGroups }
    }
    
    suspend fun setManualFocusStateToActive(focusGroupId:String, durationInMs: Long){
        settingsDataStore.updateData { it.copy(activeManualFocusGroupId = Pair(focusGroupId, System.currentTimeMillis() + durationInMs)) }
    }
    suspend fun setManualFocusStateToInactive(){
        settingsDataStore.updateData { it.copy(activeManualFocusGroupId = Pair(null, 0)) }
    }

    suspend fun updateReelBlockerConfig(config: neth.iecal.curbox.data.models.ReelBlocker) {
        updateGated(GatedSettingsField.REEL_BLOCKER) { config }
    }

    suspend fun updateKeywordBlockerConfig(transform: (neth.iecal.curbox.data.models.KeywordBlocker) -> neth.iecal.curbox.data.models.KeywordBlocker) {
        updateGated(GatedSettingsField.KEYWORD_BLOCKER) { transform(it.keywordBlockerConfig) }
    }

    suspend fun updateReelCounterState(isActive: Boolean) {
        updateGated(GatedSettingsField.REEL_COUNTER) { isActive }
    }

    suspend fun updateGrayscaleGroups(newGroups: List<neth.iecal.curbox.data.models.GrayscaleGroup>) {
        updateGated(GatedSettingsField.GRAYSCALE_GROUPS) { newGroups }
    }

    suspend fun updateUsageTrackerIgnoredApps(newApps: List<String>) {
        settingsDataStore.updateData { it.copy(usageTrackerIgnoredApps = newApps) }
    }

    suspend fun updateAppUsageTrackingEnabled(isEnabled: Boolean) {
        updateGated(GatedSettingsField.APP_USAGE_TRACKING) { isEnabled }
    }

    suspend fun updateWebsiteUsageTrackingEnabled(isEnabled: Boolean) {
        updateGated(GatedSettingsField.WEBSITE_USAGE_TRACKING) { isEnabled }
    }

    suspend fun updateMindfulMessageConfig(config: neth.iecal.curbox.data.models.MindfulMessageConfig) {
        updateGated(GatedSettingsField.MINDFUL_MESSAGES) { config }
    }

    suspend fun updateUiHiderConfig(transform: (neth.iecal.curbox.data.models.UiHiderConfig) -> neth.iecal.curbox.data.models.UiHiderConfig) {
        updateGated(GatedSettingsField.UI_HIDER) { transform(it.uiHiderConfig.normalized()) }
    }

    suspend fun updateReelCounterOverlayConfig(config: neth.iecal.curbox.data.models.ReelCounterOverlayConfig) {
        settingsDataStore.updateData { it.copy(reelCounterOverlayConfig = config) }
    }

    suspend fun updateNextWebsiteRecheckTime(time: Long) {
        settingsDataStore.updateData { it.copy(nextWebsiteRecheckTime = time) }
    }

    suspend fun updateAntiUninstallConfig(transform: (neth.iecal.curbox.data.models.AntiUninstallConfig) -> neth.iecal.curbox.data.models.AntiUninstallConfig) {
        settingsDataStore.updateData { it.copy(antiUninstallConfig = transform(it.antiUninstallConfig)) }
    }

    suspend fun updateServiceProtectionConfig(transform: (neth.iecal.curbox.data.models.ServiceProtectionConfig) -> neth.iecal.curbox.data.models.ServiceProtectionConfig) {
        settingsDataStore.updateData { it.copy(serviceProtectionConfig = transform(it.serviceProtectionConfig)) }
    }

    suspend fun updateSettingsChangeDelay(isEnabled: Boolean, delayMinutes: Int, requireTamperProtectionOff: Boolean) {
        val clamped = delayMinutes.coerceIn(0, SettingsChangeDelayConfig.MAX_DELAY_MINUTES)
        updateGated(GatedSettingsField.CHANGE_DELAY) { SettingsChangeDelayPrefs(isEnabled, clamped, requireTamperProtectionOff) }
    }

    suspend fun cancelPendingSettingsChange(fieldName: String) {
        settingsDataStore.updateData {
            val config = it.settingsChangeDelayConfig
            it.copy(settingsChangeDelayConfig = config.copy(
                pendingChanges = config.pendingChanges.filterNot { p -> p.field == fieldName }
            ))
        }
    }

    /**
     * Writes any pending settings change whose countdown has run out into the live settings.
     * Called from the blocking service heartbeat, app start and the change delay screen, so
     * a due change lands no matter which process is alive. Returns true if anything applied.
     */
    suspend fun applyDuePendingChanges(): Boolean {
        var appliedAny = false
        settingsDataStore.updateData { current ->
            val now = System.currentTimeMillis()
            val delayConfig = current.settingsChangeDelayConfig
            // Tamper protection being on holds every pending change back, no matter how long
            // its own timer has already run out, until the user turns tamper protection off
            val tamperBlocked = delayConfig.requireTamperProtectionOff && current.antiUninstallConfig.isEnabled
            val (due, waiting) = delayConfig.pendingChanges
                .partition { it.appliesAtMs <= now && !tamperBlocked }
            appliedAny = due.isNotEmpty()
            if (due.isEmpty()) {
                current
            } else {
                var settings = current
                due.forEach { change ->
                    applyPendingValue(settings, change)?.let { settings = it }
                }
                settings.copy(settingsChangeDelayConfig = settings.settingsChangeDelayConfig.copy(
                    pendingChanges = waiting
                ))
            }
        }
        return appliedAny
    }

    /**
     * The settings change delay gate. Changes that keep every restriction at least as strong
     * apply right away; anything else is parked as a [PendingSettingsChange] until the
     * countdown ends. A stricter instant write also drops the field's pending change, because
     * that pending snapshot no longer matches what the user sees.
     */
    private suspend fun updateGated(field: GatedSettingsField, computeNewValue: (Settings) -> Any) {
        var deferredUntilMs = 0L
        var hadPendingForField = false
        var tamperGated = false
        settingsDataStore.updateData { current ->
            deferredUntilMs = 0L
            hadPendingForField = false
            tamperGated = false
            val newValueJson = gson.toJson(computeNewValue(current))
            val proposed = withFieldValue(current, field, newValueJson) ?: return@updateData current
            hadPendingForField = current.settingsChangeDelayConfig.pendingChanges.any { it.field == field.name }
            val delayConfig = current.settingsChangeDelayConfig
            val timeGateActive = delayConfig.isEnabled && delayConfig.delayMinutes > 0
            tamperGated = delayConfig.requireTamperProtectionOff && current.antiUninstallConfig.isEnabled
            if ((!timeGateActive && !tamperGated) ||
                RestrictionComparator.isSameOrStricter(field, current, proposed)
            ) {
                tamperGated = false
                val proposedDelayConfig = proposed.settingsChangeDelayConfig
                proposed.copy(settingsChangeDelayConfig = proposedDelayConfig.copy(
                    pendingChanges = proposedDelayConfig.pendingChanges.filterNot { it.field == field.name }
                ))
            } else {
                val now = System.currentTimeMillis()
                // With no active timer, the pending value is due the instant tamper protection
                // turns off; applyDuePendingChanges re-checks that condition on every sweep.
                deferredUntilMs = if (timeGateActive) now + delayConfig.delayMinutes * 60_000L else now
                val pending = PendingSettingsChange(
                    field = field.name,
                    newValueJson = newValueJson,
                    requestedAtMs = now,
                    appliesAtMs = deferredUntilMs
                )
                current.copy(settingsChangeDelayConfig = delayConfig.copy(
                    pendingChanges = delayConfig.pendingChanges.filterNot { it.field == field.name } + pending
                ))
            }
        }
        if (deferredUntilMs > 0L) {
            notifyChangeDeferred(field, deferredUntilMs, hadPendingForField, tamperGated)
        } else if (hadPendingForField) {
            notifyPendingChangeDropped(field)
        }
    }

    private fun applyPendingValue(settings: Settings, change: PendingSettingsChange): Settings? {
        val field = runCatching { GatedSettingsField.valueOf(change.field) }.getOrNull() ?: return null
        return withFieldValue(settings, field, change.newValueJson)
    }

    private fun withFieldValue(settings: Settings, field: GatedSettingsField, valueJson: String): Settings? {
        return runCatching {
            when (field) {
                GatedSettingsField.APP_GROUPS -> settings.copy(
                    blockedAppGroups = gson.fromJson(valueJson, object : TypeToken<List<AppGroup>>() {}.type)
                )
                GatedSettingsField.AUTO_DND_GROUPS -> settings.copy(
                    autoDndGroups = gson.fromJson(valueJson, object : TypeToken<List<neth.iecal.curbox.data.models.AutoDndGroup>>() {}.type)
                )
                GatedSettingsField.REEL_BLOCKER -> settings.copy(
                    reelBlockerConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.ReelBlocker::class.java)
                )
                GatedSettingsField.KEYWORD_BLOCKER -> settings.copy(
                    keywordBlockerConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.KeywordBlocker::class.java)
                )
                GatedSettingsField.REEL_COUNTER -> settings.copy(
                    isReelCounterOn = gson.fromJson(valueJson, Boolean::class.java)
                )
                GatedSettingsField.GRAYSCALE_GROUPS -> settings.copy(
                    grayscaleGroups = gson.fromJson(valueJson, object : TypeToken<List<neth.iecal.curbox.data.models.GrayscaleGroup>>() {}.type)
                )
                GatedSettingsField.MINDFUL_MESSAGES -> settings.copy(
                    mindfulMessageConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.MindfulMessageConfig::class.java)
                )
                GatedSettingsField.UI_HIDER -> settings.copy(
                    uiHiderConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.UiHiderConfig::class.java)
                )
                GatedSettingsField.APP_USAGE_TRACKING -> settings.copy(
                    isAppUsageTrackingEnabled = gson.fromJson(valueJson, Boolean::class.java)
                )
                GatedSettingsField.WEBSITE_USAGE_TRACKING -> settings.copy(
                    isWebsiteUsageTrackingEnabled = gson.fromJson(valueJson, Boolean::class.java)
                )
                GatedSettingsField.CHANGE_DELAY -> {
                    val prefs = gson.fromJson(valueJson, SettingsChangeDelayPrefs::class.java)
                    settings.copy(settingsChangeDelayConfig = settings.settingsChangeDelayConfig.copy(
                        isEnabled = prefs.isEnabled,
                        delayMinutes = prefs.delayMinutes,
                        requireTamperProtectionOff = prefs.requireTamperProtectionOff
                    ))
                }
            }
        }.getOrNull()
    }

    /**
     * Pops the review warning screen so an accidental weakening can be undone on the spot.
     * Falls back to a toast when the screen cannot be shown, for example when the change came
     * from the Curbox API while the app is in the background.
     */
    private fun notifyChangeDeferred(
        field: GatedSettingsField,
        appliesAtMs: Long,
        replacedExisting: Boolean,
        tamperGated: Boolean
    ) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent(appContext, neth.iecal.curbox.ui.activity.PendingChangeReviewActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_FIELD, field.name)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_APPLIES_AT_MS, appliesAtMs)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_REPLACED_EXISTING, replacedExisting)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_TAMPER_GATED, tamperGated)
                }
                appContext.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val message = if (tamperGated) {
                        appContext.getString(R.string.change_delay_tamper_gated_toast)
                    } else {
                        val remaining = SettingsChangeDelayUtils.formatRemaining(appContext, appliesAtMs - System.currentTimeMillis())
                        appContext.getString(R.string.change_delay_deferred_toast, remaining)
                    }
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun notifyPendingChangeDropped(field: GatedSettingsField) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(
                    appContext,
                    appContext.getString(
                        R.string.change_delay_dropped_toast,
                        SettingsChangeDelayUtils.fieldLabel(appContext, field.name)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
            }
        }
    }
}
