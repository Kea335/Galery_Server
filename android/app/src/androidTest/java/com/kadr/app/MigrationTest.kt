package com.kadr.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadr.app.data.local.KadrDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §14 M7 asks for database migration tests.
 *
 * v1 → v2 adds the mirror of the server library; v2 → v3 adds the timeline
 * view the paged reader is built on; v3 → v4 adds the album mirror. What has to hold across both is that a
 * phone which was backing up under v1 keeps every row it had: an index that
 * survives an app update is the difference between a quiet upgrade and
 * re-uploading someone's whole library.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KadrDatabase::class.java,
    )

    @Test
    fun v1_rows_survive_the_move_to_v2() {
        val mediaStoreId = 987_654L

        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO local_assets (
                    id, mediaStoreId, contentUri, relativePath, filename, sizeBytes,
                    dateModified, capturedAt, mimeType, durationMs, width, height,
                    orientation, sha256, state, remoteId, attemptCount, lastError
                ) VALUES (
                    1, $mediaStoreId, 'content://media/external/images/media/$mediaStoreId',
                    'Pictures/', 'holiday.jpg', 2048, 1700000000000, 1699999999000,
                    'image/jpeg', NULL, 1920, 1080, 0,
                    'abc123', 'VERIFIED', 'server-asset-1', 0, NULL
                )
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME,
            4,
            true,
            KadrDatabase.MIGRATION_1_2,
            KadrDatabase.MIGRATION_2_3,
            KadrDatabase.MIGRATION_3_4,
        )

        migrated.query("SELECT filename, state, remoteId FROM local_assets WHERE id = 1").use { row ->
            assertTrue("The v1 row did not survive the migration", row.moveToFirst())
            assertEquals("holiday.jpg", row.getString(0))
            assertEquals("VERIFIED", row.getString(1))
            assertEquals("server-asset-1", row.getString(2))
        }

        // The new table has to exist and be empty — the server library is
        // fetched fresh, it is not conjured out of the old schema.
        migrated.query("SELECT COUNT(*) FROM remote_assets").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(0, row.getInt(0))
        }

        // v3's view has to be there and has to see the row that came from v1 —
        // the timeline is read entirely through it now.
        migrated.query("SELECT itemKey, filename FROM timeline_items").use { row ->
            assertTrue("The v3 view is missing", row.moveToFirst())
            assertEquals("l1", row.getString(0))
            assertEquals("holiday.jpg", row.getString(1))
            assertEquals("One local row should surface exactly once", 1, row.count)
        }
        // v4's album mirror has to exist and start empty — albums come from the
        // server, they are not conjured out of the old schema either.
        migrated.query("SELECT COUNT(*) FROM remote_albums").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(0, row.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM album_items").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(0, row.getInt(0))
        }
        migrated.close()
    }

    /**
     * Opening the real database on top of the migrated file is what the app
     * actually does on first launch after an update.
     */
    @Test
    fun the_app_can_open_the_migrated_database() {
        helper.createDatabase(DB_NAME, 1).close()
        helper.runMigrationsAndValidate(
            DB_NAME,
            4,
            true,
            KadrDatabase.MIGRATION_1_2,
            KadrDatabase.MIGRATION_2_3,
            KadrDatabase.MIGRATION_3_4,
        ).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, KadrDatabase::class.java, DB_NAME)
            .addMigrations(
                KadrDatabase.MIGRATION_1_2,
                KadrDatabase.MIGRATION_2_3,
                KadrDatabase.MIGRATION_3_4,
            )
            .build()

        try {
            // Touching both DAOs forces Room to open and validate the schema.
            val timelineIsQueryable = runCatching {
                kotlinx.coroutines.runBlocking { database.gallery().remoteCount() }
            }
            assertTrue(
                "Room refused the migrated schema: ${timelineIsQueryable.exceptionOrNull()}",
                timelineIsQueryable.isSuccess,
            )
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
