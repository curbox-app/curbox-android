package nethical.digipaws.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import nethical.digipaws.Constants
import nethical.digipaws.blockers.ViewBlocker
import nethical.digipaws.ui.activity.MainActivity
import nethical.digipaws.ui.activity.WarningActivity

class ViewBlockerService : BaseBlockingService() {

    companion object {
        const val INTENT_ACTION_REFRESH_VIEW_BLOCKER = "nethical.digipaws.refresh.viewblocker"
        const val INTENT_ACTION_REFRESH_VIEW_BLOCKER_COOLDOWN =
            "nethical.digipaws.refresh.viewblocker.cooldown"

    }

    private val viewBlocker = ViewBlocker()
    private var warningScreenConfig = MainActivity.WarningData()
    private var lastEventTimeStamp = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isDelayOver(lastEventTimeStamp, 2000)) {
            return
        }
        val rootNode: AccessibilityNodeInfo? = rootInActiveWindow

        handleViewBlockerResult(rootNode?.let {
            viewBlocker.doesViewNeedToBeBlocked(
                it,
                event?.packageName.toString()
            )
        })
        lastEventTimeStamp = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {
        TODO("Not yet implemented")
    }


    private fun handleViewBlockerResult(result: ViewBlocker.ViewBlockerResult?) {
        if (result == null || !result.isBlocked) return

        pressBack()

        if(warningScreenConfig.isWarningDialogHidden) return
        val dialogIntent = Intent(this, WarningActivity::class.java)
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        dialogIntent.putExtra("warning_message", warningScreenConfig.message)
        dialogIntent.putExtra("mode", Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER)
        dialogIntent.putExtra("is_dynamic_timing", warningScreenConfig.isDynamicIntervalSettingAllowed)
        dialogIntent.putExtra("result_id", result.viewId)
        dialogIntent.putExtra("default_cooldown", warningScreenConfig.timeInterval / 60000)
        dialogIntent.putExtra("is_proceed_disabled", warningScreenConfig.isProceedDisabled)
        dialogIntent.putExtra("is_press_home", result.requestHomePressInstead)
        startActivity(dialogIntent)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_VIEW_BLOCKER -> setupBlocker()

                INTENT_ACTION_REFRESH_VIEW_BLOCKER_COOLDOWN -> {
                    val interval = intent.getIntExtra("selected_time", warningScreenConfig.timeInterval)
                    viewBlocker.applyCooldown(
                        intent.getStringExtra("result_id") ?: "xxxxxxxxxxxxxx",
                        SystemClock.uptimeMillis() + interval
                    )
                }
            }
        }
    }


    private fun setupBlocker() {
        warningScreenConfig = savedPreferencesLoader.loadViewBlockerWarningInfo()

        val viewBlockerCheatHours = getSharedPreferences("cheat_hours", Context.MODE_PRIVATE)
        viewBlocker.cheatMinuteStartTime =
            viewBlockerCheatHours.getInt("view_blocker_start_time", -1)
        viewBlocker.cheatMinutesEndTIme = viewBlockerCheatHours.getInt("view_blocker_end_time", -1)

        val addReelData = getSharedPreferences("config_reels", Context.MODE_PRIVATE)
        viewBlocker.isIGInboxReelAllowed = addReelData.getBoolean("is_reel_inbox", false)
        viewBlocker.isFirstReelInFeedAllowed = addReelData.getBoolean("is_reel_first", false)
        Log.d("data", viewBlocker.isFirstReelInFeedAllowed.toString())
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        setupBlocker()
        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT

        }
        serviceInfo = info

        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_VIEW_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_VIEW_BLOCKER_COOLDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
    }


}