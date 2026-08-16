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
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.ApiProvider
import com.kadr.app.data.repo.BackupRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §17: "Write the upload state machine with unit tests that simulate: network
 * drop mid-chunk, server restart, hash mismatch, duplicate file, disk full.
 * These five cases are where this category of app usually fails."
 *
 * A scripted MockWebServer stands in for Kadr. Responses are chosen by **path**,
 * not by queue position, because OkHttp retries connection failures on its own —
 * a fixed queue gets consumed at an unpredictable rate and the test becomes a
 * coin flip. These run on a device because the upload path reads real bytes
 * through a real ContentResolver.
 */
@RunWith(AndroidJUnit4::class)
class UploadFailureTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private lateinit var server: MockWebServer
    private lateinit var script: KadrScript
    private lateinit var database: KadrDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repository: BackupRepository

    private lateinit var mediaUri: Uri
    private var assetId: Long = 0
    private var fileSize: Long = 0

    /**
     * SettingsStore is backed by one real EncryptedSharedPreferences file, so
     * pointing it at a mock server would otherwise leave the installed app
     * paired to a dead port. Put back exactly what was there.
     */
    private lateinit var savedSettings: com.kadr.app.data.prefs.KadrSettings

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, KadrDatabase::class.java).build()
        val hasher = Sha256Hasher(context)

        // One small file, so a single chunk covers it and the scripts stay short.
        mediaUri = TestMedia.seedJpeg(resolver, "kadr_fail_${System.nanoTime()}.jpg", 320, 240)
        fileSize = requireNotNull(resolver.openFileDescriptor(mediaUri, "r")).use { it.statSize }
        val sha = hasher.hash(mediaUri)

        script = KadrScript(sha, fileSize)
        server = MockWebServer().apply {
            dispatcher = script
            start()
        }

        settings = SettingsStore(context)
        savedSettings = settings.current
        settings.savePairing(server.url("/").toString(), "test-device", "test-token")

        repository = BackupRepository(
            context = context,
            dao = database.assets(),
            scanner = MediaStoreScanner(context),
            hasher = hasher,
            apiProvider = ApiProvider(settings, json),
            settings = settings,
            json = json,
        )

        assetId = database.assets().insert(
            LocalAsset(
                mediaStoreId = System.nanoTime(),
                contentUri = mediaUri.toString(),
                relativePath = "Pictures/KadrTest/",
                filename = "kadr_fail.jpg",
                sizeBytes = fileSize,
                dateModified = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis(),
                mimeType = "image/jpeg",
                durationMs = null,
                width = 320,
                height = 240,
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
        settings.savePairing(
            savedSettings.serverUrl,
            savedSettings.deviceId,
            savedSettings.token,
        )
        database.close()
        server.shutdown()
    }

    // ── 1. Network drop mid-chunk ───────────────────────────────────────────

    @Test
    fun a_connection_dropped_mid_chunk_is_retried_and_succeeds() = runBlocking {
        // The socket dies with the chunk in flight and no reply ever comes.
        // DISCONNECT_AFTER_REQUEST rather than DISCONNECT_DURING_REQUEST_BODY:
        // a small body is written in one go, so "during" is a race that
        // sometimes lets a bodiless 200 through instead of dropping.
        script.failFirstChunks(
            count = 1,
            response = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )
        script.assetId = "asset-dropped"

        val result = repository.upload(assetId)

        assertTrue(
            "A dropped chunk should be retried, not surfaced: ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val asset = requireNotNull(database.assets().findById(assetId))
        assertEquals(AssetState.VERIFIED, asset.state)
        assertEquals("asset-dropped", asset.remoteId)
        assertTrue("The chunk should have been sent more than once", script.patchCount > 1)
    }

    // ── 2. Server restart mid-upload ────────────────────────────────────────

    @Test
    fun b_a_restarted_server_resets_the_session_and_the_upload_starts_over() = runBlocking {
        // The partial file did not survive the restart.
        script.failFirstChunks(
            count = 1,
            response = errorResponse(409, "SESSION_RESET", "Partial file was lost.", receivedBytes = 0),
        )
        script.assetId = "asset-restarted"

        val result = repository.upload(assetId)

        assertTrue(
            "A session reset is an instruction, not a failure: ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertEquals(AssetState.VERIFIED, requireNotNull(database.assets().findById(assetId)).state)
    }

    @Test
    fun c_a_range_gap_resumes_from_where_the_server_actually_is() = runBlocking {
        script.failFirstChunks(
            count = 1,
            response = errorResponse(409, "RANGE_GAP", "Only 0 bytes are held.", receivedBytes = 0),
        )
        script.assetId = "asset-gap"

        val result = repository.upload(assetId)

        assertTrue("A range gap is recoverable: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(AssetState.VERIFIED, requireNotNull(database.assets().findById(assetId)).state)
    }

    // ── 3. Hash mismatch ────────────────────────────────────────────────────

    @Test
    fun d_a_hash_mismatch_fails_the_row_and_discards_the_cached_digest() = runBlocking {
        script.completeOverride =
            errorResponse(409, "HASH_MISMATCH", "Assembled file hashes differently.", receivedBytes = 0)

        val result = repository.upload(assetId)

        assertTrue("A hash mismatch must not be swallowed", result.isFailure)
        val asset = requireNotNull(database.assets().findById(assetId))
        assertEquals(AssetState.FAILED, asset.state)
        assertNull("The stale digest must be dropped so the retry recomputes it", asset.sha256)
        assertEquals(1, asset.attemptCount)
        assertNotNull("The error has to reach the UI", asset.lastError)
        assertEquals("A mismatch is final, not retried", 1, script.completeCount)
    }

    // ── 4. Duplicate file ───────────────────────────────────────────────────

    @Test
    fun e_a_duplicate_short_circuits_without_sending_a_single_byte() = runBlocking {
        script.serverAlreadyHasIt = true
        script.duplicateAssetId = "asset-dupe"

        val result = repository.upload(assetId)

        assertTrue(result.isSuccess)
        val asset = requireNotNull(database.assets().findById(assetId))
        assertEquals(AssetState.VERIFIED, asset.state)
        assertEquals("asset-dupe", asset.remoteId)
        assertEquals("Not one byte of the file should have been sent", 0, script.patchCount)
    }

    // ── 5. Disk full ────────────────────────────────────────────────────────

    @Test
    fun f_a_full_server_disk_fails_fast_instead_of_hammering_it() = runBlocking {
        script.chunkAlways = errorResponse(507, "DISK_FULL", "The server has run out of disk space.")

        val result = repository.upload(assetId)

        assertTrue(result.isFailure)
        assertEquals("A full disk is not worth retrying", 1, script.patchCount)

        val asset = requireNotNull(database.assets().findById(assetId))
        assertTrue(
            "The user must be told the disk is full, got: ${asset.lastError}",
            asset.lastError?.contains("disk", ignoreCase = true) == true,
        )

        // §16: a full server is an ending, not this photo's fault. Burning one
        // of its six attempts would strand it FAILED after a few runs against a
        // full disk, even once room is made.
        assertEquals("The row must stay ready to send", AssetState.CHECKED, asset.state)
        assertEquals("A full disk must not cost the file an attempt", 0, asset.attemptCount)
        assertTrue(
            "The file has to still be queued once there is room",
            database.assets()
                .pendingBatch(BackupRepository.MAX_ATTEMPTS, limit = 10)
                .any { it.id == assetId },
        )
    }

    @Test
    fun g_a_full_server_is_refused_up_front_and_says_how_much_room_is_left() = runBlocking {
        // The real server checks free space when the session is opened, before
        // a single byte is sent (server/src/routes/uploads.js).
        script.sessionOverride = errorResponse(
            507,
            "DISK_FULL",
            "The server has run out of disk space.",
            extraFields = ""","freeBytes":1048576,"requiredBytes":4194304""",
        )

        val result = repository.upload(assetId)

        assertTrue(result.isFailure)
        assertEquals("Nothing should be sent to a server with no room", 0, script.patchCount)

        val full = requireNotNull(repository.serverFull.value) {
            "The timeline banner has nothing to show without this"
        }
        assertEquals(1_048_576L, full.freeBytes)
        assertEquals(4_194_304L, full.requiredBytes)
        assertEquals(
            "The row must stay ready to send",
            AssetState.CHECKED,
            requireNotNull(database.assets().findById(assetId)).state,
        )
    }

    private fun errorResponse(
        code: Int,
        errorCode: String,
        message: String,
        receivedBytes: Long? = null,
        extraFields: String = "",
    ): MockResponse {
        val extra = (receivedBytes?.let { ""","receivedBytes":$it""" } ?: "") + extraFields
        return MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"code":"$errorCode","message":"$message"$extra}}""")
    }
}

/**
 * A stand-in Kadr server. Every knob is a way the real one can misbehave.
 */
private class KadrScript(private val sha: String, private val fileSize: Long) : Dispatcher() {

    var serverAlreadyHasIt = false
    var duplicateAssetId: String? = null
    var assetId = "asset-ok"
    var completeOverride: MockResponse? = null

    /** Refuses to open a session at all — a full disk, caught before any bytes. */
    var sessionOverride: MockResponse? = null

    /** Applies to every chunk, for conditions that do not clear up. */
    var chunkAlways: MockResponse? = null

    private var chunkFailuresLeft = 0
    private var chunkFailure: MockResponse? = null

    var patchCount = 0
        private set
    var completeCount = 0
        private set

    fun failFirstChunks(count: Int, response: MockResponse) {
        chunkFailuresLeft = count
        chunkFailure = response
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            path.endsWith("/assets/check") -> json(
                200,
                if (serverAlreadyHasIt) """{"data":{"missing":[]}}"""
                else """{"data":{"missing":["$sha"]}}""",
            )

            request.method == "POST" && path.endsWith("/api/v1/uploads") ->
                sessionOverride ?: duplicateAssetId?.let {
                    json(200, """{"data":{"alreadyExists":true,"assetId":"$it"}}""")
                } ?: json(201, """{"data":{"uploadId":"upload-1","receivedBytes":0}}""")

            request.method == "PATCH" -> {
                patchCount++
                chunkAlways ?: takeChunkFailure() ?: json(
                    200,
                    """{"data":{"receivedBytes":$fileSize}}""",
                )
            }

            path.endsWith("/complete") -> {
                completeCount++
                completeOverride ?: json(200, """{"data":{"assetId":"$assetId"}}""")
            }

            else -> MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND"}}""")
        }
    }

    private fun takeChunkFailure(): MockResponse? {
        if (chunkFailuresLeft <= 0) return null
        chunkFailuresLeft--
        return chunkFailure
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
