package neth.iecal.curbox.ui.fragments.main.focus

import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.utils.FocusGoalProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Turns raw focus rows into the numbers shown on the focus stats screen. */
object FocusStatsCalculator {

    /** One thing the user started: a plain focus session, or every focus round of one Pomodoro. */
    data class Session(
        val groupId: String,
        val startMs: Long,
        val focusMs: Long,
        val rounds: Int,
        val isPomodoro: Boolean,
        val endedEarly: Boolean,
        val isRunning: Boolean
    )

    data class GroupShare(val groupId: String, val focusMs: Long, val sessions: Int)

    data class DayTotal(val date: LocalDate, val focusMs: Long)

    data class Report(
        val totalFocusMs: Long,
        val todayMs: Long,
        val dailyAverageMs: Long,
        val sessionCount: Int,
        val completionPercent: Int,
        val averageSessionMs: Long,
        val longestSessionMs: Long,
        val currentStreak: Int,
        val bestStreak: Int,
        val lastSevenDays: List<DayTotal>,
        /** Night, morning, afternoon, and evening, matching [PART_OF_DAY_START_HOURS]. */
        val partsOfDay: List<Long>,
        val pomodoroCount: Int,
        val pomodoroRounds: Int,
        val pomodoroFocusMs: Long,
        val groups: List<GroupShare>,
        val recentSessions: List<Session>
    )

    val PART_OF_DAY_START_HOURS = listOf(0, 6, 12, 18)
    private const val RECENT_SESSION_COUNT = 5

    /** [rangeDays] counts today as one day. Null means all time. */
    fun build(rows: List<FocusStatsEntity>, rangeDays: Int?, now: Long, zone: ZoneId): Report {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val firstDay = rows.minOfOrNull { it.startTimeInMillis }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: today
        val rangeStartDay = if (rangeDays == null) firstDay else today.minusDays(rangeDays - 1L)
        val rangeStart = startOf(rangeStartDay, zone)
        val tomorrowStart = startOf(today.plusDays(1), zone)

        val rangeRanges = FocusGoalProgress.focusRanges(rows, rangeStart, tomorrowStart, now)
        val totalFocusMs = rangeRanges.sumOf { it.second - it.first }
        val daysInRange = ChronoUnit.DAYS.between(rangeStartDay, today).coerceAtLeast(0L) + 1

        val sessions = toSessions(rows, now).filter { it.startMs >= rangeStart }
        val doneSessions = sessions.filterNot { it.isRunning }
        val finishedCount = doneSessions.count { !it.endedEarly }
        val pomodoros = sessions.filter { it.isPomodoro }

        val (currentStreak, bestStreak) = streaks(rows, today, now, zone)

        return Report(
            totalFocusMs = totalFocusMs,
            todayMs = FocusGoalProgress.durationWithinDay(rows, startOf(today, zone), tomorrowStart, now),
            dailyAverageMs = totalFocusMs / daysInRange,
            sessionCount = sessions.size,
            completionPercent = if (doneSessions.isEmpty()) 0 else finishedCount * 100 / doneSessions.size,
            averageSessionMs = if (doneSessions.isEmpty()) 0L else doneSessions.sumOf { it.focusMs } / doneSessions.size,
            longestSessionMs = doneSessions.maxOfOrNull { it.focusMs } ?: 0L,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            lastSevenDays = (6 downTo 0).map { offset ->
                val day = today.minusDays(offset.toLong())
                DayTotal(
                    day,
                    FocusGoalProgress.durationWithinDay(rows, startOf(day, zone), startOf(day.plusDays(1), zone), now)
                )
            },
            partsOfDay = partsOfDay(rangeRanges, zone),
            pomodoroCount = pomodoros.size,
            pomodoroRounds = pomodoros.sumOf { it.rounds },
            pomodoroFocusMs = pomodoros.sumOf { it.focusMs },
            groups = sessions.groupBy { it.groupId }
                .map { (groupId, members) -> GroupShare(groupId, members.sumOf { it.focusMs }, members.size) }
                .sortedByDescending { it.focusMs },
            recentSessions = sessions.sortedByDescending { it.startMs }.take(RECENT_SESSION_COUNT)
        )
    }

    private fun toSessions(rows: List<FocusStatsEntity>, now: Long): List<Session> {
        val (pomodoroRows, plainRows) = rows.partition { it.pomodoroCycleId != null }
        val units = plainRows.map { listOf(it) } +
            pomodoroRows.groupBy { it.pomodoroCycleId }.values
        return units.map { members ->
            Session(
                groupId = members.first().groupId,
                startMs = members.minOf { it.startTimeInMillis },
                focusMs = members.sumOf {
                    (FocusGoalProgress.effectiveEnd(it, now) - it.startTimeInMillis).coerceAtLeast(0L)
                },
                rounds = members.size,
                isPomodoro = members.first().pomodoroCycleId != null,
                endedEarly = members.any { it.status == FocusStatsEntity.STATUS_STOPPED_EARLY },
                isRunning = members.any {
                    it.status == FocusStatsEntity.STATUS_RUNNING && it.estimatedEndTimeInMillis > now
                }
            )
        }
    }

    /** Days in a row with any focus, counting a streak as alive until today ends. */
    private fun streaks(rows: List<FocusStatsEntity>, today: LocalDate, now: Long, zone: ZoneId): Pair<Int, Int> {
        val focusDays = mutableSetOf<LocalDate>()
        for (row in rows) {
            val end = FocusGoalProgress.effectiveEnd(row, now)
            if (end <= row.startTimeInMillis) continue
            var day = Instant.ofEpochMilli(row.startTimeInMillis).atZone(zone).toLocalDate()
            val lastDay = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
            while (!day.isAfter(lastDay)) {
                focusDays.add(day)
                day = day.plusDays(1)
            }
        }

        var current = 0
        var day = if (today in focusDays) today else today.minusDays(1)
        while (day in focusDays) {
            current++
            day = day.minusDays(1)
        }

        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (focusDay in focusDays.sorted()) {
            run = if (previous != null && focusDay == previous.plusDays(1)) run + 1 else 1
            best = maxOf(best, run)
            previous = focusDay
        }
        return current to best
    }

    private fun partsOfDay(ranges: List<Pair<Long, Long>>, zone: ZoneId): List<Long> {
        val totals = LongArray(PART_OF_DAY_START_HOURS.size)
        for ((start, end) in ranges) {
            var cursor = start
            while (cursor < end) {
                val time = Instant.ofEpochMilli(cursor).atZone(zone)
                val nextHour = time.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val sliceEnd = minOf(nextHour, end)
                val part = PART_OF_DAY_START_HOURS.indexOfLast { time.hour >= it }
                totals[part] += sliceEnd - cursor
                cursor = sliceEnd
            }
        }
        return totals.toList()
    }

    private fun startOf(day: LocalDate, zone: ZoneId): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli()
}
