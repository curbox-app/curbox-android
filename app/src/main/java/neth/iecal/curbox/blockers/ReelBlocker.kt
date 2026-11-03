package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelTimeConfig
import neth.iecal.curbox.data.models.ReelCountConfig
import neth.iecal.curbox.data.models.upgradeLegacyConfig
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.hardcoded.ReelAppConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TimerNotification
import java.util.Calendar

class ReelBlocker : BaseBlocker() {

    companion object {
        private const val TIMER_NOTIFICATION_ID = 1003
        const val INTENT_ACTION_REFRESH_REEL_BLOCKER = "neth.iecal.curbox.refresh.reelblocker"
        const val INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN =
            "neth.iecal.curbox.refresh.reelblocker.cooldown"


        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED

    }
    private lateinit var service : BaseBlockingService

    private var reelBlockerConfig: ReelBlocker = ReelBlocker(isActive = false)
    private var timeBAsedConfig : ReelTimeConfig? = null
    private var countBasedConfig : ReelCountConfig? = null
    private var currentDailyCount: Int = 0
    private var currentCountDate: String? = null
    private var settingsJob: Job? = null
    private var countJob: Job? = null
    
    private val cooldownViewIdsList = mutableMapOf<String, Long>()
    private var lastEventTimeStamp = 0L

    private lateinit var notificationManager: TimerNotification

    fun doViewBlockerCheck(
        event: AccessibilityEvent?,
        dynamicComparator: String?,
        isInstagramReelOpenedFromDmInbox: Boolean
    ){
        fun showWarningScreen(viewId: String){
            if(service.isDelayOver(3000)) {
                service.pressBack()

                if (reelBlockerConfig.warningScreenConfig.isWarningDialogHidden) return
                val dialogIntent = Intent(service, WarningActivity::class.java)
                dialogIntent.flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                dialogIntent.putExtra("mode", Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER)
                dialogIntent.putExtra("result_id", viewId)
                dialogIntent.putExtra(
                    "warning_config",
                    Gson().toJson(reelBlockerConfig.warningScreenConfig)
                )
                service.startActivity(dialogIntent)
            }
        }
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        if (!reelBlockerConfig.isActive ) {
            return
        }

        val pkg = event.packageName?.toString() ?: return
        if (pkg in reelBlockerConfig.excludedPackages) return
        if (pkg == ReelAppConfig.INSTAGRAM_PACKAGE &&
            isInstagramReelOpenedFromDmInbox &&
            reelBlockerConfig.allowInstagramReelsFromDmInbox
        ) return

        val viewId = pkg

        if (dynamicComparator != null) {
            if (isCooldownActive(viewId)) {
                return
            }

            val endAllowedMillis = getEndTimeInMillis()
            if (endAllowedMillis == null) {
                showWarningScreen(viewId)
                return
            }

            val usageLimit = getDailyReelUsageLimit()
            if (usageLimit > 0 && getTodayReelUsageMillis() >= usageLimit * 60_000L) {
                showWarningScreen(viewId)
                return
            }

            ensureCountFlowForToday()
            val limit = getDailyReelCountLimit()
            if (limit != null && limit > 0 && currentDailyCount >= limit) {
                showWarningScreen(viewId)
            }
        }
        
        lastEventTimeStamp = SystemClock.uptimeMillis()

    }


    fun applyCooldown(viewId: String, endTime: Long) {
        val now = SystemClock.uptimeMillis()
        if (endTime <= now) return
        notificationManager.startTimer(totalMillis = endTime - now, timerId = viewId, title = service.getString(R.string.notification_remaining_usage_reels_lockdown))
        cooldownViewIdsList[viewId] = endTime
    }


