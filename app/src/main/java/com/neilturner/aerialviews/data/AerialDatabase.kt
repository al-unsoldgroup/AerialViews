package com.neilturner.aerialviews.data

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(
    entities = [
        CachedMediaEntity::class,
        CachedMusicTrackEntity::class,
        PlaylistStateEntity::class,
        PrivateCacheVariant::class,
        PrivateReconciliationJournal::class,
        PrivateReconciliationCopy::class,
        PrivateCacheGeneration::class,
    ],
    // Private cache inventory and recovery state preserve existing playlists through migration.
    version = 14,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(
            from = 1,
            to = 2,
        ), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14),
    ],
)
@ColumnTypeConverters(Converters::class)
abstract class AerialDatabase : RoomDatabase() {
    abstract fun playlistCacheDao(): PlaylistCacheDao

    abstract fun privateCacheVariantDao(): PrivateCacheVariantDao

    abstract fun privateReconciliationDao(): PrivateReconciliationDao

    companion object {
        private const val DATABASE_NAME = "aerial_database"

        @Volatile
        private var db: AerialDatabase? = null

        fun getInstance(context: Context): AerialDatabase {
            val existing = db
            if (existing != null) return existing

            return synchronized(this) {
                val rechecked = db
                if (rechecked != null) {
                    rechecked
                } else {
                    val created =
                        Room
                            .databaseBuilder(
                                context.applicationContext,
                                AerialDatabase::class.java,
                                DATABASE_NAME,
                            ).fallbackToDestructiveMigration(dropAllTables = true)
                            .setDriver(AndroidSQLiteDriver())
                            .build()
                    db = created
                    created
                }
            }
        }

        fun closeAndReset() {
            synchronized(this) {
                db?.close()
                db = null
            }
        }

        fun dbName(): String = DATABASE_NAME
    }
}
