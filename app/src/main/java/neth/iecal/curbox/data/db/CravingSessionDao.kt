package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CravingSessionDao {
    @Insert
    suspend fun insert(session: CravingSessionEntity)

    @Query("SELECT * FROM craving_sessions ORDER BY timestamp DESC")
    fun getAll(): Flow<List<CravingSessionEntity>>

    @Query("SELECT * FROM craving_sessions ORDER BY timestamp DESC")
    suspend fun getAllSync(): List<CravingSessionEntity>

    @Query("DELETE FROM craving_sessions")
    suspend fun deleteAll()
}
