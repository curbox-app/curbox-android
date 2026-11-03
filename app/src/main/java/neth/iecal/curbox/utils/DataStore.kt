package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.ExternalUnlockCondition
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.core.DataMigration
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.PomodoroState
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.SettingsChangeDelayPrefs
import neth.iecal.curbox.data.models.upgradeLegacyAppGroupConfigs
import neth.iecal.curbox.data.models.upgradeLegacyKeywordGroupConfigs
import neth.iecal.curbox.data.models.upgradeLegacyConfig
import neth.iecal.curbox.hardcoded.normalized
import neth.iecal.curbox.services.TemporaryGroupDisableJob
import java.io.File
import java.io.IOException
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
            gson.fromJson<T>(input.readBytes().decodeToString(), type)
                ?: throw IOException("Settings file is empty or null")
        } catch (e: JsonParseException) {
            // A failed read must block updates, not replace the saved settings with defaults.
            throw IOException("Unable to read settings", e)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(gson.toJson(t).toByteArray())
    }
}

private class ScheduledUsageConfigMigration(
    private val gson: Gson
) : DataMigration<Settings> {
    override suspend fun shouldMigrate(currentData: Settings): Boolean {
        if (currentData.blockedAppGroups.any { it.config == null }) return true
        if (currentData.keywordBlockerConfig.keywordGroups.any { it.config == null }) return true
        return currentData.settingsChangeDelayConfig2.pendingChanges.any {
            pendingChangeNeedsMigration(it)
        }
    }

    override suspend fun migrate(currentData: Settings): Settings {
        val upgradedPending = currentData.settingsChangeDelayConfig2.pendingChanges.map { change ->
            when (change.field) {
                GatedSettingsField.APP_GROUPS.name -> {
                    val groups = parseAppGroups(change.newValueJson) ?: return@map change
                    change.copy(
                        newValueJson = gson.toJson(groups.upgradeLegacyAppGroupConfigs(gson))
                    )
                }
                GatedSettingsField.KEYWORD_BLOCKER.name -> {
                    val config = parseKeywordBlocker(change.newValueJson) ?: return@map change
                    change.copy(
                        newValueJson =
                            gson.toJson(config.upgradeLegacyKeywordGroupConfigs(gson))
                    )
                }
                else -> change
            }
        }
        return currentData.copy(
            blockedAppGroups = currentData.blockedAppGroups.upgradeLegacyAppGroupConfigs(gson),
            keywordBlockerConfig =
                currentData.keywordBlockerConfig.upgradeLegacyKeywordGroupConfigs(gson),
            settingsChangeDelayConfig2 = currentData.settingsChangeDelayConfig2.copy(
                pendingChanges = upgradedPending
            )
        )
    }

    override suspend fun cleanUp() = Unit

    private fun pendingChangeNeedsMigration(change: PendingSettingsChange): Boolean =
        when (change.field) {
            GatedSettingsField.APP_GROUPS.name ->
                parseAppGroups(change.newValueJson)?.any { it.config == null } == true
            GatedSettingsField.KEYWORD_BLOCKER.name ->
                parseKeywordBlocker(change.newValueJson)
                    ?.keywordGroups
                    ?.any { it.config == null } == true
            else -> false
        }

    private fun parseAppGroups(json: String): List<AppGroup>? = runCatching {
        gson.fromJson<List<AppGroup>>(json, object : TypeToken<List<AppGroup>>() {}.type)
    }.getOrNull()

    private fun parseKeywordBlocker(json: String): KeywordBlocker? = runCatching {
        gson.fromJson(json, KeywordBlocker::class.java)
    }.getOrNull()
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
                    migrations = listOf(ScheduledUsageConfigMigration(gson)),
                    produceFile = { File(context.applicationContext.filesDir, "datastore/settings.json") }
                ).also { INSTANCE = it }
            }
        }
    }

    private val settingsDataStore = getSettingsDataStore(context, gson)

    val settings = settingsDataStore.data

    /**
     * The values the user has requested, including changes still waiting for their deadline.
     * Settings editors should use this flow; blockers must keep using [settings].
     */
    val settingsForEditing = settings.map(::overlayPendingChanges).flowOn(Dispatchers.Default)

    suspend fun restoreSettingsDuringOnboarding(backup: Settings) {
        OnboardingState.restore(context) {
            val restored = SettingsBackupCodec.forDevice(
                backup,
                supportsUiHider = neth.iecal.curbox.BuildConfig.SUPPORTS_UI_HIDER,
                supportsGrayscale = neth.iecal.curbox.BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS
            )
            settingsDataStore.updateData { restored }
        }
        schedulePendingChanges()
        restoreDueTemporaryAppGroups()
    }

    suspend fun registerExternalUnlockCondition(condition: ExternalUnlockCondition) {
        settingsDataStore.updateData { current ->
            val existing = current.externalUnlockConditions.find {
                it.packageName == condition.packageName && it.id == condition.id
            }
            require(existing == null || existing == condition) { "Condition ids are immutable" }
            require(existing != null || current.externalUnlockConditions.count {
                it.packageName == condition.packageName
            } < 50) { "Too many conditions" }
            if (existing != null) current else current.copy(
                externalUnlockConditions = current.externalUnlockConditions + condition
            )
        }
    }

    suspend fun removeExternalUnlockCondition(packageName: String, id: String) {
        settingsDataStore.updateData { current ->
            current.copy(externalUnlockConditions = current.externalUnlockConditions.filterNot {
                it.packageName == packageName && it.id == id
            })
        }
    }

    suspend fun updateAppGroups(newGroups: List<AppGroup>) {
        updateGated(GatedSettingsField.APP_GROUPS) { newGroups }
    }

    suspend fun updateManualFocusGroups(newGroup: List<ManualFocusGroup>){
        settingsDataStore.updateData { it.copy(manualFocusGroups = newGroup) }
    }

    /**
     * Applies a configuration received from sync without letting another device bypass this
     * device's settings change delay. Runtime focus state stays on its dedicated sync channel,
     * and pending changes remain local because their deadlines are based on this device's clock.
     */
    suspend fun updateFromSync(remote: Settings) {
        settingsDataStore.updateData { current ->
            val delayConfig = current.settingsChangeDelayConfig2
            val timeGateActive = delayConfig.isEnabled && delayConfig.delayMinutes > 0
            val tamperGated = delayConfig.requireTamperProtectionOff &&
                current.antiUninstallConfig2.isEnabled

            val remoteWithLocalRuntime = remote.copy(
                blockedAppGroups = remote.blockedAppGroups.map { group ->
                    group.copy(
                        temporarilyDisabledUntilMs = current.blockedAppGroups
                            .find { it.id == group.id }
                            ?.temporarilyDisabledUntilMs
                            ?: 0L
                    )
                },
                reelBlockerConfig = remote.reelBlockerConfig.copy(
                    temporarilyDisabledUntilMs = current.reelBlockerConfig.temporarilyDisabledUntilMs
                ),
                keywordBlockerConfig = remote.keywordBlockerConfig.copy(
                    keywordGroups = remote.keywordBlockerConfig.keywordGroups.map { group ->
                        group.copy(
                            temporarilyDisabledUntilMs = current.keywordBlockerConfig.keywordGroups
                                .find { it.id == group.id }
                                ?.temporarilyDisabledUntilMs
                                ?: 0L
                        )
                    }
                ),
                // Active protection must be unlocked on this device. Sync must not turn it
                // off or replace its password and release tamper-gated pending changes.
                antiUninstallConfig2 = if (current.antiUninstallConfig2.isEnabled) current.antiUninstallConfig2
                else remote.antiUninstallConfig2.copy(
                    unlockRequestedAtMs = current.antiUninstallConfig2.unlockRequestedAtMs
                ),
                serviceProtectionConfig = remote.serviceProtectionConfig.copy(
                    appBlockerLastAliveMs = current.serviceProtectionConfig.appBlockerLastAliveMs
                ),
            )

            // Start with non-gated configuration from the remote device. Each gated field is
            // restored to its local value and considered independently below.
            var updated = remoteWithLocalRuntime.copy(
                blockedAppGroups = current.blockedAppGroups,
                manualFocusGroups = current.manualFocusGroups,
                autoDndGroups = current.autoDndGroups,
                activeManualFocusGroupId = current.activeManualFocusGroupId,
                activePomodoroState = current.activePomodoroState,
                reelBlockerConfig = current.reelBlockerConfig,
                keywordBlockerConfig = current.keywordBlockerConfig,
                isReelCounterOn = current.isReelCounterOn,
                grayscaleGroups = current.grayscaleGroups,
                isAppUsageTrackingEnabled = current.isAppUsageTrackingEnabled,
                isWebsiteUsageTrackingEnabled = current.isWebsiteUsageTrackingEnabled,
                mindfulMessageConfig = current.mindfulMessageConfig,
                uiHiderConfig = current.uiHiderConfig,
                nextWebsiteRecheckTime = current.nextWebsiteRecheckTime,
                externalUnlockConditions = current.externalUnlockConditions,
                settingsChangeDelayConfig2 = delayConfig,
            )

            for (field in GatedSettingsField.values()) {
                val newValueJson = syncedFieldValueJson(remoteWithLocalRuntime, field) ?: continue
                val existingPending = updated.settingsChangeDelayConfig2.pendingChanges
                    .find { it.field == field.name }
                if (existingPending?.newValueJson == newValueJson) continue

                val proposed = withFieldValue(updated, field, newValueJson) ?: continue
                if ((!timeGateActive && !tamperGated) ||
                    RestrictionComparator.isSameOrStricter(field, current, proposed)
                ) {
                    updated = proposed.copy(
                        settingsChangeDelayConfig2 = proposed.settingsChangeDelayConfig2.copy(
                            pendingChanges = proposed.settingsChangeDelayConfig2.pendingChanges
                                .filterNot { it.field == field.name }
                        )
                    )
                } else {
                    val now = System.currentTimeMillis()
                    val delayMinutes = delayConfig.delayMinutes.coerceIn(
                        0,
                        SettingsChangeDelayConfig.MAX_DELAY_MINUTES,
                    )
                    val appliesAt = if (timeGateActive) now + delayMinutes * 60_000L else now
                    val pending = PendingSettingsChange(
                        field = field.name,
                        newValueJson = newValueJson,
                        requestedAtMs = now,
                        appliesAtMs = appliesAt,
                    )
                    updated = updated.copy(
                        settingsChangeDelayConfig2 = updated.settingsChangeDelayConfig2.copy(
                            pendingChanges = updated.settingsChangeDelayConfig2.pendingChanges
                                .filterNot { it.field == field.name } + pending
                        )
                    )
                }
            }
            updated
        }
        schedulePendingChanges()
    }

    suspend fun updateAutoDndGroups(newGroups: List<neth.iecal.curbox.data.models.AutoDndGroup>) {
        updateGated(GatedSettingsField.AUTO_DND_GROUPS) { newGroups }
    }
    
    suspend fun setManualFocusStateToActive(
        focusGroupId: String,
        durationInMs: Long,
        pomodoroState: PomodoroState = PomodoroState(),
        endTimeInMillis: Long = System.currentTimeMillis() + durationInMs,
        requireExitableSession: Boolean = false
    ) {
        settingsDataStore.updateData {
            require(!requireExitableSession || neth.iecal.curbox.api.ApiRestrictionPolicy.canReplaceFocus(
                it, System.currentTimeMillis()
            )) { "The active focus session cannot be ended early" }
            it.copy(
                activeManualFocusGroupId = Pair(focusGroupId, endTimeInMillis),
                activePomodoroState = pomodoroState.normalized()
            )
        }
    }

    suspend fun advancePomodoroState(expectedEndTimeInMillis: Long): Settings? {
        var advanced = false
        val updated = settingsDataStore.updateData { current ->
            val groupId = current.activeManualFocusGroupId.first
            val pomodoro = current.activePomodoroState
            if (!pomodoro.isActive ||
                groupId == null ||
                current.activeManualFocusGroupId.second != expectedEndTimeInMillis
            ) {
                return@updateData current
            }

            advanced = true
            val next = pomodoro.nextPhase()
            if (!next.isActive) {
                current.copy(
                    activeManualFocusGroupId = Pair(null, 0L),
                    activePomodoroState = PomodoroState()
                )
            } else {
                current.copy(
                    activeManualFocusGroupId = Pair(
                        groupId,
                        System.currentTimeMillis() + next.currentPhaseDurationMs()
                    ),
                    activePomodoroState = next
                )
            }
        }
        return updated.takeIf { advanced }
    }

    suspend fun setManualFocusStateToInactive(requireExitableSession: Boolean = false) {
        settingsDataStore.updateData {
            require(!requireExitableSession || neth.iecal.curbox.api.ApiRestrictionPolicy.canReplaceFocus(
                it, System.currentTimeMillis()
            )) { "The active focus session cannot be ended early" }
            it.copy(
                activeManualFocusGroupId = Pair(null, 0L),
                activePomodoroState = PomodoroState()
            )
        }
    }

    suspend fun updateReelBlockerConfig(config: neth.iecal.curbox.data.models.ReelBlocker) {
        updateGated(GatedSettingsField.REEL_BLOCKER) { config.upgradeLegacyConfig(gson) }
    }

    suspend fun updateAllowInstagramReelsFromDmInbox(allow: Boolean) {
        updateGated(GatedSettingsField.REEL_BLOCKER) {
            it.reelBlockerConfig.upgradeLegacyConfig(gson).copy(
                allowInstagramReelsFromDmInbox = allow
            )
        }
    }

    suspend fun updateAllowTikTokVideosFromDmInbox(allow: Boolean) {
        updateGated(GatedSettingsField.REEL_BLOCKER) {
            it.reelBlockerConfig.upgradeLegacyConfig(gson).copy(
                allowTikTokVideosFromDmInbox = allow
            )
        }
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

    suspend fun updateUsageBackgroundArt(artName: String) {
        settingsDataStore.updateData { it.copy(usageBackgroundArt = artName) }
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
        settingsDataStore.updateData { it.copy(antiUninstallConfig2 = transform(it.antiUninstallConfig2)) }
        // Turning tamper protection off may release changes whose time delay already elapsed.
        applyDuePendingChanges()
    }

    suspend fun updateServiceProtectionConfig(transform: (neth.iecal.curbox.data.models.ServiceProtectionConfig) -> neth.iecal.curbox.data.models.ServiceProtectionConfig) {
        settingsDataStore.updateData { it.copy(serviceProtectionConfig = transform(it.serviceProtectionConfig)) }
    }

    suspend fun updateSettingsChangeDelay(
        isEnabled: Boolean,
        delayMinutes: Int,
        requireTamperProtectionOff: Boolean
    ) {
        val clamped = delayMinutes.coerceIn(0, SettingsChangeDelayConfig.MAX_DELAY_MINUTES)
        updateGated(GatedSettingsField.CHANGE_DELAY) {
            SettingsChangeDelayPrefs(
                isEnabled,
                clamped,
                requireTamperProtectionOff
            )
        }
    }

    suspend fun temporarilyDisableAppGroup(groupId: String, durationMinutes: Long): Boolean {
        val untilMs = temporaryDisableDeadline(durationMinutes)
        var changed = false
        var nextDeadline: Long? = null
        settingsDataStore.updateData { current ->
            if (current.settingsChangeDelayConfig2.isEnabled ||
                current.settingsChangeDelayConfig2.requireTamperProtectionOff) return@updateData current
            val groups = current.blockedAppGroups.map { group ->
                if (group.id == groupId && group.isActive) {
                    changed = true
                    group.copy(isActive = false, temporarilyDisabledUntilMs = untilMs)
                } else {
                    group
                }
            }
            val updated = current.copy(blockedAppGroups = groups)
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        if (changed) {
            neth.iecal.curbox.services.TemporaryGroupDisableJob.schedule(context, nextDeadline)
        }
        return changed
    }

    suspend fun temporarilyDisableKeywordGroup(groupId: String, durationMinutes: Long): Boolean {
        val untilMs = temporaryDisableDeadline(durationMinutes)
        var changed = false
        var nextDeadline: Long? = null
        settingsDataStore.updateData { current ->
            if (current.settingsChangeDelayConfig2.isEnabled ||
                current.settingsChangeDelayConfig2.requireTamperProtectionOff) return@updateData current
            val config = current.keywordBlockerConfig
            val groups = config.keywordGroups.map { group ->
                if (group.id == groupId && group.isActive) {
                    changed = true
                    group.copy(isActive = false, temporarilyDisabledUntilMs = untilMs)
                } else group
            }
            val updated = current.copy(keywordBlockerConfig = config.copy(keywordGroups = groups))
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        if (changed) TemporaryGroupDisableJob.schedule(context, nextDeadline)
        return changed
    }

    suspend fun temporarilyDisableReelBlocker(durationMinutes: Long): Boolean {
        val untilMs = temporaryDisableDeadline(durationMinutes)
        var changed = false
        var nextDeadline: Long? = null
        settingsDataStore.updateData { current ->
            if (current.settingsChangeDelayConfig2.isEnabled ||
                current.settingsChangeDelayConfig2.requireTamperProtectionOff ||
                !current.reelBlockerConfig.isActive
            ) return@updateData current
            changed = true
            val updated = current.copy(
                reelBlockerConfig = current.reelBlockerConfig.copy(
                    isActive = false,
                    temporarilyDisabledUntilMs = untilMs
                )
            )
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        if (changed) TemporaryGroupDisableJob.schedule(context, nextDeadline)
        return changed
    }

    /**
     * @param rescheduleAlways false skips rescheduling the job when nothing expired, so the
     * frequent service heartbeat does not keep cancelling and recreating it.
     */
    suspend fun restoreDueTemporaryAppGroups(rescheduleAlways: Boolean = true) {
        var nextDeadline: Long? = null
        var changed = false
        settingsDataStore.updateData { current ->
            val updated = current.restoreExpiredTemporaryDisables(System.currentTimeMillis())
            changed = updated != current
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        if (changed || rescheduleAlways) {
            neth.iecal.curbox.services.TemporaryGroupDisableJob.schedule(context, nextDeadline)
        }
    }

    private fun temporaryDisableDeadline(durationMinutes: Long): Long =
        if (durationMinutes == TemporaryDisableDialog.UNTIL_MANUALLY_ENABLED) {
            TemporaryDisableDialog.UNTIL_MANUALLY_ENABLED
        } else {
            System.currentTimeMillis() + durationMinutes.coerceIn(1L, 43_200L) * 60_000L
        }

    private fun earliestTemporaryDisable(settings: Settings): Long? {
        val deadlines = buildList {
            addAll(settings.blockedAppGroups.map { it.temporarilyDisabledUntilMs })
            addAll(
                settings.keywordBlockerConfig.keywordGroups.map {
                    it.temporarilyDisabledUntilMs
                }
            )
            add(settings.reelBlockerConfig.temporarilyDisabledUntilMs)
        }
        val now = System.currentTimeMillis()
        return deadlines.filter { it > now }.minOrNull()
    }

    suspend fun cancelPendingSettingsChange(fieldName: String, expectedAppliesAtMs: Long? = null) {
        settingsDataStore.updateData {
            val config = it.settingsChangeDelayConfig2
            it.copy(settingsChangeDelayConfig2 = config.copy(
                pendingChanges = config.pendingChanges.filterNot { p ->
                    p.field == fieldName && (expectedAppliesAtMs == null || p.appliesAtMs == expectedAppliesAtMs)
                }
            ))
        }
        schedulePendingChanges()
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
            val delayConfig = current.settingsChangeDelayConfig2
            // Tamper protection being on holds every pending change back, no matter how long
            // its own timer has already run out, until the user turns tamper protection off
            val tamperBlocked = delayConfig.requireTamperProtectionOff && current.antiUninstallConfig2.isEnabled
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
                settings.restoreExpiredTemporaryDisables(now).copy(settingsChangeDelayConfig2 = settings.settingsChangeDelayConfig2.copy(
                    pendingChanges = waiting
                ))
            }
        }
        schedulePendingChanges()
        return appliedAny
    }

    /**
     * The settings change delay gate. Changes that keep every restriction at least as strong
     * apply right away; anything else is parked as a [PendingSettingsChange] until the
     * countdown ends. A stricter instant write also drops the field's pending change, because
     * that pending snapshot no longer matches what the user sees.
     */
    private suspend fun updateGated(field: GatedSettingsField, computeNewValue: (Settings) -> Any): Unit = withContext(Dispatchers.Default) {
        var deferredUntilMs = 0L
        var hadPendingForField = false
        var tamperGated = false
        var unchangedPending = false
        settingsDataStore.updateData { current ->
            deferredUntilMs = 0L
            hadPendingForField = false
            tamperGated = false
            unchangedPending = false
            val newValueJson = gson.toJson(computeNewValue(overlayPendingChanges(current)))
            val proposed = withFieldValue(current, field, newValueJson) ?: return@updateData current
            val delayConfig = current.settingsChangeDelayConfig2
            val existingPending = delayConfig.pendingChanges.find { it.field == field.name }
            hadPendingForField = existingPending != null
            if (existingPending?.newValueJson == newValueJson) {
                unchangedPending = true
                return@updateData current
            }
            val timeGateActive = delayConfig.isEnabled && delayConfig.delayMinutes > 0
            tamperGated = delayConfig.requireTamperProtectionOff && current.antiUninstallConfig2.isEnabled
            if ((!timeGateActive && !tamperGated) ||
                RestrictionComparator.isSameOrStricter(field, current, proposed)
            ) {
                tamperGated = false
                val proposedDelayConfig = proposed.settingsChangeDelayConfig2
                proposed.copy(settingsChangeDelayConfig2 = proposedDelayConfig.copy(
                    pendingChanges = proposedDelayConfig.pendingChanges.filterNot { it.field == field.name }
                ))
            } else {
                val now = System.currentTimeMillis()
                // With no active timer, the pending value is due the instant tamper protection
                // turns off; applyDuePendingChanges re-checks that condition on every sweep.
                val delayMinutes = delayConfig.delayMinutes.coerceIn(0, SettingsChangeDelayConfig.MAX_DELAY_MINUTES)
                deferredUntilMs = if (timeGateActive) now + delayMinutes * 60_000L else now
                val pending = PendingSettingsChange(
                    field = field.name,
                    newValueJson = newValueJson,
                    requestedAtMs = now,
                    appliesAtMs = deferredUntilMs
                )
                current.copy(settingsChangeDelayConfig2 = delayConfig.copy(
                    pendingChanges = delayConfig.pendingChanges.filterNot { it.field == field.name } + pending
                ))
            }
        }
        schedulePendingChanges()
        if (unchangedPending) {
            return@withContext
        } else if (deferredUntilMs > 0L) {
            notifyChangeDeferred(field, deferredUntilMs, hadPendingForField, tamperGated)
        } else if (hadPendingForField) {
            notifyPendingChangeDropped(field)
        }
    }

    private fun applyPendingValue(settings: Settings, change: PendingSettingsChange): Settings? {
        val field = runCatching { GatedSettingsField.valueOf(change.field) }.getOrNull() ?: return null
        return withFieldValue(settings, field, change.newValueJson)
    }

    private fun overlayPendingChanges(settings: Settings): Settings {
        var displayed = settings
        settings.settingsChangeDelayConfig2.pendingChanges.forEach { change ->
            applyPendingValue(displayed, change)?.let { displayed = it }
        }
        return displayed
    }

    private suspend fun schedulePendingChanges() {
        val current = settingsDataStore.data.first()
        neth.iecal.curbox.services.PendingSettingsChangeJob.schedule(
            context.applicationContext,
            current.settingsChangeDelayConfig2,
            current.antiUninstallConfig2.isEnabled
        )
    }

    private fun withFieldValue(settings: Settings, field: GatedSettingsField, valueJson: String): Settings? {
        return runCatching {
            when (field) {
                GatedSettingsField.APP_GROUPS -> settings.copy(
                    blockedAppGroups = gson.fromJson<List<AppGroup>>(
                        valueJson,
                        object : TypeToken<List<AppGroup>>() {}.type
                    ).upgradeLegacyAppGroupConfigs(gson)
                )
                GatedSettingsField.AUTO_DND_GROUPS -> settings.copy(
                    autoDndGroups = gson.fromJson(valueJson, object : TypeToken<List<neth.iecal.curbox.data.models.AutoDndGroup>>() {}.type)
                )
                GatedSettingsField.REEL_BLOCKER -> settings.copy(
                    reelBlockerConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.ReelBlocker::class.java)
                        .upgradeLegacyConfig(gson)
                )
                GatedSettingsField.KEYWORD_BLOCKER -> settings.copy(
                    keywordBlockerConfig = gson.fromJson(
                        valueJson,
                        neth.iecal.curbox.data.models.KeywordBlocker::class.java
                    ).upgradeLegacyKeywordGroupConfigs(gson)
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
                    settings.copy(settingsChangeDelayConfig2 = settings.settingsChangeDelayConfig2.copy(
                        isEnabled = prefs.isEnabled,
                        delayMinutes = prefs.delayMinutes.coerceIn(0, SettingsChangeDelayConfig.MAX_DELAY_MINUTES),
                        requireTamperProtectionOff = prefs.requireTamperProtectionOff
                    ))
                }
            }
        }.getOrNull()
    }

    private fun syncedFieldValueJson(settings: Settings, field: GatedSettingsField): String? =
        runCatching {
            val value: Any = when (field) {
                GatedSettingsField.APP_GROUPS -> settings.blockedAppGroups
                GatedSettingsField.AUTO_DND_GROUPS -> settings.autoDndGroups
                GatedSettingsField.REEL_BLOCKER -> settings.reelBlockerConfig
                GatedSettingsField.KEYWORD_BLOCKER -> settings.keywordBlockerConfig
                GatedSettingsField.REEL_COUNTER -> settings.isReelCounterOn
                GatedSettingsField.GRAYSCALE_GROUPS -> settings.grayscaleGroups
                GatedSettingsField.MINDFUL_MESSAGES -> settings.mindfulMessageConfig
                GatedSettingsField.UI_HIDER -> settings.uiHiderConfig
                GatedSettingsField.APP_USAGE_TRACKING -> settings.isAppUsageTrackingEnabled
                GatedSettingsField.WEBSITE_USAGE_TRACKING -> settings.isWebsiteUsageTrackingEnabled
                GatedSettingsField.CHANGE_DELAY -> SettingsChangeDelayPrefs(
                    isEnabled = settings.settingsChangeDelayConfig2.isEnabled,
                    delayMinutes = settings.settingsChangeDelayConfig2.delayMinutes.coerceIn(
                        0,
                        SettingsChangeDelayConfig.MAX_DELAY_MINUTES,
                    ),
                    requireTamperProtectionOff =
                        settings.settingsChangeDelayConfig2.requireTamperProtectionOff,
                )
            }
            gson.toJson(value)
        }.getOrNull()

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
