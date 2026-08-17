package com.kadr.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocalAsset::class, RemoteAsset::class],
    views = [GalleryItem::class],
    version = 3,
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

        /**
         * v3 turns the merged timeline into a view, so the paged reader, the
         * header count and the viewer's position lookup all describe the same
         * library instead of three copies of one query drifting apart.
         *
         * No table changes and no data moves — a view is only a saved question.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP VIEW IF EXISTS `timeline_items`")
                // `trim()` is not cosmetic: Room compares the statement it finds
                // in sqlite_master against the one it builds from the annotation,
                // character for character, and it trims the annotation value
                // first. A stray leading newline here fails to open the database.
                db.execSQL("CREATE VIEW `timeline_items` AS ${TIMELINE_VIEW_SQL.trim()}")
            }
        }
    }
}
