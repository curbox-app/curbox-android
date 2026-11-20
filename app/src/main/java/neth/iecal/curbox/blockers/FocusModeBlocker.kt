package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.PomodoroPhase
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.FocusModeSoundPlayer
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.getDefaultLauncherPackageName

class FocusModeBlocker : BaseBlocker() {

    private data class ManualFocusModeData(
        val focusGroupData: ManualFocusGroup,
        val endTimeInMillis: Long
    )

    companion object {
        private const val TIMER_NOTIFICATION_ID = 1002
        const val INTENT_ACTION_REFRESH_FOCUS_MODE = "neth.iecal.curbox.refresh.focus_mode"
        const val INTENT_ACTION_UNSUSPEND_ALL = "neth.iecal.curbox.unsuspend_all_apps"
    }

    @Volatile private var focusModeData: ManualFocusModeData? = null
    private var lastPackage = ""
    private var lastBlockTime = 0L
    private var lastWebsiteCheckTime = 0L
    private lateinit var service: AppBlockerService
    private lateinit var notificationManager: TimerNotification
    private lateinit var soundPlayer: FocusModeSoundPlayer
    private lateinit var crashLogger: CrashLogger
    private val keywordBlocker = KeywordBlocker()
    private var focusKeywordsPatterns = Pair(emptyList<Regex>(), emptyList<String>())
    private val blockerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsMutex = Mutex()

    @Volatile private var essentialPackages: Set<String> = emptySet()

    @Volatile private var currentlySuspendedPackages = setOf<String>()

    @Volatile private var isDndRequested = false

    private var settingsJob: Job? = null
    private var phaseTransitionJob: Job? = null

    @Synchronized
    private fun updateSuspendedPackages(serviceContext: Context) {
        val newSuspendedPackages = mutableSetOf<String>()
        var shouldDndBeOn = false
        focusModeData?.focusGroupData?.let { group ->
            if (group.autoTurnOnDnd) shouldDndBeOn = true
            newSuspendedPackages.addAll(
                AppSuspendHelper.getPackagesToSuspend(serviceContext, group.blockMode, group.packages, essentialPackages)
            )
        }

        val toSuspend = newSuspendedPackages - currentlySuspendedPackages
        val toUnsuspend = currentlySuspendedPackages - newSuspendedPackages

        if (toSuspend.isNotEmpty()) {
            AppSuspendHelper.suspendApps(toSuspend.toList())
        }
        if (toUnsuspend.isNotEmpty()) {
            AppSuspendHelper.unsuspendApps(toUnsuspend.toList())
        }

        currentlySuspendedPackages = newSuspendedPackages
        
        if (isDndRequested != shouldDndBeOn) {
            isDndRequested = shouldDndBeOn
            service.syncDndState()
        }
    }

    fun isDndRequested(): Boolean = isDndRequested

    private fun turnOffFocusMode(groupId: String, expectedEndTimeInMillis: Long) {
        focusModeData = null
        blockerScope.launchSafely {
            val settings = service.dataStoreManager.settings.first()
            if (settings.activeManualFocusGroupId != Pair(groupId, expectedEndTimeInMillis)) {
                return@launchSafely
            }
            completeRunningSessions(groupId)
            service.dataStoreManager.setManualFocusStateToInactive()
            soundPlayer.play(FocusModeSoundPlayer.Effect.FOCUS_COMPLETE)
        }
        notificationManager.stopTimer()
        updateSuspendedPackages(service)
    }

    private fun requestPhaseTransition(expectedEndTimeInMillis: Long) {
        if (phaseTransitionJob?.isActive == true) return
        phaseTransitionJob = blockerScope.launchSafely {
            val settings = service.dataStoreManager.settings.first()
            val (groupId, endTimeInMillis) = settings.activeManualFocusGroupId
            if (groupId == null || endTimeInMillis != expectedEndTimeInMillis) {
                return@launchSafely
            }

            if (settings.activePomodoroState.isActive) {
                val finishedPhase = settings.activePomodoroState.phase
                val updated = service.dataStoreManager
                    .advancePomodoroState(expectedEndTimeInMillis)
                    ?: return@launchSafely
                if (finishedPhase == PomodoroPhase.FOCUS) {
                    focusModeData = null
                    updateSuspendedPackages(service)
                    soundPlayer.play(
                        if (updated.activePomodoroState.isActive) {
                            FocusModeSoundPlayer.Effect.BREAK_STARTED
                        } else {
                            FocusModeSoundPlayer.Effect.POMODORO_COMPLETE
                        }
                    )
                    completeRunningSessions(groupId)
                } else {
                    soundPlayer.play(FocusModeSoundPlayer.Effect.FOCUS_RESUMED)
                }
            } else {
                turnOffFocusMode(groupId, expectedEndTimeInMillis)
            }
        }
    }

