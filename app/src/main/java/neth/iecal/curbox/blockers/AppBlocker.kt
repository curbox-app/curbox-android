package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
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
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.upgradeLegacyAppGroupConfigs
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.ShizukuRunner
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.activeWindow
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.nextChangeAfter
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class AppBlocker : BaseBlocker() {

    companion object {
        /**
         * Refreshes information about warning screen, cheat hours and blocked app list
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "neth.iecal.curbox.refresh.appblocker"

        /**
         * Add cooldown to an app group.
         * This broadcast should always be sent together with the following keys:
         * selected_time: Long -> Duration of cooldown in millis
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

    private data class AppGroupEntry(
        val groupId: String,
        val config: AppGroupConfig,
        val groupPackages: List<String>,
        val warningConfig: AppBlockerWarningScreenConfig
    )

    private val blockedAppsList = ConcurrentHashMap<String, MutableList<AppGroupEntry>>()
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

    private val ignoredApps = mutableListOf("com.android.systemui")

    fun doAppBlockerCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val primaryPackage = event.packageName?.toString() ?: ""
        val visiblePackages = service.getVisiblePackages(event)
        
        if (primaryPackage.isNotEmpty() && primaryPackage != lastPackage && primaryPackage != service.packageName && !ignoredApps.contains(primaryPackage)) {
            clearFinishedOnEachOpenSessions(primaryPackage)
            lastPackage = primaryPackage
        }

        val now = System.currentTimeMillis()
        var minRemainingForAll = Long.MAX_VALUE
        var anyBlocked = false
        var packageBlocked = ""

        for (packageName in visiblePackages) {
            if (packageName == service.packageName || ignoredApps.contains(packageName)) continue

            val entries = blockedAppsList[packageName]
            if (entries != null) {
                var isThisPackageBlocked = false
                for (entry in entries) {
                    if (!entry.warningConfig.isOnOpenConfig || isGroupInCooldown(entry.groupId, now)) continue
                    val activeWindow = entry.config.schedule.activeWindow(now)
                    if (activeWindow == null) {
                        entry.config.schedule.nextChangeAfter(now)?.let { nextChange ->
                            setUpForcedRefreshChecker("schedule:${entry.groupId}:$packageName", nextChange)
                        }
                        continue
                    }
                    notificationManager.stopTimer()
                    showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                    isThisPackageBlocked = true
                    anyBlocked = true
                    packageBlocked = packageName
                    break
                }
                
                if (isThisPackageBlocked) break

                var minRemainingForPackage = Long.MAX_VALUE
                for (entry in entries) {
                    if (entry.warningConfig.isOnOpenConfig) continue
                    if (isGroupInCooldown(entry.groupId, now)) continue
                    val activeWindow = entry.config.schedule.activeWindow(now)
                    if (activeWindow == null) {
                        entry.config.schedule.nextChangeAfter(now)?.let { nextChange ->
                            setUpForcedRefreshChecker("schedule:${entry.groupId}:$packageName", nextChange)
                        }
                        continue
                    }

                    val currentUsage = runBlocking {
                        usageStats.getForegroundUsageBetween(
                            entry.groupPackages.toSet(),
                            activeWindow.startMs,
                            minOf(now, activeWindow.endMs)
                        )
                    }
                    val usageLimitMillis = getUsageLimitForToday(entry.config.usage) * 60_000L
                    val remainingUsage = usageLimitMillis - currentUsage

                    if (remainingUsage <= 0) {
                        notificationManager.stopTimer()
                        showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                        isThisPackageBlocked = true
                        anyBlocked = true
                        packageBlocked = packageName
                        break
                    }
                    if (remainingUsage < minRemainingForPackage) minRemainingForPackage = remainingUsage
                    setUpForcedRefreshChecker("schedule:${entry.groupId}:$packageName", activeWindow.endMs)
                }

                if (isThisPackageBlocked) break

                if (minRemainingForPackage != Long.MAX_VALUE) {
                    if (minRemainingForPackage < minRemainingForAll) minRemainingForAll = minRemainingForPackage
                    if (packageName == primaryPackage) {
                        notificationManager.startTimer(
                            totalMillis = minRemainingForPackage,
                            timerId = packageName,
                            title = service.getString(R.string.notification_title_remaining_usage)
                        )
                    }
                    setUpForcedRefreshChecker("usage:$packageName", System.currentTimeMillis() + minRemainingForPackage)
                }
            }
            if (anyBlocked) break
        }

        if (anyBlocked) {
            // Expand the PiP window if it's not the primary package
            if (packageBlocked.isNotEmpty() && packageBlocked != primaryPackage) {
                val launchIntent = service.packageManager.getLaunchIntentForPackage(packageBlocked)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                try {
                    service.startActivity(launchIntent)
                } catch (_: Exception) {}
            }
            return
        }

        showNextCooldownNotification()
    }

    /**
     * An on-each-open unlock lasts only while the user stays within that group's selected apps.
     */
    private fun clearFinishedOnEachOpenSessions(currentPackage: String) {
        blockedAppsList[lastPackage]
            .orEmpty()
            .asSequence()
            .filter { it.warningConfig.isOnOpenConfig }
            .filter { currentPackage !in it.groupPackages }
            .map(AppGroupEntry::groupId)
            .distinct()
            .filter(cooldownGroupsList::containsKey)
            .forEach(::removeCooldownFrom)
    }

    // THIS IS EXPORTED HERE INTENTIONALLY. THE APPBLOCKER SERVICE RUNS IN A DIFFERENT PROCESS THAN
    // THE MAIN UI.
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
        }
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
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

                val newBlockedAppsList = ConcurrentHashMap<String, MutableList<AppGroupEntry>>()
                settings.blockedAppGroups.upgradeLegacyAppGroupConfigs().forEach { group ->
                    if (!group.isActive) return@forEach

                    try {
                        val config = group.config ?: return@forEach
                        val groupPackages = group.selectedPackages.map(String::trim)
                        val entry = AppGroupEntry(
                            group.id,
                            config,
                            groupPackages,
                            group.warningScreenConfig
                        )
                        groupPackages.forEach { pkg ->
                            newBlockedAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                        }
                    } catch (e: Exception) {
                        Log.e("AppBlocker", "Error loading group ${group.name}", e)
                    }
                }

                // Atomic-like update of the maps
                blockedAppsList.clear()
                blockedAppsList.putAll(newBlockedAppsList)

                Log.d("AppBlocker", "Loaded ${blockedAppsList.size} scheduled usage apps")
                
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

        val durationMillis = intent.getLongExtra(
            "selected_time",
            appBlockerWarningScrnConfgs[groupId]?.timeInterval ?: 10L
        )
        if (durationMillis <= 0) return
        Log.d("cooldown for ", durationMillis.toString())
        val currentTimeMillis = System.currentTimeMillis()
        val realTimeEndMillis = if (durationMillis > Long.MAX_VALUE - currentTimeMillis) {
            Long.MAX_VALUE
        } else {
            currentTimeMillis + durationMillis
        }

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
