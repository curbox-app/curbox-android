package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: FocusStatsEntity)

    @Update
    suspend fun update(stats: FocusStatsEntity)

    @Delete
    suspend fun delete(stats: FocusStatsEntity)

    @Query("SELECT * FROM focus_stats WHERE status = 0")
    suspend fun getRunningSessions(): List<FocusStatsEntity>

    @Query("SELECT * FROM focus_stats WHERE id = :id")
    suspend fun getSessionById(id: String): FocusStatsEntity?

    @Query("SELECT * FROM focus_stats ORDER BY startTimeInMillis DESC")
    fun getAllSessionsFlow(): Flow<List<FocusStatsEntity>>

    @Query(
        """SELECT * FROM focus_stats
            WHERE groupId = :groupId
            AND startTimeInMillis < :dayEnd
            AND (status = 0 OR actualEndTimeInMillis > :dayStart)"""
    )
    suspend fun getSessionsOverlappingDay(
        groupId: String,
        dayStart: Long,
        dayEnd: Long
    ): List<FocusStatsEntity>
}
