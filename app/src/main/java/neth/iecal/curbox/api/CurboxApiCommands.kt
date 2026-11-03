package neth.iecal.curbox.api

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.hardcoded.allScripts
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.DndHelper
import neth.iecal.curbox.utils.TimeTools
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

/**
 * Turns an API command into a real change in Curbox.
 *
 * Everything here mirrors what the in app screens already do: write the new state to DataStore
 * and send the matching refresh broadcast so the running services pick it up immediately. Going
 * through DataStore keeps API driven changes and in app changes perfectly consistent.
 */
object CurboxApiCommands {

    private const val TAG = "CurboxApi"

    /** Runs an action command. Returns true if it was understood and applied. */
    suspend fun runCommand(context: Context, commandName: String?, args: Bundle, callerPackage: String): Boolean {
        val command = ApiCommand.fromNameOrNull(commandName) ?: return false
        val target = args.getString(CurboxApiContract.ARG_TARGET).orEmpty()
        val enable = args.getBoolean(CurboxApiContract.ARG_ENABLE, true)
        val minutes = args.getInt(CurboxApiContract.ARG_MINUTES, 25)
        val endsAt = args.getLong(CurboxApiContract.ARG_ENDS_AT, 0L)

        val app = context.applicationContext
        val dataStore = DataStoreManager(app)

        return try {
            when (command) {
                ApiCommand.REGISTER_UNLOCK_CONDITION -> ExternalUnlockProviders.register(app, callerPackage, args)
                ApiCommand.REMOVE_UNLOCK_CONDITION -> dataStore.removeExternalUnlockCondition(
                    callerPackage, args.getString("id").orEmpty()
                )
                ApiCommand.START_FOCUS -> startFocus(app, dataStore, target, minutes, endsAt)
                ApiCommand.STOP_FOCUS -> stopFocus(app, dataStore)
                ApiCommand.SET_APP_BLOCKER_GROUP -> setAppBlockerGroup(app, dataStore, target, enable)
                ApiCommand.SET_KEYWORD_BLOCKER -> setKeywordBlocker(app, dataStore, enable)
                ApiCommand.SET_KEYWORD_GROUP -> setKeywordGroup(app, dataStore, target, enable)
                ApiCommand.SET_REEL_BLOCKER -> setReelBlocker(app, dataStore, enable)
                ApiCommand.SET_UI_HIDER -> {
                    if (!BuildConfig.SUPPORTS_UI_HIDER) return false
                    setUiHider(app, dataStore, enable)
                }
                ApiCommand.SET_GRAYSCALE_GROUP -> setGrayscaleGroup(app, dataStore, target, enable)
                ApiCommand.SET_REEL_COUNTER -> setReelCounter(app, dataStore, enable)
                ApiCommand.SET_DND -> DndHelper.applyDndState(app, enable)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run command $command", e)
            false
        }
    }

    private fun broadcast(context: Context, action: String) {
        context.sendBroadcast(Intent(action).setPackage(context.packageName))
    }

    private const val MAX_FOCUS_MS = 24 * 60 * 60_000L

    private suspend fun startFocus(context: Context, dataStore: DataStoreManager, groupId: String, minutes: Int, endsAt: Long) {
        val settings = dataStore.settings.first()
        require(ApiRestrictionPolicy.canReplaceFocus(settings, System.currentTimeMillis())) {
            "The active focus session cannot be ended early"
        }
        val group = requireNotNull(settings.manualFocusGroups.firstOrNull { it.groupId == groupId }) {
            "Unknown focus group: $groupId"
        }
        val now = System.currentTimeMillis()
        // An exact end lets a companion line this session up with another device to the millisecond.
        val durationMs = if (endsAt > now) {
            (endsAt - now).coerceIn(60_000L, MAX_FOCUS_MS)
        } else {
            minutes.coerceAtLeast(1) * 60_000L
        }
        val endTimeInMillis = now + durationMs

        dataStore.setManualFocusStateToActive(
            group.groupId,
            durationMs,
            endTimeInMillis = endTimeInMillis,
            requireExitableSession = true
        )
        val statsDao = AppDatabase.getInstance(context).focusStatsDao()
        statsDao.insert(
            FocusStatsEntity(
                groupId = group.groupId,
                startTimeInMillis = now,
                estimatedEndTimeInMillis = endTimeInMillis,
                actualEndTimeInMillis = 0L,
                status = 0
            )
        )
        broadcast(context, FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
    }

    private suspend fun stopFocus(context: Context, dataStore: DataStoreManager) {
        require(ApiRestrictionPolicy.canReplaceFocus(dataStore.settings.first(), System.currentTimeMillis())) {
            "The active focus session cannot be ended early"
        }
        val statsDao = AppDatabase.getInstance(context).focusStatsDao()
        val now = System.currentTimeMillis()
        val sessions = statsDao.getRunningSessions()
        dataStore.setManualFocusStateToInactive(requireExitableSession = true)
        for (session in sessions) {
            val actEnd = if (session.estimatedEndTimeInMillis < now) session.estimatedEndTimeInMillis else now
            statsDao.update(session.copy(status = 1, actualEndTimeInMillis = actEnd))
        }
        broadcast(context, FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
    }

    private suspend fun setAppBlockerGroup(context: Context, dataStore: DataStoreManager, groupId: String, enable: Boolean) {
        val settings = dataStore.settings.first()
        require(settings.blockedAppGroups.any { it.id == groupId }) {
            "Unknown app blocker group: $groupId"
        }
        val updated = settings.blockedAppGroups.map {
            if (it.id == groupId) it.copy(isActive = enable) else it
        }
        dataStore.updateAppGroups(updated)
        broadcast(context, AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER)
    }

    private suspend fun setKeywordBlocker(context: Context, dataStore: DataStoreManager, enable: Boolean) {
        dataStore.updateKeywordBlockerConfig { it.copy(isActive = enable) }
        broadcast(context, KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
    }

    private suspend fun setKeywordGroup(context: Context, dataStore: DataStoreManager, groupId: String, enable: Boolean) {
        val settings = dataStore.settings.first()
        require(settings.keywordBlockerConfig.keywordGroups.any { it.id == groupId }) {
            "Unknown keyword group: $groupId"
        }
        dataStore.updateKeywordBlockerConfig { config ->
            config.copy(keywordGroups = config.keywordGroups.map {
                if (it.id == groupId) it.copy(isActive = enable) else it
            })
        }
        broadcast(context, KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
    }

    private suspend fun setReelBlocker(context: Context, dataStore: DataStoreManager, enable: Boolean) {
        val settings = dataStore.settings.first()
        dataStore.updateReelBlockerConfig(settings.reelBlockerConfig.copy(isActive = enable))
        broadcast(context, ReelBlocker.INTENT_ACTION_REFRESH_REEL_BLOCKER)
    }

    private suspend fun setUiHider(context: Context, dataStore: DataStoreManager, enable: Boolean) {
        dataStore.updateUiHiderConfig { it.copy(isActive = enable) }
        broadcast(context, UiHider.INTENT_ACTION_REFRESH_UI_HIDER)
    }

    private suspend fun setGrayscaleGroup(context: Context, dataStore: DataStoreManager, groupId: String, enable: Boolean) {
        val settings = dataStore.settings.first()
        require(settings.grayscaleGroups.any { it.groupId == groupId }) {
            "Unknown grayscale group: $groupId"
        }
        val updated = settings.grayscaleGroups.map {
            if (it.groupId == groupId) it.copy(isActive = enable) else it
        }
        dataStore.updateGrayscaleGroups(updated)
        broadcast(context, GrayScaleFilter.INTENT_ACTION_REFRESH_GRAYSCALE)
    }

    private suspend fun setReelCounter(context: Context, dataStore: DataStoreManager, enable: Boolean) {
        dataStore.updateReelCounterState(enable)
        broadcast(context, ReelsCountTracker.INTENT_ACTION_REFRESH_REEL_COUNTER)
    }

    /** Reads a state for a query. Returns the values the client can read, or null if unknown. */
    suspend fun queryState(context: Context, stateName: String?): Map<String, String>? {
        val state = ApiState.fromNameOrNull(stateName) ?: return null

        val app = context.applicationContext
        val dataStore = DataStoreManager(app)

        return when (state) {
            ApiState.FOCUS_ACTIVE -> {
                val settings = dataStore.settings.first()
                val (groupId, endTime) = settings.activeManualFocusGroupId
                val active = groupId != null && endTime > System.currentTimeMillis()
                val name = if (active) settings.manualFocusGroups.firstOrNull { it.groupId == groupId }?.groupName ?: "" else ""
                val remainingMin = if (active) (endTime - System.currentTimeMillis()) / 60_000L else 0L
                mapOf(
                    "active" to active.toString(),
                    CurboxApiContract.VAR_FOCUS_GROUP to name,
                    CurboxApiContract.VAR_FOCUS_REMAINING to remainingMin.toString(),
                    CurboxApiContract.VAR_FOCUS_GROUP_ID to (if (active) groupId.orEmpty() else ""),
                    CurboxApiContract.VAR_FOCUS_ENDS_AT to (if (active) endTime else 0L).toString()
                )
            }

            ApiState.SCREENTIME_TODAY -> {
                val settings = dataStore.settings.first()
                val ignored = settings.usageTrackerIgnoredApps.toSet()
                val today = TimeTools.getCurrentDate()
                val totalMs = AppDatabase.getInstance(app).appUsageDao().getForDate(today)
                    .filter { it.packageName !in ignored }
                    .sumOf { it.totalTime }
                val minutes = (totalMs / 60_000L).toString()
                mapOf(CurboxApiContract.VAR_SCREENTIME to minutes)
            }

            ApiState.REELS_TODAY -> {
                val today = TimeTools.getCurrentDate()
                val count = (AppDatabase.getInstance(app).reelStatsDao().getCount(today) ?: 0).toString()
                mapOf(CurboxApiContract.VAR_REELS to count)
            }
        }
    }

    /**
     * Lists the things a client can discover. Group kinds return the ids needed to target a group
     * with [runCommand]; STATUS returns a snapshot of every global toggle and the active focus.
     * Returns a List (or a Map for STATUS) ready to be serialized, or null if the kind is unknown.
     */
    suspend fun list(context: Context, kindName: String?): Any? {
        val kind = ApiList.fromNameOrNull(kindName) ?: return null
        val app = context.applicationContext
        val settings = DataStoreManager(app).settings.first()

        return when (kind) {
            ApiList.UNLOCK_CONDITIONS -> settings.externalUnlockConditions.filter {
                ExternalUnlockProviders.isAvailable(app, it)
            }

            ApiList.FOCUS_GROUPS -> settings.manualFocusGroups.map { g ->
                linkedMapOf(
                    "id" to g.groupId,
                    "name" to g.groupName,
                    "apps" to g.packages.size,
                    "websites" to g.keywords.size,
                    "blockMode" to g.blockMode.name,
                    "exitable" to g.exitable,
                    "autoTurnOnDnd" to g.autoTurnOnDnd
                )
            }

            ApiList.APP_BLOCKER_GROUPS -> settings.blockedAppGroups.map { g ->
                linkedMapOf(
                    "id" to g.id,
                    "name" to g.name,
                    "isActive" to g.isActive,
                    "apps" to g.selectedPackages.size
                )
            }

            ApiList.KEYWORD_GROUPS -> settings.keywordBlockerConfig.keywordGroups.map { g ->
                linkedMapOf(
                    "id" to g.id,
                    "name" to g.name,
                    "isActive" to g.isActive,
                    "keywords" to g.selectedKeywords.size
                )
            }

            ApiList.GRAYSCALE_GROUPS -> settings.grayscaleGroups.map { g ->
                linkedMapOf(
                    "id" to g.groupId,
                    "name" to g.groupName,
                    "isActive" to g.isActive,
                    "apps" to g.packages.size
                )
            }

            ApiList.AUTO_DND_GROUPS -> settings.autoDndGroups.map { g ->
                linkedMapOf(
                    "id" to g.groupId,
                    "name" to g.groupName,
                    "autoTurnOnDnd" to g.autoTurnOnDnd
                )
            }

            ApiList.UI_HIDER_SCRIPTS -> if (!BuildConfig.SUPPORTS_UI_HIDER) emptyList() else settings.uiHiderConfig.allScripts().map { s ->
                linkedMapOf(
                    "id" to s.id,
                    "label" to s.label,
                    "packageName" to s.packageName,
                    "isEnabled" to s.isEnabled
                )
            }

            ApiList.USAGE_HISTORY -> usageHistory(app, settings.usageTrackerIgnoredApps.toSet())

            ApiList.STATUS -> {
                val (groupId, endTime) = settings.activeManualFocusGroupId
                val focusActive = groupId != null && endTime > System.currentTimeMillis()
                val focusName = if (focusActive) {
                    settings.manualFocusGroups.firstOrNull { it.groupId == groupId }?.groupName ?: ""
                } else ""
                val remaining = if (focusActive) (endTime - System.currentTimeMillis()) / 60_000L else 0L
                linkedMapOf(
                    "focusActive" to focusActive,
                    "focusGroup" to focusName,
                    "focusRemainingMinutes" to remaining,
                    "focusGroupId" to (if (focusActive) groupId.orEmpty() else ""),
                    "focusEndsAt" to (if (focusActive) endTime else 0L),
                    "keywordBlocker" to settings.keywordBlockerConfig.isActive,
                    "reelBlocker" to settings.reelBlockerConfig.isActive,
                    "reelCounter" to settings.isReelCounterOn,
                    "uiHider" to (BuildConfig.SUPPORTS_UI_HIDER && settings.uiHiderConfig.isActive)
                )
            }
        }
    }

    private const val USAGE_DAYS = 28
    private const val USAGE_TOP = 25

    /**
     * Daily totals, newest first, for days that have any usage. Each day keeps its top apps and
     * websites so the reply stays small enough for Binder.
     */
    private suspend fun usageHistory(context: Context, ignored: Set<String>): List<Map<String, Any>> {
        val db = AppDatabase.getInstance(context)
        val today = LocalDate.now()
        val days = (0 until USAGE_DAYS).map { today.minusDays(it.toLong()) }
        val keys = days.associateBy { TimeTools.dayKey(it) }
        val apps = db.appUsageDao().getForDates(keys.keys.toList())
            .filter { it.packageName !in ignored && it.totalTime > 0 }
            .groupBy { it.date }
        val sites = db.websiteStatsDao().getStatsForDates(keys.keys.toList())
            .filter { it.totalTime > 0 }
            .groupBy { it.date }
        val pm = context.packageManager
        val labels = HashMap<String, String>()
        fun label(pkg: String) = labels.getOrPut(pkg) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        return keys.mapNotNull { (key, date) ->
            val dayApps = apps[key].orEmpty()
            val daySites = sites[key].orEmpty()
            if (dayApps.isEmpty() && daySites.isEmpty()) return@mapNotNull null
            linkedMapOf(
                "date" to date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                "screenTimeMs" to dayApps.sumOf { it.totalTime },
                "apps" to dayApps.sortedByDescending { it.totalTime }.take(USAGE_TOP).map {
                    linkedMapOf(
                        "packageName" to it.packageName,
                        "label" to label(it.packageName),
                        "ms" to it.totalTime,
                        "launches" to it.launchCount
                    )
                },
                "websites" to daySites.groupBy { it.domain }
                    .map { (domain, rows) -> domain to rows.sumOf { it.totalTime } }
                    .sortedByDescending { it.second }
                    .take(USAGE_TOP)
                    .map { (domain, ms) -> linkedMapOf("domain" to domain, "ms" to ms) }
            )
        }
    }
}
