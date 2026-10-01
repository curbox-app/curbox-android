package neth.iecal.curbox.utils

import android.content.Context
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.ui.fragments.main.usage.AppUsageStat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

class UsageStatsHelper(context: Context) {

    private val dao = AppDatabase.getInstance(context.applicationContext).appUsageDao()

    suspend fun getForegroundStatsByRelativeDay(offset: Int): List<AppUsageStat> {
        return getForegroundStatsByDay(LocalDate.now().minusDays(offset.toLong()))
    }

    suspend fun getForegroundStatsByDay(date: LocalDate): List<AppUsageStat> {
        return dao.getForDate(TimeTools.dayKey(date))
            .map { it.toStat() }
            .sortedByDescending { it.totalTime }
    }

    suspend fun getForegroundStatsByDays(
        dates: Collection<LocalDate>
    ): Map<LocalDate, List<AppUsageStat>> {
        if (dates.isEmpty()) return emptyMap()
        val datesByKey = dates.distinct().associateBy { TimeTools.dayKey(it) }
        return dao.getForDates(datesByKey.keys.toList())
            .mapNotNull { row -> datesByKey[row.date]?.let { date -> date to row.toStat() } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, stats) -> stats.sortedByDescending { it.totalTime } }
    }

    suspend fun getForegroundStatsByTimestamps(start: Long, end: Long): List<AppUsageStat> {
        val dates = datesBetween(start, end)
        if (dates.isEmpty()) return emptyList()

        val merged = HashMap<String, AppUsageStat>()
        for (row in dao.getForDates(dates)) {
            val existing = merged[row.packageName]
            if (existing == null) {
                merged[row.packageName] = row.toStat()
            } else {
                val hourly = existing.hourlyUsage.copyOf()
                val incoming = parseHourly(row.hourlyUsage)
                for (i in 0 until 24) hourly[i] += incoming[i]
                merged[row.packageName] = AppUsageStat(
                    packageName = row.packageName,
                    totalTime = existing.totalTime + row.totalTime,
                    sessions = existing.sessions + row.launchCount,
                    hourlyUsage = hourly
                )
            }
        }
        return merged.values.sortedByDescending { it.totalTime }
    }

    suspend fun getEarliestTimestamp(): Long = dao.earliestTimestamp() ?: System.currentTimeMillis()

    /**
     * Uses the same hourly buckets shown by AppUsageBreakdown and returns only the portion that
     * overlaps [startMs, endMs]. Sessions are already split on hour boundaries by AppUsageTracker.
     */
    suspend fun getForegroundUsageBetween(
        packageNames: Set<String>,
        startMs: Long,
        endMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        if (packageNames.isEmpty() || endMs <= startMs) return 0L
        val zone = ZoneId.systemDefault()
        val dates = datesBetween(startMs, endMs)
        var total = 0.0
        dao.getForDates(dates).asSequence()
            .filter { it.packageName in packageNames }
            .forEach { row ->
                val date = runCatching {
                    LocalDate.parse(row.date, TimeTools.dayKeyFormatter())
                }.getOrNull() ?: return@forEach
                val hourly = parseHourly(row.hourlyUsage)
                for (hour in 0 until 24) {
                    val bucketStart = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                    val bucketEnd = date.atTime(hour, 0).plusHours(1)
                        .atZone(zone).toInstant().toEpochMilli()
                    total += hourly[hour] * HourlyUsageBuckets.shareInWindow(
                        bucketStart, bucketEnd, startMs, endMs, nowMs
                    )
                }
            }
        return total.roundToLong()
    }

    private fun datesBetween(start: Long, end: Long): List<String> {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        if (endDate.isBefore(startDate)) return emptyList()

        val result = ArrayList<String>()
        var cursor = startDate
        while (!cursor.isAfter(endDate)) {
            result.add(TimeTools.dayKey(cursor))
            cursor = cursor.plusDays(1)
        }
        return result
    }

    private fun AppUsageEntity.toStat(): AppUsageStat {
        return AppUsageStat(
            packageName = packageName,
            totalTime = totalTime,
            sessions = launchCount,
            hourlyUsage = parseHourly(hourlyUsage)
        )
    }

    private fun parseHourly(serialized: String?): LongArray {
        val result = LongArray(24)
        if (serialized.isNullOrEmpty()) return result
        val parts = serialized.split(',')
        for (i in 0 until minOf(24, parts.size)) {
            result[i] = parts[i].toLongOrNull() ?: 0L
        }
        return result
    }
}
