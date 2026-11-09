package neth.iecal.curbox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReelStatsEntity::class, ReelUsageStatsEntity::class, ScrollPatternEntity::class, FocusStatsEntity::class, WebsiteStatsEntity::class, IntentLogEntity::class, AppUsageEntity::class, CravingSessionEntity::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reelStatsDao(): ReelStatsDao
    abstract fun reelUsageStatsDao(): ReelUsageStatsDao
    abstract fun scrollPatternDao(): ScrollPatternDao
    abstract fun focusStatsDao(): FocusStatsDao
    abstract fun websiteStatsDao(): WebsiteStatsDao
    abstract fun intentLogDao(): IntentLogDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun cravingSessionDao(): CravingSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE website_stats ADD COLUMN hourlyUsage BLOB NOT NULL DEFAULT X''"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `reel_usage_stats` (
                        `date` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `totalTime` INTEGER NOT NULL,
                        `reelCount` INTEGER NOT NULL,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`date`, `packageName`)
                    )""".trimIndent()
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `craving_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `ratingBefore` INTEGER NOT NULL,
                        `ratingAfter` INTEGER NOT NULL,
                        `activity` TEXT NOT NULL
                    )""".trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "curbox_db"
                ).addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .enableMultiInstanceInvalidation()
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