    fun setupBlocker(service: BaseBlockingService) {
        this.service = service

        if (!this::notificationManager.isInitialized) {
            notificationManager = TimerNotification(service, TIMER_NOTIFICATION_ID)
        }

        settingsJob?.cancel()
        countJob?.cancel()

        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                reelBlockerConfig = settings.reelBlockerConfig.upgradeLegacyConfig()
                val config = reelBlockerConfig.config
                timeBAsedConfig = config?.schedule
                countBasedConfig = config?.reelCount
            }
        }

        launchCountFlow(TimeTools.getCurrentDate())
    }

    /**
     * Re-subscribes currentDailyCount to today's row. Room's flow only emits when the
     * queried row changes, so a subscription bound to yesterday's date never sees today's
     * writes — without this, yesterday's cap keeps blocking after midnight.
     */
    private fun ensureCountFlowForToday() {
        val today = TimeTools.getCurrentDate()
        if (today != currentCountDate) {
            currentDailyCount = 0
            launchCountFlow(today)
        }
    }

    private fun launchCountFlow(date: String) {
        countJob?.cancel()
        currentCountDate = date
        val db = AppDatabase.getInstance(service)
        countJob = CoroutineScope(Dispatchers.IO).launch {
            db.reelStatsDao().getCountFlow(date).collectLatest { count ->
                currentDailyCount = count ?: 0
            }
        }
    }

    fun setupReceivers(){
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_REEL_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN)
        }
        ContextCompat.registerReceiver(
            service, refreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun removeReceivers(){
        service.unregisterReceiver(refreshReceiver)
        notificationManager.release()
        settingsJob?.cancel()
        countJob?.cancel()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_REEL_BLOCKER -> setupBlocker(service)

                INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN -> {
                    val interval = intent.getLongExtra(
                        "selected_time",
                        reelBlockerConfig.warningScreenConfig.timeInterval
                    )
                    if (interval <= 0L) return
                    val currentUptime = SystemClock.uptimeMillis()
                    val endTime = if (interval > Long.MAX_VALUE - currentUptime) {
                        Long.MAX_VALUE
                    } else {
                        currentUptime + interval
                    }
                    applyCooldown(
                        intent.getStringExtra("result_id") ?: "xxxxxxxxxxxxxx",
                        endTime
                    )
                }
            }
        }
    }

    private fun isCooldownActive(viewId: String): Boolean {
        val cooldownEnd = cooldownViewIdsList[viewId] ?: return false
        if (SystemClock.uptimeMillis() > cooldownEnd) {
            cooldownViewIdsList.remove(viewId)
            return false
        }
        return true
    }

    private fun getDailyReelCountLimit(): Int? {
        val config = countBasedConfig ?: return null
        if (config.isDailyUniform) {
            return config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday, 1=Monday...
            return config.dailyLimits[dayOfWeek]
        }
    }

    private fun getDailyReelUsageLimit(): Long {
        val config = reelBlockerConfig.config?.usage ?: return 0
        if (config.isDailyUniform) return config.uniformLimit
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        return config.dailyLimits[dayOfWeek]
    }

    private fun getTodayReelUsageMillis(): Long = runBlocking {
        AppDatabase.getInstance(service).reelUsageStatsDao()
            .getForDate(TimeTools.getCurrentDate())
            .filter { it.packageName !in reelBlockerConfig.excludedPackages }
            .sumOf { it.totalTime }
    }

    /**
     * @return null if reels is not currently allowed by time config, or the end time in uptimeMillis if allowed.
     */
    private fun getEndTimeInMillis(): Long? {

        if(timeBAsedConfig==null) return null
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(currentHour, currentMinute)

        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday, 1=Monday...

        val intervals = if (timeBAsedConfig!!.isEveryday) {
            timeBAsedConfig!!.everydayIntervals
        } else {
            timeBAsedConfig!!.dailyIntervals[dayOfWeek] ?: emptyList()
        }

        intervals.forEach { interval ->
            val startMinutes = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val endMinutes = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)

            if (startMinutes <= endMinutes) {
                if (currentMinutes in startMinutes until endMinutes) {
                    val remainingMins = endMinutes - currentMinutes
                    return SystemClock.uptimeMillis() + (remainingMins * 60 * 1000L)
                }
            } else {
                // cross midnight
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) {
                    val remainingMins = if (currentMinutes >= startMinutes) {
                        (1440 - currentMinutes) + endMinutes
                    } else {
                        endMinutes - currentMinutes
                    }
                    return SystemClock.uptimeMillis() + (remainingMins * 60 * 1000L)
                }
            }
        }
        return null
    }

}
