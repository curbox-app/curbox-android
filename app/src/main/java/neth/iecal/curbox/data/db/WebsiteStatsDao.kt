package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WebsiteStatsDao {

    @Query("SELECT * FROM website_stats WHERE date = :date")
    suspend fun getStatsForDate(date: String): List<WebsiteStatsEntity>

    @Query("SELECT * FROM website_stats WHERE date = :date")
    fun observeStatsForDate(date: String): Flow<List<WebsiteStatsEntity>>


    @Query("SELECT * FROM website_stats WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun getStat(date: String, packageName: String, urlIdentifier: String): WebsiteStatsEntity?

    @Query("SELECT * FROM website_stats WHERE date = :date AND packageName = :packageName")
    suspend fun getStatsForPackage(date: String, packageName: String): List<WebsiteStatsEntity>

    @Upsert
    suspend fun upsert(entity: WebsiteStatsEntity)

    // Creates the row only if it does not exist yet, preserving any totalTime
    // already accumulated. Used to make sure a row is present before adding time.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: WebsiteStatsEntity)

    // Atomic increment so concurrent commits never clobber each other's additions.
    @Query("UPDATE website_stats SET totalTime = totalTime + :deltaMs, lastVisited = :lastVisited WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun addTime(date: String, packageName: String, urlIdentifier: String, deltaMs: Long, lastVisited: Long)

    @Query("UPDATE website_stats SET lastVisited = :lastVisited WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun touch(date: String, packageName: String, urlIdentifier: String, lastVisited: Long)
}
