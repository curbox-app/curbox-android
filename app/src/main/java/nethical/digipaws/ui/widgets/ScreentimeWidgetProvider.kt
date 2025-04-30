package nethical.digipaws.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import nethical.digipaws.R
import nethical.digipaws.ui.activity.FragmentActivity
import nethical.digipaws.ui.fragments.usage.AllAppsUsageFragment
import nethical.digipaws.utils.SavedPreferencesLoader
import nethical.digipaws.utils.TimeTools
import nethical.digipaws.utils.UsageStatsHelper
import nethical.digipaws.utils.getDefaultLauncherPackageName

class ScreentimeWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "ScreentimeWidgetProvider"
        private const val ACTION_WIDGET_REFRESH = "nethical.digipaws.screentime.WIDGET_REFRESH"
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        try {
            when (intent.action) {
                ACTION_WIDGET_REFRESH -> handleRefresh(context, intent)
                "android.appwidget.action.APPWIDGET_UPDATE" -> handleRefresh(context, intent)
                else -> Log.d(TAG, "Received unhandled action: ${intent.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling widget receive", e)
        }
    }

    private fun handleRefresh(context: Context, intent: Intent) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)

        if (widgetIds == null) {
            Log.e(TAG, "No widget IDs provided for refresh")
            return
        }

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
        getDefaultLauncherPackageName(context.packageManager)?.let {
            ignoredPackages.add(
                it
            )
        }
        val savedPreferencesLoader = SavedPreferencesLoader(context)
        ignoredPackages.addAll(savedPreferencesLoader.loadIgnoredAppUsageTracker())

        val list = usageStatsHelper.getForegroundStatsByRelativeDay(0).filter {
            it.totalTime >= 180_000 && it.packageName !in ignoredPackages
        }

        val totalScreentime = list.sumOf { it.totalTime }
        try{
            val views = RemoteViews(context.packageName, R.layout.widget_app_stats).apply {
                setTextViewText(R.id.screentime_widget, formatTime(totalScreentime))
                // Loop to handle the first 3 items dynamically
                for (i in 0..2) {
                    val item =
                        list.getOrNull(i) // Safely get the item, returns null if index is out of bounds
                    if (item != null) {
                        setAppUsageText(this, 0, list, R.id.app_1_sm, context)
                        setAppUsageText(this, 1, list, R.id.app_2_sm, context)
                        setAppUsageText(this, 2, list, R.id.app_3_sm, context)
                    }

                    // Set up refresh button
                    val refreshIntent = createRefreshIntent(context, widgetId)
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        widgetId,
                        refreshIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.refresh_stats_screentime, pendingIntent)

                    val intent = Intent(context, FragmentActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    intent.putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
                    val openIntent = PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_bg_app_stats, openIntent)
                }
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        } catch (e:Exception){
            Log.e(TAG, "Error updating widget $widgetId", e)
        }
    }

    fun setAppUsageText(remoteViews: RemoteViews,index: Int, list: List<AllAppsUsageFragment.Stat>, textViewId: Int, context: Context) {
        val item = list.getOrNull(index) // Safely get the item
        if (item != null) {
            val usage =  (TimeTools.formatTimeForWidget(item.totalTime))
            val appName = context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(item.packageName, 0)
            )
            remoteViews.setTextViewText(textViewId, "$usage : $appName")
        } else {
            remoteViews.setTextViewText(textViewId, "") // Handle missing items
        }
    }

    private fun createRefreshIntent(context: Context, widgetId: Int): Intent {
        return Intent(context, ScreentimeWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
    }


    fun formatTime(timeInMillis: Long): String {
        val hours = timeInMillis / (1000 * 60 * 60)
        val minutes = (timeInMillis % (1000 * 60 * 60)) / (1000 * 60)

        return buildString {
            if (hours > 0) append("${hours}h")
            if (minutes > 0) append(" ${minutes}m")
        }.trim()
    }


}