    private suspend fun completeRunningSessions(groupId: String) = statsMutex.withLock {
        val statsDao = AppDatabase.getInstance(service).focusStatsDao()
        val runningSessions = statsDao.getRunningSessions().filter { it.groupId == groupId }
        for (session in runningSessions) {
            statsDao.update(
                session.copy(
                    status = 1,
                    actualEndTimeInMillis = session.estimatedEndTimeInMillis
                )
            )
        }
    }

    private suspend fun reconcileFocusStats(settings: Settings) = statsMutex.withLock {
        val pomodoro = settings.activePomodoroState
        val groupId = settings.activeManualFocusGroupId.first ?: return@withLock
        val endTimeInMillis = settings.activeManualFocusGroupId.second
        val statsDao = AppDatabase.getInstance(service).focusStatsDao()
        val runningForGroup = statsDao.getRunningSessions().filter { it.groupId == groupId }

        if (pomodoro.isActive && pomodoro.phase == PomodoroPhase.BREAK) {
            for (session in runningForGroup) {
                statsDao.update(
                    session.copy(
                        status = 1,
                        actualEndTimeInMillis = session.estimatedEndTimeInMillis
                    )
                )
            }
            return@withLock
        }

        if (runningForGroup.any { it.estimatedEndTimeInMillis == endTimeInMillis }) {
            return@withLock
        }
        val now = System.currentTimeMillis()
        // A session or Pomodoro round that has not ended yet was given more time, so it keeps going.
        val extended = runningForGroup.firstOrNull {
            it.estimatedEndTimeInMillis > now && it.estimatedEndTimeInMillis < endTimeInMillis
        }
        if (extended != null) {
            statsDao.update(extended.copy(estimatedEndTimeInMillis = endTimeInMillis))
            return@withLock
        }
        if (!pomodoro.isActive) return@withLock
        for (session in runningForGroup) {
            statsDao.update(session.stoppedAt(now))
        }
        statsDao.insert(
            FocusStatsEntity(
                groupId = groupId,
                startTimeInMillis = endTimeInMillis - pomodoro.currentPhaseDurationMs(),
                estimatedEndTimeInMillis = endTimeInMillis,
                actualEndTimeInMillis = 0L,
                status = 0,
                pomodoroCycleId = pomodoro.cycleId
            )
        )
    }

