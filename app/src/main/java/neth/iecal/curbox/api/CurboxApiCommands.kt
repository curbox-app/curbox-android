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
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.PomodoroPhase
import neth.iecal.curbox.data.models.PomodoroState
import neth.iecal.curbox.data.models.Settings
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
                ApiCommand.START_FOCUS -> startFocus(app, dataStore, target, minutes, endsAt, pomodoroFrom(args, minutes))
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
                ApiCommand.HOST_COMPANION -> CompanionHost.request(app, callerPackage, enable)
                ApiCommand.SAVE_FOCUS_GROUP -> saveFocusGroup(app, dataStore, target, args)
                ApiCommand.DELETE_FOCUS_GROUP -> deleteFocusGroup(app, dataStore, target)
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

    /** Version 7: a positive round count makes START_FOCUS run (or join) a Pomodoro cycle. */
    private fun pomodoroFrom(args: Bundle, minutes: Int): PomodoroState {
        val rounds = args.getInt(CurboxApiContract.ARG_POMODORO_ROUNDS, 0)
        if (rounds <= 0) return PomodoroState()
        val phase = if (args.getString(CurboxApiContract.ARG_POMODORO_PHASE).equals("BREAK", ignoreCase = true)) {
            PomodoroPhase.BREAK
        } else PomodoroPhase.FOCUS
        val state = PomodoroState(
            isActive = true,
            phase = phase,
            focusMinutes = args.getInt(CurboxApiContract.ARG_POMODORO_FOCUS_MINUTES, minutes),
            breakMinutes = args.getInt(CurboxApiContract.ARG_POMODORO_BREAK_MINUTES, PomodoroState.DEFAULT_BREAK_MINUTES),
            totalFocusIntervals = rounds,
            completedFocusIntervals = args.getInt(CurboxApiContract.ARG_POMODORO_COMPLETED, 0)
        ).normalized()
        // A break follows at least one round, and the last round is never followed by a break.
        val maxCompleted = state.totalFocusIntervals - 1
        val minCompleted = if (phase == PomodoroPhase.BREAK) 1 else 0
        return state.copy(completedFocusIntervals = state.completedFocusIntervals.coerceIn(minCompleted, maxCompleted))
    }

    private suspend fun startFocus(
        context: Context,
        dataStore: DataStoreManager,
        groupId: String,
        minutes: Int,
        endsAt: Long,
        pomodoro: PomodoroState
    ) {
        val settings = dataStore.settings.first()
        require(ApiRestrictionPolicy.canReplaceFocus(settings, System.currentTimeMillis())) {
            "The active focus session cannot be ended early"
        }
        val group = requireNotNull(settings.manualFocusGroups.firstOrNull { it.groupId == groupId }) {
            "Unknown focus group: $groupId"
        }
        val now = System.currentTimeMillis()
        // An exact end lets a companion line this session up with another device to the millisecond.
        val durationMs = when {
            endsAt > now -> (endsAt - now).coerceIn(60_000L, MAX_FOCUS_MS)
            pomodoro.isActive -> pomodoro.currentPhaseDurationMs()
            else -> minutes.coerceAtLeast(1) * 60_000L
        }
        val endTimeInMillis = now + durationMs

        dataStore.setManualFocusStateToActive(
            group.groupId,
            durationMs,
            pomodoroState = pomodoro,
            endTimeInMillis = endTimeInMillis,
            requireExitableSession = true
        )
        val statsDao = AppDatabase.getInstance(context).focusStatsDao()
        // Joining a cycle during its break: breaks are rest and never count as focus time.
        if (!(pomodoro.isActive && pomodoro.phase == PomodoroPhase.BREAK)) statsDao.insert(
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

    private const val MAX_WEBSITES = 1_000
    private const val MAX_WEBSITE_LENGTH = 500

    /**
     * Version 7: creates the group [groupId] or updates the fields given. Apps change only when
     * given, and Do Not Disturb never does, since a companion may not know them. A group running in a session that
     * cannot be ended early is left alone, so editing it cannot loosen that session.
     */
    private suspend fun saveFocusGroup(context: Context, dataStore: DataStoreManager, groupId: String, args: Bundle) {
        val id = groupId.trim()
        require(id.isNotEmpty() && id.length <= 200) { "A focus group id is required" }
        val name = args.getString(CurboxApiContract.ARG_NAME)?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
        val websites = args.getStringArrayList(CurboxApiContract.ARG_WEBSITES)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.length <= MAX_WEBSITE_LENGTH }
            ?.distinct()
            ?.take(MAX_WEBSITES)
            ?.let { HashSet(it) }
        val apps = args.getStringArrayList(CurboxApiContract.ARG_APPS)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.length <= MAX_WEBSITE_LENGTH }
            ?.distinct()
            ?.take(MAX_WEBSITES)
            ?.let { HashSet(it) }
        val mode = args.getString(CurboxApiContract.ARG_BLOCK_MODE)?.let { raw ->
            requireNotNull(FocusBlockMode.entries.firstOrNull { it.name == raw }) { "Unknown block mode: $raw" }
        }
        val exitable = if (args.containsKey(CurboxApiContract.ARG_EXITABLE)) {
            args.getBoolean(CurboxApiContract.ARG_EXITABLE)
        } else null
        dataStore.editManualFocusGroups { settings ->
            val existing = settings.manualFocusGroups.firstOrNull { it.groupId == id }
            if (existing == null) {
                requireNotNull(name) { "A new focus group needs a name" }
                settings.manualFocusGroups + ManualFocusGroup(
                    groupId = id,
                    groupName = name,
                    keywords = websites ?: hashSetOf(),
                    packages = apps ?: hashSetOf(),
                    blockMode = mode ?: FocusBlockMode.BLOCK_SELECTED,
                    exitable = exitable ?: true
                )
            } else {
                require(!inLockedSession(settings, id)) { "The focus group is in a session that cannot be ended early" }
                val updated = existing.copy(
                    groupName = name ?: existing.groupName,
                    keywords = websites ?: existing.keywords,
                    packages = apps ?: existing.packages,
                    blockMode = mode ?: existing.blockMode,
                    exitable = exitable ?: existing.exitable
                )
                settings.manualFocusGroups.map { if (it.groupId == id) updated else it }
            }
        }
        broadcast(context, FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
    }

    /** Version 7: removes a focus group unless a session is using it. */
    private suspend fun deleteFocusGroup(context: Context, dataStore: DataStoreManager, groupId: String) {
        dataStore.editManualFocusGroups { settings ->
            require(settings.manualFocusGroups.any { it.groupId == groupId }) { "Unknown focus group: $groupId" }
            val (activeId, endTime) = settings.activeManualFocusGroupId
            require(activeId != groupId || endTime <= System.currentTimeMillis()) { "The focus group is in use" }
            settings.manualFocusGroups.filterNot { it.groupId == groupId }
        }
        broadcast(context, FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
    }

    private fun inLockedSession(settings: Settings, groupId: String): Boolean {
        val (activeId, endTime) = settings.activeManualFocusGroupId
        return activeId == groupId && endTime > System.currentTimeMillis() &&
            settings.manualFocusGroups.firstOrNull { it.groupId == groupId }?.exitable == false
    }

    /** Version 7 Pomodoro fields for FOCUS_ACTIVE and STATUS. */
    private fun pomodoroVars(settings: Settings, active: Boolean): Map<String, String> {
        val p = settings.activePomodoroState
        val on = active && p.isActive
        return mapOf(
            CurboxApiContract.VAR_FOCUS_PHASE to (if (on) p.phase.name else ""),
            CurboxApiContract.VAR_POMODORO_FOCUS_MINUTES to (if (on) p.focusMinutes else 0).toString(),
            CurboxApiContract.VAR_POMODORO_BREAK_MINUTES to (if (on) p.breakMinutes else 0).toString(),
            CurboxApiContract.VAR_POMODORO_ROUNDS to (if (on) p.totalFocusIntervals else 0).toString(),
            CurboxApiContract.VAR_POMODORO_COMPLETED to (if (on) p.completedFocusIntervals else 0).toString()
        )
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
                ) + pomodoroVars(settings, active)
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

            ApiList.FOCUS_GROUP_DETAILS -> settings.manualFocusGroups.map { g ->
                linkedMapOf(
                    "id" to g.groupId,
                    "name" to g.groupName,
                    "apps" to g.packages.size,
                    "websites" to g.keywords.sorted(),
                    "packages" to g.packages.sorted(),
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
                    "focusPhase" to (if (focusActive && settings.activePomodoroState.isActive) settings.activePomodoroState.phase.name else ""),
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
