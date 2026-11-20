package neth.iecal.curbox.utils

import neth.iecal.curbox.data.db.FocusStatsEntity

object FocusGoalProgress {
    fun durationWithinDay(
        sessions: List<FocusStatsEntity>,
        dayStart: Long,
        dayEnd: Long,
        now: Long
    ): Long = focusRanges(sessions, dayStart, dayEnd, now).sumOf { it.second - it.first }

    /** The time between [from] and [to] spent focusing, with overlapping sessions merged. */
    fun focusRanges(
        sessions: List<FocusStatsEntity>,
        from: Long,
        to: Long,
        now: Long
    ): List<Pair<Long, Long>> {
        val ranges = sessions.mapNotNull { session ->
            val clippedStart = maxOf(session.startTimeInMillis, from)
            val clippedEnd = minOf(effectiveEnd(session, now), to)
            if (clippedEnd > clippedStart) clippedStart to clippedEnd else null
        }.sortedBy { it.first }

        val merged = mutableListOf<Pair<Long, Long>>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    fun effectiveEnd(session: FocusStatsEntity, now: Long): Long =
        if (session.status == FocusStatsEntity.STATUS_RUNNING) {
            minOf(now, session.estimatedEndTimeInMillis)
        } else {
            session.actualEndTimeInMillis
        }
}
