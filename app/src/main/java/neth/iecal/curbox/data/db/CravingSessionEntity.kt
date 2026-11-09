package neth.iecal.curbox.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "craving_sessions")
data class CravingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    /** App that was blocked when the user asked for help. Empty when unknown. */
    val packageName: String,
    val ratingBefore: Int,
    val ratingAfter: Int,
    /** One of [ACTIVITY_PUZZLE] or [ACTIVITY_MOVE]. */
    val activity: String
) {
    companion object {
        const val ACTIVITY_PUZZLE = "puzzle"
        const val ACTIVITY_MOVE = "move"
    }
}
