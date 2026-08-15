package com.kadr.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocalAsset::class, RemoteAsset::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KadrDatabase : RoomDatabase() {
    abstract fun assets(): LocalAssetDao
    abstract fun gallery(): GalleryDao

    companion object {
        /** v2 adds the mirror of the server library that the timeline reads. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_assets` (
                        `id` TEXT NOT NULL,
                        `sha256` TEXT,
                        `sizeBytes` INTEGER NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `filename` TEXT NOT NULL,
                        `capturedAt` INTEGER NOT NULL,
                        `uploadedAt` INTEGER NOT NULL,
                        `width` INTEGER,
                        `height` INTEGER,
                        `durationMs` INTEGER,
                        `orientation` INTEGER NOT NULL,
                        `deleted` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_assets_sha256` ON `remote_assets` (`sha256`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_assets_capturedAt` ON `remote_assets` (`capturedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_assets_updatedAt` ON `remote_assets` (`updatedAt`)")
            }
        }
    }
}
