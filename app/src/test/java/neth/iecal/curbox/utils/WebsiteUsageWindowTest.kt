package neth.iecal.curbox.utils

import neth.iecal.curbox.data.db.WebsiteHourlyUsageCodec
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WebsiteUsageWindowTest {
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.of(2026, 9, 22)

    private fun at(hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun row(vararg usage: Pair<Int, Int>): WebsiteStatsEntity {
        val buckets = IntArray(24)
        usage.forEach { (hour, millis) -> buckets[hour] = millis }
        return WebsiteStatsEntity(
            date = TimeTools.dayKey(day),
            packageName = "com.android.chrome",
            urlIdentifier = "instagram.com",
            domain = "instagram.com",
            totalTime = buckets.sum().toLong(),
            hourlyUsage = WebsiteHourlyUsageCodec.encode(buckets)
        )
    }

    @Test
    fun currentHourUsageCountsInFull() {
        val threeMinutes = 3 * 60_000
        val now = at(13, 5)

        val used = WebsiteUsageWindow.sum(listOf(row(13 to threeMinutes)), at(11), now, now)

        assertEquals(threeMinutes.toLong(), used)
    }

    @Test
    fun finishedHoursCountInFull() {
        val now = at(15, 30)

        val used = WebsiteUsageWindow.sum(
            listOf(row(11 to 60_000, 14 to 120_000, 15 to 30_000)),
            at(11),
            now,
            now
        )

        assertEquals(210_000L, used)
    }

    @Test
    fun windowStartingMidHourCountsOnlyItsElapsedShare() {
        val now = at(21, 45)

        val used = WebsiteUsageWindow.sum(listOf(row(21 to 90_000)), at(21, 30), now, now)

        // 15 of the 45 elapsed minutes fall inside the window.
        assertEquals(30_000L, used)
    }

    @Test
    fun windowEndingMidHourUsesElapsedTimeOfThatHour() {
        val now = at(21, 50)

        val used = WebsiteUsageWindow.sum(listOf(row(21 to 100_000)), at(11), at(21, 30), now)

        assertEquals(60_000L, used)
    }

    @Test
    fun bucketOutsideWindowIsIgnored() {
        val now = at(13, 5)

        val used = WebsiteUsageWindow.sum(listOf(row(9 to 60_000)), at(11), now, now)

        assertEquals(0L, used)
    }
}
