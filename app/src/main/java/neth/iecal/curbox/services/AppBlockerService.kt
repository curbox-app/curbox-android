package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.AutoDnd
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.anti_stimulants.MindfulMessage
import neth.iecal.curbox.blockers.AntiUninstallBlocker
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.ReelScriptRunner
import neth.iecal.curbox.blockers.uihider.NodePicker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.trackers.AppUsageTracker
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.trackers.ReelUsageTracker
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.trackers.WebsiteUsageTracker
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val appBlocker: AppBlocker = AppBlocker()
    private val focusModeBlocker = FocusModeBlocker()
    private val autoDnd = AutoDnd()
    private val reelBlocker = ReelBlocker()
    private val reelScriptRunner = ReelScriptRunner()
    private var keywordBlocker = KeywordBlocker()
    private val uiHider = UiHider()
    private val nodePicker = NodePicker()
    private val antiUninstallBlocker = AntiUninstallBlocker()

    private var grayScaleFilter = GrayScaleFilter()

    // Usage tracking, which used to live in its own accessibility service, now
    // runs here so the user only has to grant one service.
    private val reelsOverlayManager by lazy { ReelsOverlayManager(this) }
    private val reelsCountTracker = ReelsCountTracker()
    private val reelUsageTracker = ReelUsageTracker()
    private val mindfulMessage = MindfulMessage()
    private val websiteUsageTracker = WebsiteUsageTracker()
    private val appUsageTracker = AppUsageTracker()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val eventChannel = Channel<AccessibilityEvent>(Channel.CONFLATED) { droppedEvent ->
        droppedEvent.recycle()
    }
    private val websiteObservationChannel = Channel<WebsiteObservation?>(Channel.CONFLATED)

    // A still reel sends no events after its pager stops, so the last scroll is replayed once settled.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReelSettleEvent: AccessibilityEvent? = null
    private val reelSettleRecheck = Runnable {
        val event = pendingReelSettleEvent ?: return@Runnable
        pendingReelSettleEvent = null
        if (eventChannel.trySend(event).isFailure) {
            event.recycle()
        }
    }

    private lateinit var crashLogger: CrashLogger

    fun syncDndState() {
        val autoDndActive = autoDnd.isDndRequested()
        val manualFocusDndActive = focusModeBlocker.isDndRequested()
        neth.iecal.curbox.utils.DndHelper.applyDndState(this, autoDndActive || manualFocusDndActive)
    }

    override fun onCreate() {
        super.onCreate()
        crashLogger = CrashLogger(this)
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
        } catch (e: Exception) {
            Log.e("Shizuku", "Failed to bind Shizuku in non-provider process", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        try {
            if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
                antiUninstallBlocker.doAntiUninstallCheck(event)
            }
            appBlocker.doAppBlockerCheck(event)
            grayScaleFilter.doGrayscaleCheck(event)
            focusModeBlocker.doFocusModeCheck(event)
        } catch (t: Throwable) {
            Log.e("error", t.message ?: "Unknown error")
            crashLogger.logNonFatalError(Exception(t))
        }

        try {
            appUsageTracker.onEvent(event)
            if (reelsCountTracker.onPagerEvent(event)) {
                scheduleReelSettleRecheck(event)
            }
            mindfulMessage.onEvent(event)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t

            crashLogger.logNonFatalError(Exception(t))
            Log.e("Usage Tracking", "Usage tracking or mindful message error", t)
        }

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
    }

    private fun scheduleReelSettleRecheck(event: AccessibilityEvent) {
        mainHandler.removeCallbacks(reelSettleRecheck)
        pendingReelSettleEvent?.recycle()
        pendingReelSettleEvent = AccessibilityEvent.obtain(event)
        mainHandler.postDelayed(reelSettleRecheck, ReelsCountTracker.PAGER_SETTLE_RECHECK_MS)
    }

    private fun cancelReelSettleRecheck() {
        mainHandler.removeCallbacks(reelSettleRecheck)
        pendingReelSettleEvent?.recycle()
        pendingReelSettleEvent = null
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            for (event in eventChannel) {
                try {
                    websiteUsageTracker.onEvent(event)
                    val reelDetection = reelScriptRunner.detect(event)
                    val reelComparator = reelDetection?.comparator
                    reelUsageTracker.onEvent(event, reelComparator)
                    reelsCountTracker.onEvent(event, reelComparator)
                    reelBlocker.doViewBlockerCheck(
                        event,
                        reelComparator,
                        reelDetection?.isInstagramReelOpenedFromDmInbox == true
                    )
                    keywordBlocker.checkIfUnsupportedBrowser(event)
                    if (BuildConfig.SUPPORTS_UI_HIDER) {
                        uiHider.doUiHiderCheck(event)
                    }
                } catch (t: Throwable) {
                    // Don't log normal coroutine cancellations as crashes
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("Blocker", "Background worker error", t)
                } finally {
                    event.recycle()
                }
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            for (observation in websiteObservationChannel) {
                try {
                    keywordBlocker.onWebsiteObserved(observation)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("KeywordBlocker", "Website observation check failed", t)
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        appBlocker.setupAppBlocker(this)
        focusModeBlocker.setupFocusMode(this)
        autoDnd.setup(this)
        reelBlocker.setupBlocker(this)
        reelScriptRunner.setup(this)
        keywordBlocker.setupBlocker(this)
        grayScaleFilter.setup(this)
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupBlocker(this)
            nodePicker.setupBlocker(this)
        }
        if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            antiUninstallBlocker.setupBlocker(this)
        }

        reelUsageTracker.setup(this)
        reelsCountTracker.setup(this, reelsOverlayManager, reelUsageTracker::onReelCounted)
        mindfulMessage.setup(this)
        websiteUsageTracker.setup(this) { observation ->
            websiteObservationChannel.trySend(observation)
        }
        appUsageTracker.setup(this)
        neth.iecal.curbox.utils.UsageStatsCleaner.watch(this)

        focusModeBlocker.setupReceivers()
        appBlocker.setupReceivers()
        reelBlocker.setupReceivers()
        keywordBlocker.setupReceivers()
        grayScaleFilter.setupReceivers()
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupReceivers()
            nodePicker.setupReceivers()
        }
        reelsCountTracker.setupReceivers()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.please_provide_draw_over_other_apps),
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
        }

        startBackgroundWorker()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupFeature("reel settle recheck") { cancelReelSettleRecheck() }
        cleanupFeature("event worker") { serviceScope.cancel() }
        cleanupFeature("event channel") { eventChannel.cancel() }
        cleanupFeature("website observation channel") { websiteObservationChannel.cancel() }

        cleanupFeature("focus receivers") { focusModeBlocker.removeReceivers() }
        cleanupFeature("focus mode") { focusModeBlocker.onDestroy() }
        cleanupFeature("auto DND") { autoDnd.stop() }
        cleanupFeature("reel blocker") { reelBlocker.removeReceivers() }
        cleanupFeature("app blocker") { appBlocker.onDestroy() }
        cleanupFeature("keyword blocker") { keywordBlocker.removeReceivers() }
        cleanupFeature("grayscale") { grayScaleFilter.unregisterReceivers() }
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            cleanupFeature("UI hider") { uiHider.removeReceivers() }
            cleanupFeature("node picker") { nodePicker.removeReceivers() }
        }
        if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            cleanupFeature("anti uninstall") { antiUninstallBlocker.onDestroy() }
        }
        cleanupFeature("mindful messages") { mindfulMessage.onDestroy() }
        cleanupFeature("reel counter") { reelsCountTracker.onDestroy() }
        cleanupFeature("reel usage") { reelUsageTracker.onDestroy() }
        cleanupFeature("website usage") { websiteUsageTracker.onDestroy() }
        cleanupFeature("app usage") { appUsageTracker.onDestroy() }
    }

    private inline fun cleanupFeature(name: String, cleanup: () -> Unit) {
        try {
            cleanup()
        } catch (error: Throwable) {
            Log.e("AppBlockerService", "Failed to clean up $name", error)
            crashLogger.logNonFatalError(Exception("Failed to clean up $name", error))
        }
    }
}
