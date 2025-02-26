package nethical.digipaws.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import nethical.digipaws.blockers.ViewBlocker
import nethical.digipaws.ui.overlay.UsageStatOverlayManager
import nethical.digipaws.utils.TimeTools
import java.util.concurrent.TimeUnit

class UsageTrackingService : BaseBlockingService() {

    private var screenOnTime: Long = 0
    private var accumulatedTime: Long = 0
    private var isScreenOn = false
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private val usageStatOverlayManager by lazy { UsageStatOverlayManager(this) }
    private var userYSwipeEventCounter: Long = 0

    private var attentionSpanDataList = mutableMapOf<String, MutableList<AttentionSpanVideoItem>>()
    private var lastVideoViewFoundTime: Long? = null
    private var reelCountData = mutableMapOf<String, Int>()

    private var isReelCountToBeDisplayed = true
    private var isTimeElapsedCounterOn = true
    private var supportsViewScrolled = false

    private var displayOverlayApps = hashSetOf("") //show overlay only on these apps
    private var lastScrollTime: Long = 0
    private var lastScrollY: Float = 0f
    private var isScrollInProgress = false
    private val SCROLL_DEBOUNCE_TIME = 800L // Increased to 800ms
    private val MIN_SCROLL_DISTANCE = 100f // Minimum distance to consider a new scroll

