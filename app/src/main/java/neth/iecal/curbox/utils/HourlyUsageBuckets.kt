package neth.iecal.curbox.utils

object HourlyUsageBuckets {
    /**
     * Fraction of an hourly bucket's usage to count for [startMs, endMs]. Usage can only have been
     * recorded up to [nowMs], so a bucket for the current hour is spread over its elapsed part
     * rather than the full hour. Otherwise usage from the last few minutes would be scaled down.
     */
    fun shareInWindow(
        bucketStart: Long,
        bucketEnd: Long,
        startMs: Long,
        endMs: Long,
        nowMs: Long
    ): Double {
        val recordedEnd = minOf(bucketEnd, nowMs)
        if (recordedEnd <= bucketStart) return 0.0
        val overlap = minOf(endMs, recordedEnd) - maxOf(startMs, bucketStart)
        if (overlap <= 0L) return 0.0
        return overlap.toDouble() / (recordedEnd - bucketStart)
    }
}
