package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.getDefaultLauncherPackageName

class FocusModeBlocker : BaseBlocker() {

    private data class ManualFocusModeData(
        val focusGroupData: ManualFocusGroup,
        val endTimeInMillis: Long
    )

    companion object {
        const val INTENT_ACTION_REFRESH_FOCUS_MODE = "neth.iecal.curbox.refresh.focus_mode"
        const val INTENT_ACTION_UNSUSPEND_ALL = "neth.iecal.curbox.unsuspend_all_apps"
    }

    @Volatile private var focusModeData: ManualFocusModeData? = null
    private var lastPackage = ""
    private var lastBlockTime = 0L
    private var lastWebsiteCheckTime = 0L
    private lateinit var service: AppBlockerService
    private lateinit var notificationManager: TimerNotification
    private val keywordBlocker = KeywordBlocker()
    private var focusKeywordsPatterns = Pair(emptyList<Regex>(), emptyList<String>())

    @Volatile private var essentialPackages: Set<String> = emptySet()

    @Volatile private var currentlySuspendedPackages = setOf<String>()

    @Volatile private var isDndRequested = false

    // Tracks the active settings-watching coroutine so it can be cancelled on re-setup
    private var settingsJob: kotlinx.coroutines.Job? = null

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

    private fun turnOffFocusMode() {
        val groupId = focusModeData?.focusGroupData?.groupId
        focusModeData = null
        CoroutineScope(Dispatchers.IO).launch {
            if (groupId != null) {
                val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
                val statsDao = db.focusStatsDao()
                val runningSessions = statsDao.getRunningSessions().filter { it.groupId == groupId }
                for (session in runningSessions) {
                    statsDao.update(session.copy(status = 1, actualEndTimeInMillis = session.estimatedEndTimeInMillis))
                }
            }
            service.dataStoreManager.setManualFocusStateToInactive()
        }
        notificationManager.stopTimer()
        updateSuspendedPackages(service)
    }

    fun doFocusModeCheck(event: AccessibilityEvent?) {
        val primaryPackage = event?.packageName?.toString() ?: ""
        val visiblePackages = service.getVisiblePackages(event)
        
        if (primaryPackage == service.packageName) return
        if (!service.isDelayOver(1000)) return

        if (focusModeData != null) {
            var packageBlocked = ""
            for (packageName in visiblePackages) {
                if (packageName == service.packageName) continue
                when (focusModeData!!.focusGroupData.blockMode) {
                    FocusBlockMode.BLOCK_SELECTED -> {
                        if (focusModeData!!.focusGroupData.packages.contains(packageName)) {
                            packageBlocked = packageName
                            break
                        }
                    }
                    FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED -> {
                        if (!focusModeData!!.focusGroupData.packages.contains(packageName) && packageName != "com.android.systemui" && !packageName.startsWith("com.miui")) {
                            packageBlocked = packageName
                            break
                        }
                    }
                }
            }

            if (packageBlocked.isNotEmpty()) {
                if (lastPackage != packageBlocked) {
                    lastPackage = packageBlocked
                    service.pressHome()
                    Log.d("focus mode","home pressed $packageBlocked")
                    
                    if (packageBlocked != primaryPackage) {
                        val launchIntent = service.packageManager.getLaunchIntentForPackage(packageBlocked)
                        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        try { service.startActivity(launchIntent) } catch (_: Exception) {}
                    }
                    return
                }
            } else if (primaryPackage.isNotEmpty()) {
                lastPackage = primaryPackage
            }

            if (focusModeData!!.focusGroupData.keywords.isNotEmpty() &&
                URL_BAR_ID_LIST.containsKey(primaryPackage)) {

                val now = System.currentTimeMillis()
                // Throttle website checks to every 400ms within the same app to preserve performance
                if (now - lastWebsiteCheckTime > 400) {
                    lastWebsiteCheckTime = now
                    if (keywordBlocker.isFocusWebsiteBlocked(primaryPackage, focusKeywordsPatterns, focusModeData!!.focusGroupData.blockMode)) {
                        if (now - lastBlockTime > 1500) {
                            service.pressBack()
                            Log.d("focus mode","back pressed")
                            lastBlockTime = now
                        }
                    }
                }
            }

            if (focusModeData!!.endTimeInMillis < System.currentTimeMillis()) {
                turnOffFocusMode()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_FOCUS_MODE)
            addAction(INTENT_ACTION_UNSUSPEND_ALL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun removeReceivers() {
        service.unregisterReceiver(refreshReceiver)
    }

    fun setupFocusMode(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        keywordBlocker.setupBlocker(service, watchSettings = false)
        if (!this::notificationManager.isInitialized) {
            notificationManager = TimerNotification(service)
        }

        // cache essential packages
        val essential = mutableSetOf("com.android.systemui")
        getDefaultLauncherPackageName(service.packageManager)?.let { essential.add(it) }
        getCurrentKeyboardPackageName(service)?.let { essential.add(it) }
        essentialPackages = essential

        Log.d("essential package", essentialPackages.toString())
        CoroutineScope(Dispatchers.IO).launch {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            val runningSessions = statsDao.getRunningSessions()
            for (session in runningSessions) {
                if (session.estimatedEndTimeInMillis < System.currentTimeMillis()) {
                     statsDao.update(session.copy(status = 1, actualEndTimeInMillis = session.estimatedEndTimeInMillis))
                }
            }
        }

        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                applySettings(settings)
            }
        }
    }

    /**
     * Applies settings to in-memory state and updates suspended packages.
     * Must be called from a coroutine context.
     */
    private suspend fun applySettings(settings: neth.iecal.curbox.data.models.Settings) {
        if (settings.activeManualFocusGroupId.first != null) {
            val currentFocusingGroup = settings.manualFocusGroups.find { it.groupId == settings.activeManualFocusGroupId.first }
            if (currentFocusingGroup != null && settings.activeManualFocusGroupId.second > System.currentTimeMillis()) {
                // Fix: copy the packages set instead of mutating the original data object
                val effectiveGroup = if (currentFocusingGroup.blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) {
                    val packagesCopy = HashSet(currentFocusingGroup.packages)
                    packagesCopy.addAll(essentialPackages)
                    currentFocusingGroup.copy(packages = packagesCopy)
                } else {
                    currentFocusingGroup
                }
                focusModeData = ManualFocusModeData(effectiveGroup, settings.activeManualFocusGroupId.second)
                focusKeywordsPatterns = keywordBlocker.compileKeywords(effectiveGroup.keywords)
                withContext(Dispatchers.Main) {
                    notificationManager.startTimer(
                        focusModeData!!.endTimeInMillis - System.currentTimeMillis(),
                        timerId = "focus_mode",
                        title = service.getString(R.string.notification_title_focus_mode_on)
                    )
                }
            } else {
                focusModeData = null
                withContext(Dispatchers.Main) {
                    notificationManager.stopTimer()
                }
            }
        } else {
            focusModeData = null
            withContext(Dispatchers.Main) {
                notificationManager.stopTimer()
            }
        }

        updateSuspendedPackages(service)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_FOCUS_MODE -> {
                    CoroutineScope(Dispatchers.IO).launch {
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
