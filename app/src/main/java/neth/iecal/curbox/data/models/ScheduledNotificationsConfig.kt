package neth.iecal.curbox.data.models

import java.util.Calendar

/**
 * Notifications are held when they arrive and handed back together at the next delivery time.
 * [deliveryMinutes] are minutes since midnight. [allowedApps] always notify right away.
 */
data class ScheduledNotificationsConfig(
    val isActive: Boolean = false,
    val allowedApps: List<String> = emptyList(),
    val deliveryMinutes: List<Int> = listOf(9 * 60, 13 * 60, 18 * 60)
) {
    companion object {
        const val MAX_DELIVERY_TIMES = 12
    }

    /** The first delivery time after [nowMs], or null when no time is set. */
    fun nextDeliveryAtMs(nowMs: Long): Long? =
        deliveryTimesAround(nowMs).filter { it > nowMs }.minOrNull()

    /** The latest delivery time at or before [nowMs], or null when no time is set. */
    fun lastDeliveryAtMs(nowMs: Long): Long? =
        deliveryTimesAround(nowMs).filter { it <= nowMs }.maxOrNull()

    private fun deliveryTimesAround(nowMs: Long): List<Long> =
        (-1..1).flatMap { dayOffset ->
            deliveryMinutes.filter { it in 0 until 24 * 60 }.map { minutes ->
                Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, minutes / 60)
                    set(Calendar.MINUTE, minutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
        }
}
