package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.data.db.IntentLogEntity
import neth.iecal.curbox.data.db.ReelUsageStatsEntity
import neth.iecal.curbox.data.db.WebsiteHourlyUsageCodec
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.ui.fragments.main.usage.AppUsageStat
import java.time.LocalDate

data class DayTotal(val date: LocalDate, val totalTime: Long)

data class AppDetail(
    val packageName: String,
    val totalTime: Long,
    val previousTime: Long,
    val launches: Int,
    val daysUsed: Int,
    val peakHour: Int,
    val longestDay: DayTotal?,
    val share: Float
)

data class WebsiteDetail(
    val domain: String,
    val totalTime: Long,
    val daysVisited: Int,
    val peakHour: Int,
    val share: Float
)

data class CategoryShare(val category: String, val totalTime: Long, val apps: Int, val share: Float)

data class ReelAppDetail(val packageName: String, val count: Int, val totalTime: Long)

data class AdvancedAnalyticsReport(
    val start: LocalDate,
    val end: LocalDate,
    val dayCount: Int,
    val totalTime: Long,
    val previousTotalTime: Long,
    val dailyAverage: Long,
    val activeDays: Int,
    val appsUsed: Int,
    val launches: Int,
    val averageSession: Long,
    val topThreeShare: Float,
    val dailyTotals: List<DayTotal>,
    val busiestDay: DayTotal?,
    val quietestDay: DayTotal?,
    val hourly: LongArray,
    val peakHour: Int,
    /** Average time per hour for each weekday, Monday first. */
    val weekHeatmap: Array<LongArray>,
    val partsOfDay: LongArray,
    val weekdayAverage: Long,
    val weekendAverage: Long,
    val categories: List<CategoryShare>,
    val apps: List<AppDetail>,
    val websites: List<WebsiteDetail>,
    val websiteTotal: Long,
    val reelCount: Int,
    val reelTime: Long,
    val reelApps: List<ReelAppDetail>,
    val focusSessions: Int,
    val focusCompleted: Int,
    val focusStopped: Int,
    val focusTime: Long,
    val intentCount: Int,
    val intentUnlockedTime: Long,
    val intentApps: List<Pair<String, Int>>
) {
    val hasUsage: Boolean get() = totalTime > 0L

    companion object {
        /** Night, morning, afternoon and evening, in that order. */
        val PART_OF_DAY_HOURS = listOf(0 until 6, 6 until 12, 12 until 18, 18 until 24)
    }
}

/** Turns raw per day rows into the numbers shown on the advanced analytics screen. */
object AdvancedAnalyticsCalculator {

    fun build(
        start: LocalDate,
        end: LocalDate,
        apps: Map<LocalDate, List<AppUsageStat>>,
        previousApps: Map<LocalDate, List<AppUsageStat>>,
        websites: Map<LocalDate, List<WebsiteStatsEntity>>,
        reels: List<ReelUsageStatsEntity>,
        focusSessions: List<FocusStatsEntity>,
        intents: List<IntentLogEntity>,
        categoryOf: (String) -> String,
        nowMs: Long
    ): AdvancedAnalyticsReport {
        val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val dailyTotals = dates.map { date -> DayTotal(date, apps[date].orEmpty().sumOf { it.totalTime }) }
        val totalTime = dailyTotals.sumOf { it.totalTime }
        val activeDays = dailyTotals.filter { it.totalTime > 0L }

        val hourly = LongArray(24)
        val heatmapSums = Array(7) { LongArray(24) }
        val weekdayCounts = IntArray(7)
        dates.forEach { weekdayCounts[it.dayOfWeek.value - 1]++ }
        apps.filterKeys { it in dates }.forEach { (date, stats) ->
            val row = heatmapSums[date.dayOfWeek.value - 1]
            stats.forEach { stat ->
                stat.hourlyUsage.forEachIndexed { hour, ms ->
                    hourly[hour] += ms
                    row[hour] += ms
                }
            }
        }
        val weekHeatmap = Array(7) { day ->
            LongArray(24) { hour -> if (weekdayCounts[day] == 0) 0L else heatmapSums[day][hour] / weekdayCounts[day] }
        }
        val partsOfDay = LongArray(AdvancedAnalyticsReport.PART_OF_DAY_HOURS.size) { index ->
            AdvancedAnalyticsReport.PART_OF_DAY_HOURS[index].sumOf { hourly[it] }
        }

        val (weekend, weekday) = dailyTotals.partition { it.date.dayOfWeek.value >= 6 }

        val appDetails = buildAppDetails(apps, previousApps, totalTime)
        val launches = appDetails.sumOf { it.launches }

        val categories = appDetails.groupBy { categoryOf(it.packageName) }
            .map { (category, members) ->
                val time = members.sumOf { it.totalTime }
                CategoryShare(category, time, members.size, share(time, totalTime))
            }
            .sortedByDescending { it.totalTime }

        val websiteDetails = buildWebsiteDetails(websites)
        val reelApps = reels.groupBy { it.packageName }
            .map { (pkg, rows) -> ReelAppDetail(pkg, rows.sumOf { it.reelCount }, rows.sumOf { it.totalTime }) }
            .filter { it.count > 0 || it.totalTime > 0L }
            .sortedByDescending { it.totalTime }

        val focusTimes = focusSessions.map { session ->
            val endMs = if (session.status == 0) nowMs else session.actualEndTimeInMillis
            (endMs - session.startTimeInMillis).coerceAtLeast(0L)
        }

        return AdvancedAnalyticsReport(
            start = start,
            end = end,
            dayCount = dates.size,
            totalTime = totalTime,
            previousTotalTime = previousApps.values.sumOf { day -> day.sumOf { it.totalTime } },
            dailyAverage = if (dates.isEmpty()) 0L else totalTime / dates.size,
            activeDays = activeDays.size,
            appsUsed = appDetails.size,
            launches = launches,
            averageSession = if (launches == 0) 0L else totalTime / launches,
            topThreeShare = share(appDetails.take(3).sumOf { it.totalTime }, totalTime),
            dailyTotals = dailyTotals,
            busiestDay = activeDays.maxByOrNull { it.totalTime },
            quietestDay = activeDays.minByOrNull { it.totalTime },
            hourly = hourly,
            peakHour = peakHour(hourly),
            weekHeatmap = weekHeatmap,
            partsOfDay = partsOfDay,
            weekdayAverage = average(weekday),
            weekendAverage = average(weekend),
            categories = categories,
            apps = appDetails,
            websites = websiteDetails,
            websiteTotal = websiteDetails.sumOf { it.totalTime },
            reelCount = reelApps.sumOf { it.count },
            reelTime = reelApps.sumOf { it.totalTime },
            reelApps = reelApps,
            focusSessions = focusSessions.size,
            focusCompleted = focusSessions.count { it.status == 1 },
            focusStopped = focusSessions.count { it.status == 2 },
            focusTime = focusTimes.sum(),
            intentCount = intents.size,
            intentUnlockedTime = intents.sumOf { it.unlockedDurationMs },
            intentApps = intents.groupingBy { it.packageName }.eachCount()
                .toList().sortedByDescending { it.second }
        )
    }

