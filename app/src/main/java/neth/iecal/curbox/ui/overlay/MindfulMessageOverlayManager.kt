package neth.iecal.curbox.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.*
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.MindfulMessageVariableValues
import neth.iecal.curbox.anti_stimulants.MindfulMessageVariables
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UsageStatsHelper

class MindfulMessageOverlayManager(
    private val context: Context,
    private val remainingUnlockMillis: (String) -> Long
) {

    companion object {
        fun applyAppearance(textView: TextView, config: MindfulMessageConfig) {
            textView.apply {
                textSize = config.textSize
                setTextColor(ColorUtils.setAlphaComponent(config.textColor, config.textOpacity * 255 / 100))
                setBackgroundColor(ColorUtils.setAlphaComponent(config.bgColor, config.bgOpacity * 255 / 100))
                setPadding(32, 32, 32, 32)
            }
        }
    }

    private var overlayView: View? = null
    var isOverlayVisible = false
    private var windowManager: WindowManager? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var updateJob: Job? = null

    private var sessionStartTime = 0L
    private var textView: TextView? = null
    private var displayedConfig = MindfulMessageConfig()
    private var appGroups = emptyList<AppGroup>()

    private val usageHelper = UsageStatsHelper(context)
    private val database = AppDatabase.getInstance(context.applicationContext)

    @SuppressLint("InflateParams")
    fun startDisplaying(
        pkgName: String,
        config: MindfulMessageConfig,
        appGroups: List<AppGroup>
    ) {
        val changed = displayedConfig != config
        displayedConfig = config
        this.appGroups = appGroups
        if (!isOverlayVisible || overlayView == null) {
            setupView(config)
            startTicker(pkgName)
        } else if (changed) {
            textView?.let { applyAppearance(it, config) }
            overlayView?.requestLayout()
        }
    }

    private fun setupView(config: MindfulMessageConfig) {
        sessionStartTime = System.currentTimeMillis()
        overlayView = LayoutInflater.from(context).inflate(R.layout.mindfulmsg_overlay, null)
        textView = overlayView?.findViewById<TextView>(R.id.mindful_txt)

        textView?.let { applyAppearance(it, config) }

        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * config.positionX).toInt()
            y = (screenHeight * config.positionY).toInt()
        }
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayView, params)
        isOverlayVisible = true

        overlayView?.viewTreeObserver?.addOnGlobalLayoutListener {
            val view = overlayView ?: return@addOnGlobalLayoutListener
            val metrics = context.resources.displayMetrics
            val x = (metrics.widthPixels * displayedConfig.positionX - view.width / 2f)
                .toInt().coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
            val y = (metrics.heightPixels * displayedConfig.positionY - view.height / 2f)
                .toInt().coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
            if (params.x != x || params.y != y) {
                params.x = x
                params.y = y
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (error: Exception) {
                    CrashLogger(context).logNonFatalError(error)
                }
            }
        }
    }

    private fun startTicker(pkgName: String) {
        updateJob?.cancel()
        val appName = runCatching {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkgName)
        updateJob = scope.launch {
            try {
                while (isActive) {
                    val config = displayedConfig
                    val appGroups = appGroups
                    val formatted = withContext(Dispatchers.IO) {
                        val todayStats =
                            if (MindfulMessageVariables.needsTodayUsage(config.messages)) {
                                usageHelper.getForegroundStatsByRelativeDay(0)
                            } else {
                                emptyList()
                            }
                        val yesterdayStats =
                            if (MindfulMessageVariables.needsYesterdayUsage(config.messages)) {
                                usageHelper.getForegroundStatsByRelativeDay(1)
                            } else {
                                emptyList()
                            }
                        val reelCount = if (MindfulMessageVariables.needsReelCount(config.messages)) {
                            database.reelStatsDao().getCount(TimeTools.getCurrentDate()) ?: 0
                        } else {
                            0
                        }
                        val todayByPackage =
                            todayStats.associate { it.packageName to it.totalTime }
                        val yesterdayByPackage =
                            yesterdayStats.associate { it.packageName to it.totalTime }
                        val appStat = todayStats.find { it.packageName == pkgName }

                        MindfulMessageVariables.format(
                            template = config.messages,
                            values = MindfulMessageVariableValues(
                                appName = appName,
                                appUsageTodayMs = appStat?.totalTime ?: 0L,
                                appUsageYesterdayMs = yesterdayByPackage[pkgName] ?: 0L,
                                appOpensToday = appStat?.sessions ?: 0,
                                screenTimeTodayMs = todayStats.sumOf { it.totalTime },
                                screenTimeYesterdayMs = yesterdayStats.sumOf { it.totalTime },
                                liveSessionMs = System.currentTimeMillis() - sessionStartTime,
                                remainingUnlockMs = if (config.messages.contains("{remaining_unlock_time}")) {
                                    remainingUnlockMillis(pkgName)
                                } else {
                                    0L
                                },
                                reelCount = reelCount,
                                todayUsageByPackage = todayByPackage,
                                yesterdayUsageByPackage = yesterdayByPackage
                            ),
                            mindfulApps = config.selectedApps,
                            appGroups = appGroups
                        )
                    }

                    textView?.text = formatted

                    delay(1000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(context).logNonFatalError(e)
            }
        }
    }

    fun removeOverlay() {
        updateJob?.cancel()
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
            isOverlayVisible = false
            textView = null
        }
    }
}
