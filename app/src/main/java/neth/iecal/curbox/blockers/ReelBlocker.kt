package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.Constants
import neth.iecal.curbox.blockers.viewblocker.NodeMatcher
import neth.iecal.curbox.blockers.viewblocker.ViewBlocker
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelBlockingType
import neth.iecal.curbox.data.models.ReelTimeConfig
import neth.iecal.curbox.data.models.ReelCountConfig
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.reelData
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TimerNotification
import java.util.Calendar

class ReelBlocker : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_REEL_BLOCKER = "neth.iecal.curbox.refresh.reelblocker"
        const val INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN =
            "neth.iecal.curbox.refresh.reelblocker.cooldown"

        fun findElementById(node: AccessibilityNodeInfo?, id: String?): AccessibilityNodeInfo? {
            if (node == null) return null
            var targetNode: AccessibilityNodeInfo? = null
            try {
                targetNode = node.findAccessibilityNodeInfosByViewId(id!!)[0]
            } catch (e: Exception) {
                //e.printStackTrace();
            }
            return targetNode
        }

        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED

    }
    private lateinit var service : BaseBlockingService
    private val viewBlockerHelper = ViewBlocker()

    private var reelBlockerConfig: ReelBlocker = ReelBlocker(isActive = false)
    private var timeBAsedConfig : ReelTimeConfig? = null
    private var countBasedConfig : ReelCountConfig? = null
    private var currentDailyCount: Int = 0
    private var currentCountDate: String? = null
    private var settingsJob: Job? = null
    private var countJob: Job? = null
    
    private val cooldownViewIdsList = mutableMapOf<String, Long>()
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var lastEventTimeStamp = 0L

    private lateinit var notificationManager: TimerNotification

    fun doViewBlockerCheck(
        event: AccessibilityEvent?
    ){
        fun showWarningScreen(viewId: String){
            if(service.isDelayOver(service.lastBackPressTimeStamp,3000)) {
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

        val node = service.rootInActiveWindow
        if (node == null) return
        
        val pkg = event.packageName?.toString() ?: return
        val data = reelData[pkg] ?: return
        val viewId = data.viewId

        if(isViewOpened(node, viewId, pkg)){
            Log.d("reelblocker","view found")
            for (req in data.requiresPresent) {
                val matcher = NodeMatcher.parse(req)
                if (matcher != null) {
                    if (!viewBlockerHelper.findNodeByMatcher(node, matcher, pkg)) return
                } else {
                    if (findElementById(node, req) == null) return
                }
            }
            Log.d("reelblocker","all present")

            for (req in data.requiresAbsent) {
                val matcher = NodeMatcher.parse(req)
                if (matcher != null) {
                    if (viewBlockerHelper.findNodeByMatcher(node, matcher, pkg)) return
                } else {
                    if (findElementById(node, req) != null) return
                }
            }
            Log.d("reelblocker","all absent")

            if (isCooldownActive(viewId)) {
                return
            }

            when(reelBlockerConfig.blockingType) {
                ReelBlockingType.TIMED -> {
                    val endAllowedMillis = getEndTimeInMillis()
                    if(endAllowedMillis==null) {
                        showWarningScreen(viewId)
                    }
                }
                ReelBlockingType.USAGE -> TODO()
                ReelBlockingType.REEL_COUNT -> {
                    ensureCountFlowForToday()
                    val limit = getDailyReelCountLimit()
                    if (limit != null && limit > 0 && currentDailyCount >= limit) {
                        showWarningScreen(viewId)
                    }
                }
            }
        }
        
        lastEventTimeStamp = SystemClock.uptimeMillis()

    }


    fun applyCooldown(viewId: String, endTime: Long) {
        notificationManager.startTimer(totalMillis = endTime - SystemClock.uptimeMillis(), timerId = viewId, title = "Remaining usage before reels lockdown")
        cooldownViewIdsList[viewId] = endTime
    }


    fun setupBlocker(service: BaseBlockingService) {
        this.service = service
        this.viewBlockerHelper.service = service

        notificationManager = TimerNotification(service)
        var displayMetrics: DisplayMetrics = service.resources.displayMetrics
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels

        settingsJob?.cancel()
        countJob?.cancel()

        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                reelBlockerConfig = settings.reelBlockerConfig
                when(reelBlockerConfig.blockingType) {
                    ReelBlockingType.TIMED -> {
                        timeBAsedConfig = Gson().fromJson<ReelTimeConfig>(settings.reelBlockerConfig.settings,
                            ReelTimeConfig::class.java)
                    }
                    ReelBlockingType.USAGE -> TODO()
                    ReelBlockingType.REEL_COUNT -> {
                        countBasedConfig = Gson().fromJson<ReelCountConfig>(settings.reelBlockerConfig.settings,
                            ReelCountConfig::class.java)
                    }
                }
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers(){
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_REEL_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
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
                    val interval = intent.getIntExtra("selected_time", reelBlockerConfig.warningScreenConfig.timeInterval)
                    applyCooldown(
                        intent.getStringExtra("result_id") ?: "xxxxxxxxxxxxxx",
                        SystemClock.uptimeMillis() + interval
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

    private fun isViewOpened(rootNode: AccessibilityNodeInfo, viewId: String, pkg: String): Boolean {
        val viewIdMatcher = NodeMatcher.parse(viewId)
        val viewNode = if (viewIdMatcher != null) {
            viewBlockerHelper.resolveMatcherToNode(rootNode, viewIdMatcher, pkg)
        } else {
            findElementById(rootNode, viewId)
        }
        val nodeRect = Rect()
        viewNode?.getBoundsInScreen(nodeRect)
        val isOffScreenLeft = nodeRect.right <= 0
        val isOffScreenRight = nodeRect.left >= screenWidth
        return (viewNode != null && !isOffScreenLeft && !isOffScreenRight)
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
