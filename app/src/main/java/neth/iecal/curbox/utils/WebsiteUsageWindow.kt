package neth.iecal.curbox.utils

import neth.iecal.curbox.data.db.WebsiteHourlyUsageCodec
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

object WebsiteUsageWindow {
    fun sum(
        rows: Collection<WebsiteStatsEntity>,
        startMs: Long,
        endMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        if (endMs <= startMs) return 0L
        val zone = ZoneId.systemDefault()
        var total = 0.0
        rows.forEach { row ->
            val date = runCatching {
                LocalDate.parse(row.date, TimeTools.dayKeyFormatter())
            }.getOrNull() ?: return@forEach
            val buckets = WebsiteHourlyUsageCodec.decode(row.hourlyUsage)
            if (row.hourlyUsage.isEmpty()) {
                // Pre-migration rows have no buckets. Attribute them only when their last visit
                // falls in this window instead of charging every future linked interval.
                if (row.lastVisited in startMs until endMs) total += row.totalTime
                return@forEach
            }
            for (hour in 0 until 24) {
                if (buckets[hour] == 0) continue
                val bucketStart = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                val bucketEnd = date.atTime(hour, 0).plusHours(1)
                    .atZone(zone).toInstant().toEpochMilli()
                total += buckets[hour] * HourlyUsageBuckets.shareInWindow(
                    bucketStart, bucketEnd, startMs, endMs, nowMs
                )
            }
        }
        return total.roundToLong()
    }
}