    private fun CoroutineScope.launchSafely(block: suspend () -> Unit): Job = launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("FocusMode", "Focus mode worker failed", t)
            crashLogger.logNonFatalError(Exception(t))
        }
    }

    fun doFocusModeCheck(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == service.packageName) return
        if (!service.isDelayOver(1000)) return

        val currentFocusModeData = focusModeData ?: return
        if (currentFocusModeData.endTimeInMillis <= System.currentTimeMillis()) {
            requestPhaseTransition(currentFocusModeData.endTimeInMillis)
            return
        }

        if (lastPackage != packageName) {
            lastPackage = packageName
            when (currentFocusModeData.focusGroupData.blockMode) {
                FocusBlockMode.BLOCK_SELECTED -> {
                    if (currentFocusModeData.focusGroupData.packages.contains(packageName)) {
                        service.pressHome()

                        Log.d("focus mode","home pressed $packageName")
                        return
                    }
                }
                FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED -> {
                    if (!currentFocusModeData.focusGroupData.packages.contains(packageName)) {
                        service.pressHome()
                        Log.d("focus mode","home pressed $packageName")

                        return
                    }
                }
            }
        }

        if (currentFocusModeData.focusGroupData.keywords.isNotEmpty() &&
            URL_BAR_ID_LIST.containsKey(packageName)) {

            val now = System.currentTimeMillis()
            // Throttle website checks to every 400ms within the same app to preserve performance
            if (now - lastWebsiteCheckTime > 400) {
                lastWebsiteCheckTime = now
                if (keywordBlocker.isFocusWebsiteBlocked(packageName, focusKeywordsPatterns, currentFocusModeData.focusGroupData.blockMode)) {
                    if (now - lastBlockTime > 1500) {
                        service.pressBack()
                        Log.d("focus mode","back pressed")
                        lastBlockTime = now
                    }
                }
            }
        }
    }

    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_FOCUS_MODE)
            addAction(INTENT_ACTION_UNSUSPEND_ALL)
        }
        ContextCompat.registerReceiver(
            service, refreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun removeReceivers() {
        service.unregisterReceiver(refreshReceiver)
    }

    fun setupFocusMode(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        crashLogger = CrashLogger(service)
        keywordBlocker.setupBlocker(service, watchSettings = false)
        if (!this::notificationManager.isInitialized) {
            notificationManager = TimerNotification(service, TIMER_NOTIFICATION_ID)
        }
        if (!this::soundPlayer.isInitialized) {
            soundPlayer = FocusModeSoundPlayer(service)
        }

        // cache essential packages
        val essential = mutableSetOf("com.android.systemui")
        getDefaultLauncherPackageName(service.packageManager)?.let { essential.add(it) }
        getCurrentKeyboardPackageName(service)?.let { essential.add(it) }
        essentialPackages = essential

        Log.d("essential package", essentialPackages.toString())
        blockerScope.launchSafely {
            val db = AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            val runningSessions = statsDao.getRunningSessions()
            for (session in runningSessions) {
                if (session.estimatedEndTimeInMillis < System.currentTimeMillis()) {
                     statsDao.update(session.copy(status = 1, actualEndTimeInMillis = session.estimatedEndTimeInMillis))
                }
            }
        }

        settingsJob?.cancel()
        settingsJob = blockerScope.launchSafely {
            service.dataStoreManager.settings.collectLatest { settings ->
                applySettings(settings)
            }
        }
    }

    /**
     * Applies settings to in-memory state and updates suspended packages.
     * Must be called from a coroutine context.
     */
    private suspend fun applySettings(settings: Settings) {
        val (groupId, endTimeInMillis) = settings.activeManualFocusGroupId
        val currentFocusingGroup = settings.manualFocusGroups.find { it.groupId == groupId }
        if (groupId != null && currentFocusingGroup != null) {
            val remainingTimeInMillis = endTimeInMillis - System.currentTimeMillis()
            if (remainingTimeInMillis > 0) {
                val pomodoro = settings.activePomodoroState
                val isBreak = pomodoro.isActive && pomodoro.phase == PomodoroPhase.BREAK
                // Fix: copy the packages set instead of mutating the original data object
                val effectiveGroup = if (currentFocusingGroup.blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) {
                    val packagesCopy = HashSet(currentFocusingGroup.packages)
                    packagesCopy.addAll(essentialPackages)
                    currentFocusingGroup.copy(packages = packagesCopy)
                } else {
                    currentFocusingGroup
                }
                focusModeData = if (isBreak) {
                    null
                } else {
                    ManualFocusModeData(effectiveGroup, endTimeInMillis)
                }
                if (!isBreak) {
                    focusKeywordsPatterns = keywordBlocker.compileKeywords(effectiveGroup.keywords)
                }

                try {
                    reconcileFocusStats(settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("FocusMode", "Failed to reconcile focus stats", t)
                    crashLogger.logNonFatalError(Exception(t))
                }

                withContext(Dispatchers.Main) {
                    notificationManager.startTimer(
                        totalMillis = (endTimeInMillis - System.currentTimeMillis())
                            .coerceAtLeast(1L),
                        timerId = "focus_mode_${pomodoro.phase}_$endTimeInMillis",
                        title = service.getString(
                            if (isBreak) {
                                R.string.notification_title_pomodoro_break
                            } else {
                                R.string.notification_title_focus_mode_on
                            }
                        ),
                        onFinishCallback = {
                            requestPhaseTransition(endTimeInMillis)
                        }
                    )
                }
            } else {
                focusModeData = null
                withContext(Dispatchers.Main) {
                    notificationManager.stopTimer()
                }
                requestPhaseTransition(endTimeInMillis)
            }
        } else {
            focusModeData = null
            withContext(Dispatchers.Main) {
                notificationManager.stopTimer()
            }
        }

        updateSuspendedPackages(service)
    }

    fun onDestroy() {
        settingsJob?.cancel()
        phaseTransitionJob?.cancel()
        if (this::notificationManager.isInitialized) {
            notificationManager.release()
        }
        if (this::soundPlayer.isInitialized) {
            soundPlayer.release()
        }
        blockerScope.cancel()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_FOCUS_MODE -> {
                    blockerScope.launchSafely {
                        val settings = service.dataStoreManager.settings.first()
                        applySettings(settings)
                    }
                }
                INTENT_ACTION_UNSUSPEND_ALL -> {
                    AppSuspendHelper.unsuspendAllApps(context ?: service)
                }
            }
        }
    }
}