    private fun buildAppDetails(
        apps: Map<LocalDate, List<AppUsageStat>>,
        previousApps: Map<LocalDate, List<AppUsageStat>>,
        totalTime: Long
    ): List<AppDetail> {
        val previousByPackage = previousApps.values.flatten()
            .groupBy { it.packageName }
            .mapValues { (_, stats) -> stats.sumOf { it.totalTime } }
        return apps.flatMap { (date, stats) -> stats.map { date to it } }
            .groupBy { it.second.packageName }
            .map { (pkg, rows) ->
                val hourly = LongArray(24)
                rows.forEach { (_, stat) -> stat.hourlyUsage.forEachIndexed { hour, ms -> hourly[hour] += ms } }
                val time = rows.sumOf { it.second.totalTime }
                AppDetail(
                    packageName = pkg,
                    totalTime = time,
                    previousTime = previousByPackage[pkg] ?: 0L,
                    launches = rows.sumOf { it.second.sessions },
                    daysUsed = rows.count { it.second.totalTime > 0L },
                    peakHour = peakHour(hourly),
                    longestDay = rows.maxByOrNull { it.second.totalTime }
                        ?.let { (date, stat) -> DayTotal(date, stat.totalTime) },
                    share = share(time, totalTime)
                )
            }
            .sortedByDescending { it.totalTime }
    }

    private fun buildWebsiteDetails(websites: Map<LocalDate, List<WebsiteStatsEntity>>): List<WebsiteDetail> {
        val rows = websites.flatMap { (date, stats) -> stats.map { date to it } }
        val total = rows.sumOf { it.second.totalTime }
        return rows.groupBy { it.second.domain.lowercase() }
            .map { (domain, visits) ->
                val hourly = LongArray(24)
                visits.forEach { (_, stat) ->
                    WebsiteHourlyUsageCodec.decode(stat.hourlyUsage)
                        .forEachIndexed { hour, ms -> hourly[hour] += ms.toLong() }
                }
                val time = visits.sumOf { it.second.totalTime }
                WebsiteDetail(
                    domain = domain,
                    totalTime = time,
                    daysVisited = visits.filter { it.second.totalTime > 0L }.map { it.first }.distinct().size,
                    peakHour = peakHour(hourly),
                    share = share(time, total)
                )
            }
            .filter { it.totalTime > 0L }
            .sortedByDescending { it.totalTime }
    }

    /** Returns -1 when no hour has any time. */
    private fun peakHour(hourly: LongArray): Int {
        val max = hourly.maxOrNull() ?: 0L
        return if (max <= 0L) -1 else hourly.indexOfFirst { it == max }
    }

    private fun average(days: List<DayTotal>): Long =
        if (days.isEmpty()) 0L else days.sumOf { it.totalTime } / days.size

    private fun share(part: Long, total: Long): Float = if (total <= 0L) 0f else part.toFloat() / total
}