    private var lastEventTimeStamp = 0L
    companion object {

        const val INTENT_ACTION_REFRESH_USAGE_TRACKER = "nethical.digipaws.refresh.usage_tracker"
        private const val UPDATE_INTERVAL = 1000L // 1 second
        private const val TAG = "ScreenTimeTracking"

        // when you scroll a video, different apps return different number of TYPE_VIEW_SCROLLED events. This list was prepared
        // after a thorough analysis of different apps.
        private val MIN_SCROLL_THRESHOLD = mapOf(
            "com.ss.android.ugc.trill" to 1,
            "com.zhiliaoapp.musically" to 1,
            "com.ss.android.ugc.aweme" to 1,

            "com.google.android.youtube" to 2,
            "app.revanced.android.youtube" to 2,
            "com.facebook.katana" to 2,
            "com.instagram.android" to 2

        )

        private val SUPPORTED_TRACKING_APPS = hashSetOf(
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme",

            "com.instagram.android",
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "com.facebook.katana"
        )

        private val TIKTOK_PACKAGES = hashSetOf(
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme",
        )
        const val VIDEO_TYPE_REEL = 1
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isTimeElapsedCounterOn) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_ACTION_REFRESH_USAGE_TRACKER -> setupTracker()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
        }

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })


        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_USAGE_TRACKER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }

        setupTracker()

        attentionSpanDataList = savedPreferencesLoader.loadUsageHoursAttentionSpanData()
        reelCountData = savedPreferencesLoader.getReelsScrolled()
        if (Settings.canDrawOverlays(this)) {
//            usageStatOverlayManager.startDisplaying()
        } else {
            Toast.makeText(
                this,
                "Please provide 'Draw over other apps' permission to make this service work properly. ",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
        }
    }

    private fun setupTracker(){
        val sp = getSharedPreferences("config_tracker",Context.MODE_PRIVATE)

        isReelCountToBeDisplayed = sp.getBoolean("is_reel_counter",true)
        isTimeElapsedCounterOn = sp.getBoolean("is_time_elapsed", false)

        displayOverlayApps = savedPreferencesLoader.getOverlayApps().toHashSet()
        if(isReelCountToBeDisplayed){
            displayOverlayApps.addAll(SUPPORTED_TRACKING_APPS)
        }
        if (!isTimeElapsedCounterOn) {
            usageStatOverlayManager.binding?.timeElapsedTxt?.visibility = View.GONE
            handleScreenOff()
        }else{
            usageStatOverlayManager.binding?.timeElapsedTxt?.visibility = View.VISIBLE

            // Initialize if the screen is already on
            if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
                handleScreenOn()
            }
        }

    }

    private fun handleScreenOn() {
        isScreenOn = true
        screenOnTime = System.currentTimeMillis()
        startTimeTracking()
        Log.d(TAG, "Screen ON - Continuing from: ${formatElapsedTime(accumulatedTime)}")
    }

    private fun handleScreenOff() {
        isScreenOn = false
        updateAccumulatedTime()
        stopTimeTracking()
        Log.d(TAG, "Screen OFF - Current accumulated: ${formatElapsedTime(accumulatedTime)}")
    }

    private fun startTimeTracking() {
        stopTimeTracking() // Ensure only one tracker runs
        updateRunnable = object : Runnable {
            override fun run() {
                if (isScreenOn) {
                    val currentTime = System.currentTimeMillis()
                    val totalTime = accumulatedTime + (currentTime - screenOnTime)
                    usageStatOverlayManager.binding?.timeElapsedTxt?.text =
                        formatElapsedTime(totalTime)
                    handler.postDelayed(this, UPDATE_INTERVAL)
                }
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopTimeTracking() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun updateAccumulatedTime() {
        if (isScreenOn) {
            accumulatedTime += System.currentTimeMillis() - screenOnTime
            screenOnTime = System.currentTimeMillis()
        }
    }

    private fun formatElapsedTime(milliseconds: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if(displayOverlayApps.contains(
                event?.packageName
            ))
        {
            if (Settings.canDrawOverlays(this)) {
                usageStatOverlayManager.startDisplaying()
            }
        }
        else if(usageStatOverlayManager.isOverlayVisible) {
            usageStatOverlayManager.removeOverlay()
        }

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isDelayOver(
                lastEventTimeStamp,
                2000
            )
        ) {


            // apps supports reel tracking
            if (SUPPORTED_TRACKING_APPS.contains(event.packageName)) {
                Log.d("source", event.source?.className.toString())
                // find reel tracking view and hide the counter if not found
                ViewBlocker.BLOCKED_VIEW_ID_LIST.forEach { viewId ->
                    if (ViewBlocker.findElementById(rootInActiveWindow, viewId) == null) {
                        hideReelTrackingView()
                    }
                }
            } else {
                // app is not supported so hide it
                hideReelTrackingView()
            }

            lastEventTimeStamp = SystemClock.uptimeMillis()

        }

        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Log.d("scroll", "Scroll event - ${event.scrollY} - ${event.source?.className}")
            supportsViewScrolled = true
            
            val currentTime = System.currentTimeMillis()
            val scrollY = event.scrollY.toFloat()
            
            // Only process the event if:
            // 1. There's no scroll in progress, or
            // 2. Enough time has passed since the last scroll and the distance is significant
            if (!isScrollInProgress || 
                (currentTime - lastScrollTime > SCROLL_DEBOUNCE_TIME && 
                Math.abs(scrollY - lastScrollY) > MIN_SCROLL_DISTANCE)) {
                
                when {
                    // handle tiktok scrolls
                    TIKTOK_PACKAGES.contains(event.packageName) && 
                    event.source?.className == "androidx.viewpager.widget.ViewPager" -> {
                        handleScrollEvent(event.packageName.toString())
                    }

                    // handle facebook scrolls
                    event.packageName == "com.facebook.katana" && 
                    event.source?.className == "androidx.recyclerview.widget.RecyclerView" -> {
                        val nodes = rootInActiveWindow.findAccessibilityNodeInfosByText(
                            "FbShortsComposerAttachmentComponentSpec_STICKER"
                        )
                        if (nodes.firstOrNull() != null) handleScrollEvent("com.facebook.katana")
                    }

                    // handle instagram scrolls
                    event.source?.className == "androidx.viewpager.widget.ViewPager" && 
                    event.packageName == "com.instagram.android" -> {
                        val reelView = ViewBlocker.findElementById(
                            rootInActiveWindow,
                            "com.instagram.android:id/root_clips_layout"
                        )
                        if (reelView != null) handleScrollEvent("com.instagram.android") 
                        else hideReelTrackingView()
                    }

                    // youtube scrolls
                    event.packageName == "com.google.android.youtube" &&
                    event.source?.className == "android.support.v7.widget.RecyclerView" -> {
                        val reelView = ViewBlocker.findElementById(
                            rootInActiveWindow,
                            "com.google.android.youtube:id/reel_recycler"
                        )
                        val commentsSection = ViewBlocker.findElementById(
                            rootInActiveWindow,
                            "com.google.android.youtube:id/engagement_panel_content"
                        )
                        if (reelView != null && commentsSection == null) 
                            handleScrollEvent("com.google.android.youtube") 
                        else hideReelTrackingView()
                    }

                    // revanced scrolls
                    event.packageName == "app.revanced.android.youtube" &&
                    event.source?.className == "android.support.v7.widget.RecyclerView" -> {
                        val reelView = ViewBlocker.findElementById(
                            rootInActiveWindow,
                            "app.revanced.android.youtube:id/reel_recycler"
                        )
                        val commentsSection = ViewBlocker.findElementById(
                            rootInActiveWindow,
                            "app.revanced.android.youtube:id/engagement_panel_content"
                        )
                        if (reelView != null && commentsSection == null) 
                            handleScrollEvent("app.revanced.android.youtube") 
                        else hideReelTrackingView()
                    }
                }
                
                lastScrollY = scrollY
                lastScrollTime = currentTime
            }
        }
    }

    private fun hideReelTrackingView() {
        usageStatOverlayManager.binding?.reelCounter?.visibility = View.GONE
        lastVideoViewFoundTime = null
    }

    private fun trackAttentionSpan(type: Int = VIDEO_TYPE_REEL) {
        lastVideoViewFoundTime?.let {
            var elapsedTime = (SystemClock.uptimeMillis() - it) / 1000f
            if (elapsedTime > 150f) {
                elapsedTime = 150f
            }
            val currentDate = TimeTools.getCurrentDate()
            if (attentionSpanDataList[currentDate] == null) {
                attentionSpanDataList[currentDate] = mutableListOf()
            }

            attentionSpanDataList[currentDate]?.add(
                AttentionSpanVideoItem(
                    elapsedTime,
                    TimeTools.getCurrentTime(),
                    type
                )
            )
            savedPreferencesLoader.saveReelsScrolled(reelCountData)
            savedPreferencesLoader.saveUsageHoursAttentionSpanData(attentionSpanDataList)

        }
        lastVideoViewFoundTime = SystemClock.uptimeMillis()
    }

    private fun handleScrollEvent(packageName: String) {
        if (++userYSwipeEventCounter > (MIN_SCROLL_THRESHOLD[packageName]!!)) {
            isScrollInProgress = true
            userYSwipeEventCounter = 0
            
            val date = TimeTools.getCurrentDate()
            val newCount = (reelCountData[date] ?: 0) + 1

            reelCountData[date] = newCount
            usageStatOverlayManager.reelsScrolledThisSession = newCount

            if(isReelCountToBeDisplayed){
                usageStatOverlayManager.binding?.reelCounter?.apply {
                    visibility = View.VISIBLE
                    text = newCount.toString()
                }
            }else{
                usageStatOverlayManager.binding?.reelCounter?.visibility = View.GONE
            }

            trackAttentionSpan()

            savedPreferencesLoader.saveReelsScrolled(reelCountData)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()
            
            // Schedule the reset of scroll state
            handler.postDelayed({
                isScrollInProgress = false
            }, SCROLL_DEBOUNCE_TIME)
        }
    }

    override fun onInterrupt() {
        stopTimeTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        stopTimeTracking()
    }

    data class AttentionSpanVideoItem(
        val elapsedTime: Float,
        val time: String,
        val type: Int = VIDEO_TYPE_REEL
    )
}
