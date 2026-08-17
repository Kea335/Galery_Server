package com.kadr.app

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadr.app.data.local.AssetState
import com.kadr.app.data.local.KadrDatabase
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.media.MediaStoreScanner
import com.kadr.app.data.media.Sha256Hasher
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.ApiProvider
import com.kadr.app.data.repo.BackupRepository
import com.kadr.app.data.repo.SpaceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §10.7 is the most dangerous code in the app: it deletes the user's photos.
 *
 * The rule it must never break is that a file only goes when the server has
 * just confirmed, in this moment, that it holds that exact hash. A row marked
 * VERIFIED at some point in the past is not enough — the server could have been
 * restored from an older backup, or the row could be stale.
 *
 *   adb reverse tcp:8787 tcp:8787
 *   adb shell am instrument -w \
 *     -e kadrServerUrl http://127.0.0.1:8787 -e kadrPassword 123456 \
 *     -e class com.kadr.app.FreeUpSpaceTest \
 *     com.kadr.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FreeUpSpaceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val arguments = InstrumentationRegistry.getArguments()

    private val serverUrl: String
        get() = arguments.getString("kadrServerUrl") ?: "http://127.0.0.1:8787"

    private val username: String
        get() = arguments.getString("kadrUser") ?: "tester"

    private val password: String
        get() = requireNotNull(arguments.getString("kadrPassword")) { "Pass -e kadrPassword" }

    private lateinit var database: KadrDatabase
    private lateinit var settings: SettingsStore
    private lateinit var savedSettings: KadrSettings
    private lateinit var backup: BackupRepository
    private lateinit var space: SpaceRepository

    private lateinit var mediaUri: Uri
    private var rowId: Long = 0

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, KadrDatabase::class.java).build()
        settings = SettingsStore(context)
        savedSettings = settings.current

        val apiProvider = ApiProvider(settings, json)
        backup = BackupRepository(
            context = context,
            dao = database.assets(),
            scanner = MediaStoreScanner(context),
            hasher = Sha256Hasher(context),
            apiProvider = apiProvider,
            settings = settings,
            json = json,
        )
        space = SpaceRepository(context, database.assets(), apiProvider, json)

        // Signing in once per class keeps the device list from filling up with
        // a new entry for every test.
        if (original == null) original = savedSettings
        if (!alreadySignedIn) {
            backup.login(serverUrl, username, password).getOrThrow()
            alreadySignedIn = true
        }

        mediaUri = TestMedia.seedJpeg(resolver, "kadr_free_${System.nanoTime()}.jpg", 480, 360)
        val size = requireNotNull(resolver.openFileDescriptor(mediaUri, "r")).use { it.statSize }

        rowId = database.assets().insert(
            LocalAsset(
                mediaStoreId = System.nanoTime(),
                contentUri = mediaUri.toString(),
                relativePath = "Pictures/KadrTest/",
                filename = "kadr_free.jpg",
                sizeBytes = size,
                dateModified = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis(),
                mimeType = "image/jpeg",
                durationMs = null,
                width = 480,
                height = 360,
                orientation = 0,
                sha256 = null,
                state = AssetState.DISCOVERED,
                remoteId = null,
            ),
        )
    }

    @After
    fun tearDown() {
        runCatching { resolver.delete(mediaUri, null, null) }
        database.close()
    }

    companion object {
        private var original: KadrSettings? = null
        private var alreadySignedIn = false

        /** Put the installed app's own session back once the class is done. */
        @AfterClass
        @JvmStatic
        fun restoreAppSettings() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            original?.let { saved ->
                SettingsStore(context)
                    .savePairing(saved.serverUrl, saved.deviceId, saved.token)
            }
            original = null
            alreadySignedIn = false
        }
    }

    @Test
    fun a_a_verified_row_the_server_cannot_vouch_for_is_withheld() = runBlocking {
        // The row claims to be safe, and its hash is real — but nothing was ever
        // uploaded, so the server has never seen it.
        val digest = Sha256Hasher(context).hash(mediaUri)
        val asset = requireNotNull(database.assets().findById(rowId))
        database.assets().update(
            asset.copy(sha256 = digest, state = AssetState.VERIFIED, remoteId = "made-up"),
        )

        val plan = space.plan().getOrThrow()

        assertTrue("A file the server does not have must never be deleted", plan.isEmpty)
        assertEquals("It should be reported as withheld, not silently dropped", 1, plan.withheld)
        assertEquals(0L, plan.totalBytes)
    }

    @Test
    fun b_a_genuinely_uploaded_file_becomes_eligible() = runBlocking {
        backup.upload(rowId).getOrThrow()

        val stored = requireNotNull(database.assets().findById(rowId))
        assertEquals(AssetState.VERIFIED, stored.state)

        val plan = space.plan().getOrThrow()

        assertFalse("An uploaded file should be freeable", plan.isEmpty)
        assertEquals(1, plan.assets.size)
        assertEquals(0, plan.withheld)
        assertEquals(stored.sizeBytes, plan.totalBytes)
        assertEquals(stored.id, plan.assets.first().id)
    }

    @Test
    fun c_nothing_is_marked_freed_while_the_file_is_still_there() = runBlocking {
        backup.upload(rowId).getOrThrow()
        val plan = space.plan().getOrThrow()

        // markFreed runs after the system dialog, whatever the user chose there.
        // The file is untouched, so the row must stay VERIFIED.
        val freed = space.markFreed(plan)

        assertEquals("A file still on disk was reported as freed", 0, freed)
        assertEquals(
            AssetState.VERIFIED,
            requireNotNull(database.assets().findById(rowId)).state,
        )
    }

    // ── §12 selection mode: the same rules, narrowed to what was picked ─────

    @Test
    fun d_a_selection_only_frees_what_was_actually_picked() = runBlocking {
        backup.upload(rowId).getOrThrow()

        val picked = space.plan(listOf(rowId)).getOrThrow()
        assertEquals("The picked photo should be freeable", 1, picked.assets.size)
        assertEquals(rowId, picked.assets.first().id)

        // A row id nobody picked must not ride along.
        val other = space.plan(listOf(rowId + 9_999)).getOrThrow()
        assertTrue("Only what the user picked may be planned", other.isEmpty)
    }

    @Test
    fun e_a_selection_still_asks_the_server_before_offering_anything() = runBlocking {
        // Verified on paper, never uploaded — exactly the case rule 2 exists for.
        val digest = Sha256Hasher(context).hash(mediaUri)
        val asset = requireNotNull(database.assets().findById(rowId))
        database.assets().update(
            asset.copy(sha256 = digest, state = AssetState.VERIFIED, remoteId = "made-up"),
        )

        val plan = space.plan(listOf(rowId)).getOrThrow()

        assertTrue("Picking a photo must not bypass the server check", plan.isEmpty)
        assertEquals(1, plan.withheld)
    }

    @Test
    fun f_an_empty_selection_plans_nothing_and_asks_no_one() = runBlocking {
        backup.upload(rowId).getOrThrow()

        val plan = space.plan(emptyList()).getOrThrow()

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.withheld)
        assertEquals(0L, plan.totalBytes)
    }
}
