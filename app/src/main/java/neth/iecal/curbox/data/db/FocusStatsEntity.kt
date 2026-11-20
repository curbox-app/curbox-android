package neth.iecal.curbox.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "focus_stats")
data class FocusStatsEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val groupId: String,
    val startTimeInMillis: Long,
    val estimatedEndTimeInMillis: Long,
    val actualEndTimeInMillis: Long,
    val status: Int, // 0=RUNNING, 1=COMPLETED, 2=FORCE_STOPPED
    // Shared by every focus round of one Pomodoro. Null for plain sessions and older rows.
    val pomodoroCycleId: String? = null
) {
    /** Closes a running session that the user ended at [now], which may be before it was due. */
    fun stoppedAt(now: Long): FocusStatsEntity = if (now >= estimatedEndTimeInMillis - FINISH_GRACE_MS) {
        copy(status = STATUS_COMPLETED, actualEndTimeInMillis = estimatedEndTimeInMillis)
    } else {
        copy(status = STATUS_STOPPED_EARLY, actualEndTimeInMillis = now)
    }

    companion object {
        const val STATUS_RUNNING = 0
        const val STATUS_COMPLETED = 1
        const val STATUS_STOPPED_EARLY = 2

        // Timers and companion devices can be a few seconds apart, which is not ending early.
        private const val FINISH_GRACE_MS = 5_000L
    }
}
