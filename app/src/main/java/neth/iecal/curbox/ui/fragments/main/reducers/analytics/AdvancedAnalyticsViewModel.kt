package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads usage recorded on this phone for the chosen number of days, plus the same number of days
 * before it for comparison.
 */
class AdvancedAnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    data class AppInfo(val label: CharSequence, val icon: Drawable?, val category: String)

    sealed class State {
        object Loading : State()
        data class Ready(val report: AdvancedAnalyticsReport) : State()
    }

    private val database = AppDatabase.getInstance(application)
    private val usageStatsHelper = UsageStatsHelper(application)
    private val packageManager = application.packageManager
    private val appInfoCache = ConcurrentHashMap<String, AppInfo>()

    // Matches the usage screen: typed searches are stored as domains but are not websites.
    private val domainRegex = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$", RegexOption.IGNORE_CASE)

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    var rangeDays = 7
        private set
    private var loadJob: Job? = null

    fun load(days: Int = rangeDays, force: Boolean = false) {
        if (!force && days == rangeDays && _state.value is State.Ready) return
        rangeDays = days
        loadJob?.cancel()
        _state.value = State.Loading
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = State.Ready(buildReport(days))
        }
    }

    private suspend fun buildReport(days: Int): AdvancedAnalyticsReport {
        val app = getApplication<Application>()
        val end = LocalDate.now()
        val start = end.minusDays(days - 1L)
        val previousStart = start.minusDays(days.toLong())
        val allDates = generateSequence(previousStart) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()

        val ignored = mutableSetOf<String>()
        getDefaultLauncherPackageName(packageManager)?.let { ignored += it }
        ignored += DataStoreManager(app).settings.first().usageTrackerIgnoredApps

        val appsByDate = usageStatsHelper.getForegroundStatsByDays(allDates)
            .mapValues { (_, stats) -> stats.filter { it.totalTime >= 1_000 && it.packageName !in ignored } }
        val (current, previous) = appsByDate.entries.partition { !it.key.isBefore(start) }

        val currentDates = allDates.filter { !it.isBefore(start) }
        val datesByKey = currentDates.associateBy { TimeTools.dayKey(it) }
        val websites = database.websiteStatsDao().getStatsForDates(datesByKey.keys.toList())
            .filter { it.isWebsite() }
            .groupBy { datesByKey.getValue(it.date) }
        val reels = database.reelUsageStatsDao().getForDates(datesByKey.keys.toList())

        val zone = ZoneId.systemDefault()
        val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val focus = database.focusStatsDao().getAllSessionsFlow().first()
            .filter { it.startTimeInMillis in startMs until endMs }
        val intents = database.intentLogDao().getAllIntentLogsSync()
            .filter { it.timestamp in startMs until endMs }

        val report = AdvancedAnalyticsCalculator.build(
            start = start,
            end = end,
            apps = current.associate { it.key to it.value },
            previousApps = previous.associate { it.key to it.value },
            websites = websites,
            reels = reels,
            focusSessions = focus,
            intents = intents,
            categoryOf = { appInfo(it).category },
            nowMs = System.currentTimeMillis()
        )
        (report.apps.map { it.packageName } + report.reelApps.map { it.packageName } +
            report.intentApps.map { it.first }).forEach { appInfo(it) }
        return report
    }

    private fun WebsiteStatsEntity.isWebsite(): Boolean =
        domain.isNotBlank() && !domain.contains(' ') && domainRegex.matches(domain)

    fun appInfo(packageName: String): AppInfo = appInfoCache.computeIfAbsent(packageName) {
        val app = getApplication<Application>()
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            val category = if (info.category == ApplicationInfo.CATEGORY_UNDEFINED) null
            else ApplicationInfo.getCategoryTitle(app, info.category)?.toString()
            AppInfo(
                info.loadLabel(packageManager),
                info.loadIcon(packageManager),
                category ?: app.getString(R.string.analytics_category_other)
            )
        } catch (e: Exception) {
            AppInfo(packageName, null, app.getString(R.string.analytics_category_other))
        }
    }
}
