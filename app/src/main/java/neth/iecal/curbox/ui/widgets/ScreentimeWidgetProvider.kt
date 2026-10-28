package neth.iecal.curbox.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.usage.AppUsageStat
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import java.time.LocalDate

class ScreentimeWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "ScreentimeWidgetProvider"
        private const val ACTION_WIDGET_REFRESH = "neth.iecal.curbox.screentime.WIDGET_REFRESH"
        private const val DEFAULT_WIDGET_HEIGHT_DP = 229
        private const val HEADER_HEIGHT_DP = 96
        private const val APP_ROW_HEIGHT_DP = 44

        private val APP_ROW_IDS = intArrayOf(
            R.id.app_row_1,
            R.id.app_row_2,
            R.id.app_row_3,
            R.id.app_row_4,
            R.id.app_row_5,
            R.id.app_row_6,
            R.id.app_row_7,
            R.id.app_row_8,
        )
        private val APP_ICON_IDS = intArrayOf(
            R.id.app_icon_1,
            R.id.app_icon_2,
            R.id.app_icon_3,
            R.id.app_icon_4,
            R.id.app_icon_5,
            R.id.app_icon_6,
            R.id.app_icon_7,
            R.id.app_icon_8,
        )
        private val APP_NAME_IDS = intArrayOf(
            R.id.app_name_1,
            R.id.app_name_2,
            R.id.app_name_3,
            R.id.app_name_4,
            R.id.app_name_5,
            R.id.app_name_6,
            R.id.app_name_7,
            R.id.app_name_8,
        )
        private val APP_USAGE_IDS = intArrayOf(
            R.id.app_usage_1,
            R.id.app_usage_2,
            R.id.app_usage_3,
            R.id.app_usage_4,
            R.id.app_usage_5,
            R.id.app_usage_6,
            R.id.app_usage_7,
            R.id.app_usage_8,
        )
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        try {
            appWidgetIds.forEach { widgetId ->
                updateWidget(context, appWidgetManager, widgetId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widgets", e)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        try {
            updateWidget(context, appWidgetManager, appWidgetId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize widget $appWidgetId", e)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        try {
            when (intent.action) {
                ACTION_WIDGET_REFRESH -> handleRefresh(context, intent)
                else -> Log.d(TAG, "Received unhandled action: ${intent.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling widget receive", e)
        }
    }

    private fun handleRefresh(context: Context, intent: Intent) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: appWidgetManager.getAppWidgetIds(ComponentName(context, ScreentimeWidgetProvider::class.java))

        widgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val usageStatsHelper = UsageStatsHelper(context)
        val ignoredPackages = mutableSetOf<String>()
        getDefaultLauncherPackageName(context.packageManager)?.let { ignoredPackages.add(it) }

        val ignoredApps = runBlocking {
            DataStoreManager(context).settings.first().usageTrackerIgnoredApps
        }
        ignoredPackages.addAll(ignoredApps)

        val localList = runBlocking { usageStatsHelper.getForegroundStatsByRelativeDay(0) }.filter {
            it.totalTime >= 1_000 && it.packageName !in ignoredPackages
        }

        val list = localList.sortedByDescending { it.totalTime }

        val totalScreentime = list.sumOf { it.totalTime }

        try {
            val views = RemoteViews(context.packageName, R.layout.widget_app_stats_v2).apply {
                setTextViewText(R.id.screentime_widget, formatTime(totalScreentime))

                val visibleAppCount = getVisibleAppCount(appWidgetManager, widgetId, list.size)
                APP_ROW_IDS.indices.forEach { index ->
                    setAppUsageRow(this, index, list, visibleAppCount, context)
                }

                val refreshIntent = createRefreshIntent(context, widgetId)
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.refresh_stats_screentime, refreshPendingIntent)

                val openIntent = Intent(context, FragmentActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
                }
                val openPendingIntent = PendingIntent.getActivity(
                    context,
                    widgetId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_bg_app_stats, openPendingIntent)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget $widgetId", e)
        }
    }

    // Combines other devices' per app time into this device's list: matching apps
    // get their time added together, and apps that only ran on another device are
    // appended as their own rows. Mirrors AllAppsUsageViewModel.mergeRemoteApps.
    private fun mergeRemoteApps(
        local: List<AppUsageStat>,
        remote: Map<String, Long>,
        ignoredPackages: Set<String>,
    ): List<AppUsageStat> {
        if (remote.isEmpty()) return local
        val localByPkg = local.associateBy { it.packageName }
        val merged = ArrayList<AppUsageStat>(local.size + remote.size)
        for (st in local) {
            val extra = remote[st.packageName] ?: 0L
            merged.add(
                if (extra > 0L) {
                    AppUsageStat(st.packageName, st.totalTime + extra, st.sessions, st.hourlyUsage)
                } else {
                    st
                }
            )
        }
        for ((pkg, ms) in remote) {
            if (pkg !in localByPkg && ms >= 1_000 && pkg !in ignoredPackages) {
                merged.add(AppUsageStat(pkg, ms))
            }
        }
        return merged
    }

    private fun getVisibleAppCount(
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        availableApps: Int,
    ): Int {
        val options = appWidgetManager.getAppWidgetOptions(widgetId)
        val widgetHeight = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            DEFAULT_WIDGET_HEIGHT_DP,
        ).takeIf { it > 0 } ?: DEFAULT_WIDGET_HEIGHT_DP
        val rowsThatFit = ((widgetHeight - HEADER_HEIGHT_DP) / APP_ROW_HEIGHT_DP)
            .coerceAtLeast(1)
        return minOf(rowsThatFit, availableApps, APP_ROW_IDS.size)
    }

    private fun setAppUsageRow(
        remoteViews: RemoteViews,
        index: Int,
        list: List<AppUsageStat>,
        visibleAppCount: Int,
        context: Context,
    ) {
        if (index >= visibleAppCount) {
            remoteViews.setViewVisibility(APP_ROW_IDS[index], View.GONE)
            return
        }

        val item = list[index]
        val metadata = getAppMetadata(context, item.packageName)
        remoteViews.setViewVisibility(APP_ROW_IDS[index], View.VISIBLE)
        remoteViews.setTextViewText(APP_NAME_IDS[index], metadata.label)
        remoteViews.setTextViewText(
            APP_USAGE_IDS[index],
            TimeTools.formatTimeForWidget(item.totalTime),
        )
        metadata.icon?.let { icon ->
            remoteViews.setImageViewBitmap(APP_ICON_IDS[index], icon.toBitmap(context, 32))
        }
    }

    private fun getAppMetadata(context: Context, packageName: String): WidgetAppMetadata {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            WidgetAppMetadata(
                label = appInfo.loadLabel(context.packageManager),
                icon = appInfo.loadIcon(context.packageManager),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            WidgetAppMetadata(
                label = packageName,
                icon = ContextCompat.getDrawable(context, R.drawable.baseline_warning_24),
            )
        }
    }

    private fun Drawable.toBitmap(context: Context, sizeDp: Int): Bitmap {
        val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    private fun createRefreshIntent(context: Context, widgetId: Int): Intent {
        return Intent(context, ScreentimeWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
    }

    private fun formatTime(timeInMillis: Long): String {
        val hours = timeInMillis / (1000 * 60 * 60)
        val minutes = (timeInMillis % (1000 * 60 * 60)) / (1000 * 60)

        if (hours == 0L && minutes == 0L) return "0m"

        return buildString {
            if (hours > 0) append("${hours}h")
            if (minutes > 0) append(" ${minutes}m")
        }.trim()
    }

    private data class WidgetAppMetadata(
        val label: CharSequence,
        val icon: Drawable?,
    )
}
