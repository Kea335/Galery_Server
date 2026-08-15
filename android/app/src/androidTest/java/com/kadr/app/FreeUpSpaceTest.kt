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
 *     -e kadrServerUrl http://127.0.0.1:8787 -e kadrPairCode 123456 \
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

    private val pairCode: String
        get() = requireNotNull(arguments.getString("kadrPairCode")) { "Pass -e kadrPairCode" }

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

        // A pairing code is single use, so pair once for the whole class and
        // hand the token to the tests that follow.
        if (original == null) original = savedSettings
        if (!alreadyPaired) {
            backup.pair(serverUrl, pairCode).getOrThrow()
            alreadyPaired = true
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
        private var alreadyPaired = false

        /** Put the installed app's own pairing back once the class is done. */
        @AfterClass
        @JvmStatic
        fun restoreAppSettings() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            original?.let { saved ->
                SettingsStore(context)
                    .savePairing(saved.serverUrl, saved.deviceId, saved.token)
            }
            original = null
            alreadyPaired = false
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
}
