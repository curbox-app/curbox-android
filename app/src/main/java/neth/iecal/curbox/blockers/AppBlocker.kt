package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.edit
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.ShizukuRunner
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class AppBlocker() : BaseBlocker() {

    companion object {
        /**
         * Refreshes information about warning screen, cheat hours and blocked app list
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "neth.iecal.curbox.refresh.appblocker"

        /**
         * Add cooldown to an app group.
         * This broadcast should always be sent together with the following keys:
         * selected_time: Int -> Duration of cooldown in millis
         * result_id : String -> ID of the app group to be put into cooldown
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.appblocker.cooldown"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private lateinit var prefs: SharedPreferences
        /**
     * Stores which blocked groups have been allowed by the user and until when.
     * group-id -> end-time-in-real-time-millis
     */
    private var cooldownGroupsList = ConcurrentHashMap<String, Long>()

    /**
     * Holds a usage limited app's config along with every package in its group.
     * Usage is compared against the combined total of all packages in [groupPackages].
     * Each entry carries its own group's warning screen so the right screen shows when it triggers.
     */
    data class UsageBlockEntry(
        val groupId: String,
        val config: AppUsageConfig,
        val groupPackages: List<String>,
        val warningConfig: AppBlockerWarningScreenConfig
    )

    /**
     * Holds a timed app's config along with its own group's warning screen.
     */
    data class TimeBlockEntry(
        val groupId: String,
        val config: AppTimeConfig,
        val warningConfig: AppBlockerWarningScreenConfig
    )

    data class OnOpenBlockEntry(
        val groupId: String,
        val groupPackages: Set<String>,
        val warningConfig: AppBlockerWarningScreenConfig
    )

    /**
     * Stores block apps with their configs. A package may belong to multiple groups, so each map
     * holds a list of entries. The app is blocked if any of its groups demands a block.
     */
    val blockedAppsList = ConcurrentHashMap<String, MutableList<UsageBlockEntry>>()
    val timeBlockedAppsList = ConcurrentHashMap<String, MutableList<TimeBlockEntry>>()
    private val onOpenAppsList = ConcurrentHashMap<String, MutableList<OnOpenBlockEntry>>()
    // Holds the warning config last shown per group, used for the cooldown intent's default duration.
    private val appBlockerWarningScrnConfgs = ConcurrentHashMap<String, AppBlockerWarningScreenConfig>()

    private lateinit var usageStats : UsageStatsHelper
    private var lastPackage = ""
    private lateinit var service: BaseBlockingService
    private var settingsJob: kotlinx.coroutines.Job? = null


    // responsible to trigger a recheck for what app user is currently using even when no event is received. Used in putting the usage recheck logic into
    // cooldown for an app and later when the cooldown duration is over, trigger a recheck
    private val handler = Handler(Looper.getMainLooper())

    private val activeRunnables = ConcurrentHashMap<String, Runnable>()

    private lateinit var notificationManager: TimerNotification

    private val ignoredApps = mutableListOf<String>("com.android.systemui")

    fun doAppBlockerCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val packageName = event.packageName?.toString() ?: return

        if (lastPackage == packageName || packageName == service.packageName || ignoredApps.contains(packageName)) {
            return
        }

        if (lastPackage != packageName) {
            onOpenAppsList[lastPackage]?.forEach { entry ->
                if (packageName !in entry.groupPackages) removeCooldownFrom(entry.groupId)
            }
        }

        val now = System.currentTimeMillis()
        onOpenAppsList[packageName]?.firstOrNull { !isGroupInCooldown(it.groupId, now) }?.let { entry ->
            notificationManager.stopTimer()
            showWarningScreen(packageName, entry.groupId, entry.warningConfig)
            return
        }

        timeBlockedAppsList[packageName]?.let { entries ->
            // Block if the current time is outside any one group's allowed schedule
            var earliestEnd: Long? = null
            for (entry in entries) {
                if (isGroupInCooldown(entry.groupId, now)) continue
                val endAllowedRealTime = getEndTimeInRealTimeMillis(entry.config)
                if (endAllowedRealTime == null) {
                    Log.d("AppBlocker", "Blocking $packageName (Timed - out of schedule)")
                    notificationManager.stopTimer()
                    showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                    return
                }
                if (earliestEnd == null || endAllowedRealTime < earliestEnd) earliestEnd = endAllowedRealTime
            }
            earliestEnd?.let {
                Log.d("AppBlocker", "App $packageName allowed until $it")
                setUpForcedRefreshChecker("time:$packageName", it)
            }
        }

        blockedAppsList[packageName]?.let { entries ->
            // Fetch today's usage once and reuse it for every group this app belongs to
            val todaysStats = runBlocking { usageStats.getForegroundStatsByRelativeDay(0) }
            var minRemaining = Long.MAX_VALUE
            for (entry in entries) {
                if (isGroupInCooldown(entry.groupId, now)) continue
                // Combined usage of every app in the group, so the limit applies to the group as a whole
                val currentUsage = todaysStats
                    .filter { it.packageName in entry.groupPackages }
                    .sumOf { it.totalTime }
                val usageLimitMillis = getUsageLimitForToday(entry.config) * 60_000L
                val remainingUsage = usageLimitMillis - currentUsage

                if (remainingUsage <= 0) {
                    notificationManager.stopTimer()
                    showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                    return
                }
                if (remainingUsage < minRemaining) minRemaining = remainingUsage
            }

            if (minRemaining != Long.MAX_VALUE) {
                notificationManager.startTimer(
                    totalMillis = minRemaining,
                    timerId = packageName,
                    title = service.getString(R.string.notification_title_remaining_usage)
                )
                setUpForcedRefreshChecker("usage:$packageName", System.currentTimeMillis() + minRemaining)
            } else {
                showNextCooldownNotification()
            }
            return
        }

        showNextCooldownNotification()

        lastPackage = packageName
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun onDestroy() {
        service.unregisterReceiver(refreshReceiver)
        notificationManager.release()
        handler.removeCallbacksAndMessages(null)
        activeRunnables.clear()
        settingsJob?.cancel()
    }

    fun setupAppBlocker(service: BaseBlockingService) {
        this.service = service
        notificationManager = TimerNotification(service)
        prefs = service.getSharedPreferences("app_blocker_prefs", Context.MODE_PRIVATE)
        usageStats = UsageStatsHelper(service)
        loadPersistedData()

        ignoredApps.add(getCurrentKeyboardPackageName(service)?:"com.google.android.inputmethod.latin")
        ignoredApps.add("com.google.android.apps.wellbeing")

        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                Log.d("AppBlocker", "Settings updated, groups count: ${settings.blockedAppGroups.size}")

                val newBlockedAppsList = ConcurrentHashMap<String, MutableList<UsageBlockEntry>>()
                val newTimeBlockedAppsList = ConcurrentHashMap<String, MutableList<TimeBlockEntry>>()
                val newOnOpenAppsList = ConcurrentHashMap<String, MutableList<OnOpenBlockEntry>>()

                settings.blockedAppGroups.forEach { group ->
                    if (!group.isActive) return@forEach

                    try {
                        Log.d("AppBlocker", "Loading group: ${group.name}, type: ${group.blockingType}, apps: ${group.selectedPackages}")
                        when (group.blockingType) {
                            AppBlockingType.Usage -> {
                                val config = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                                val groupPackages = group.selectedPackages.map { it.trim() }
                                val entry = UsageBlockEntry(group.id, config, groupPackages, group.warningScreenConfig)
                                groupPackages.forEach { pkg ->
                                    newBlockedAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                                }
                            }
                            AppBlockingType.Timed -> {
                                val config = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                                val entry = TimeBlockEntry(group.id, config, group.warningScreenConfig)
                                group.selectedPackages.forEach {
                                    val pkg = it.trim()
                                    newTimeBlockedAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                                }
                            }
                            AppBlockingType.OnOpen -> {
                                val groupPackages = group.selectedPackages.map { it.trim() }.toSet()
                                val entry = OnOpenBlockEntry(group.id, groupPackages, group.warningScreenConfig)
                                groupPackages.forEach { pkg ->
                                    newOnOpenAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AppBlocker", "Error loading group ${group.name}", e)
                    }
                }

                // Atomic-like update of the maps
                blockedAppsList.clear()
                blockedAppsList.putAll(newBlockedAppsList)

                timeBlockedAppsList.clear()
                timeBlockedAppsList.putAll(newTimeBlockedAppsList)

                onOpenAppsList.clear()
                onOpenAppsList.putAll(newOnOpenAppsList)

                Log.d("AppBlocker", "Maps updated. OnOpen: ${onOpenAppsList.keys().toList()}, Usage: ${blockedAppsList.keys().toList()}, Timed: ${timeBlockedAppsList.keys().toList()}")
                Log.d("AppBlocker", "Loaded: ${blockedAppsList.size} Usage, ${timeBlockedAppsList.size} Timed, ${onOpenAppsList.size} OnOpen apps")
                
                // Force a check for the currently open app after settings change
                handler.post {
                    try {
                        val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                        if (currentPackage != null) {
                            Log.d("AppBlocker", "Forcing re-check for current package: $currentPackage")
                            lastPackage = "" // Reset lastPackage to ensure doAppBlockerCheck doesn't return early
                            // Construct a dummy event to trigger the check
                            val dummyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                            dummyEvent.packageName = currentPackage
                            doAppBlockerCheck(dummyEvent)
                            dummyEvent.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("AppBlocker", "Error in forced re-check", e)
                    }
                }
            }
        }
    }

    private fun handlePutCooldownIntentBroadcast(intent: Intent) {
        val groupId = intent.getStringExtra("result_id") ?: return

        val durationMillis = intent.getIntExtra(
            "selected_time",
            appBlockerWarningScrnConfgs[groupId]?.timeInterval ?: 10
        )
        if (durationMillis <= 0) return
        Log.d("cooldown for ", durationMillis.toString())
        val realTimeEndMillis = System.currentTimeMillis() + durationMillis

        putCooldownTo(groupId, realTimeEndMillis)
        showNextCooldownNotification()
        setUpForcedRefreshChecker("cooldown:$groupId", realTimeEndMillis, groupId)
    }

    private fun getUsageLimitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) {
            config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            config.dailyLimits[dayOfWeek]
        }
    }

    private fun loadPersistedData() {
        val cooldownKeys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        cooldownKeys.forEach { groupId ->
            val endTime = prefs.getLong("cooldown_$groupId", 0L)
            if (endTime > System.currentTimeMillis()) {
                cooldownGroupsList[groupId] = endTime
                setUpForcedRefreshChecker("cooldown:$groupId", endTime, groupId)
            }
        }
    }

    private fun persistCooldownData() {
        prefs.edit {
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
            cooldownGroupsList.forEach { (groupId, endTime) ->
                putLong("cooldown_$groupId", endTime)
            }
        }
    }

    private fun putCooldownTo(groupId: String, realTimeEnd: Long) {
        cooldownGroupsList[groupId] = realTimeEnd
        persistCooldownData()
    }

    private fun removeCooldownFrom(groupId: String) {
        cooldownGroupsList.remove(groupId)
        prefs.edit {
            remove("cooldown_$groupId")
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
        }
    }

    private fun isGroupInCooldown(groupId: String, now: Long = System.currentTimeMillis()): Boolean {
        val endTime = cooldownGroupsList[groupId] ?: return false
        if (endTime > now) return true
        removeCooldownFrom(groupId)
        return false
    }

    private fun showNextCooldownNotification() {
        val now = System.currentTimeMillis()
        val next = cooldownGroupsList.filterValues { it > now }.minByOrNull { it.value }
        if (next == null) {
            notificationManager.stopTimer()
            return
        }
        notificationManager.startTimer(
            totalMillis = next.value - now,
            timerId = "app_cooldown:${next.key}:${next.value}",
            title = service.getString(R.string.notification_remaining_usage_lockdown),
            onFinishCallback = { showNextCooldownNotification() }
        )
    }

    private fun getEndTimeInRealTimeMillis(config: AppTimeConfig): Long? {
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1

        Log.d("day of week", dayOfWeek.toString())
        val intervals = if (config.isEveryday) config.everydayIntervals else config.dailyIntervals[dayOfWeek] ?: emptyList()

        intervals.forEach { interval ->
            val startMinutes = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val endMinutes = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)

            if (startMinutes <= endMinutes) {
                if (currentMinutes in startMinutes until endMinutes) {
                    val remainingMins = endMinutes - currentMinutes
                    return System.currentTimeMillis() + (remainingMins * 60_000L)
                }
            } else {
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) {
                    val remainingMins = if (currentMinutes >= startMinutes) {
                        (1440 - currentMinutes) + endMinutes
                    } else {
                        endMinutes - currentMinutes
                    }
                    return System.currentTimeMillis() + (remainingMins * 60_000L)
                }
            }
        }
        return null
    }

    private fun setUpForcedRefreshChecker(
        checkId: String,
        realTimeEndMillis: Long,
        cooldownGroupId: String? = null
    ) {
        activeRunnables[checkId]?.let { handler.removeCallbacks(it) }

        val delayMillis = realTimeEndMillis - System.currentTimeMillis()
        if (delayMillis <= 0) return // Time is already up

        val runnable = Runnable {
            try {
                cooldownGroupId?.let(::removeCooldownFrom)
                val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                if (currentPackage != null) {
                    lastPackage = ""
                    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                    event.packageName = currentPackage
                    doAppBlockerCheck(event)
                    event.recycle()
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Recheck error: $e")
                // Retry in 1 minute if UI check failed
                setUpForcedRefreshChecker(
                    checkId,
                    System.currentTimeMillis() + 60_000L,
                    cooldownGroupId
                )
            } finally {
                activeRunnables.remove(checkId)
            }
        }

        activeRunnables[checkId] = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun showWarningScreen(packageName: String, groupId: String, warningConfig: AppBlockerWarningScreenConfig) {
        if (service.isDelayOver(1000)) {

            // Remember the warning that was shown so the cooldown intent can read its default duration
            appBlockerWarningScrnConfgs[groupId] = warningConfig

            Log.d("AppBlocker", "Showing warning screen for $packageName")
            notificationManager.stopTimer()
            service.pressHome()
            lastPackage = ""

            try {
                if (AppSuspendHelper.isShizukuAvailable()) {
                    ShizukuRunner.executeCommand(
                        "am force-stop $packageName",
                        object : ShizukuRunner.CommandResultListener {})
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Shizuku force-stop failed", e)
            }

            if (warningConfig.isWarningDialogHidden) return

            handler.postDelayed({
                val dialogIntent = Intent(service, WarningActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                    putExtra("result_id", groupId)
                    putExtra("launch_package", packageName)
                    putExtra(
                        "warning_config",
                        Gson().toJson(warningConfig)
                    )
                }
                service.startActivity(dialogIntent)
            }, 100)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_APP_BLOCKER -> setupAppBlocker(service)
                INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> handlePutCooldownIntentBroadcast(intent)
            }
        }
    }
}
