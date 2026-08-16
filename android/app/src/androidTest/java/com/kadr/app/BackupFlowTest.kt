package com.kadr.app

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadr.app.data.local.AssetState
import com.kadr.app.data.local.KadrDatabase
import com.kadr.app.data.media.MediaStoreScanner
import com.kadr.app.data.media.Sha256Hasher
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.ApiProvider
import com.kadr.app.data.remote.CheckRequest
import com.kadr.app.data.remote.CreateUploadRequest
import com.kadr.app.data.remote.apiCall
import com.kadr.app.data.repo.BackupRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * §14 M2 acceptance, driven on a real device instead of by hand:
 * MediaStore populates Room, and one file reaches the server intact.
 *
 * The emulator cannot reach the host reliably on 10.0.2.2, so tunnel first:
 *
 *   adb reverse tcp:8787 tcp:8787
 *   adb shell am instrument -w \
 *     -e kadrServerUrl http://127.0.0.1:8787 -e kadrUser tester -e kadrPassword <password> \
 *     -e class com.kadr.app.BackupFlowTest \
 *     com.kadr.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class BackupFlowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val resolver get() = context.contentResolver

    private val serverUrl: String
        get() = arguments.getString("kadrServerUrl") ?: "http://10.0.2.2:8787"

    private val username: String
        get() = arguments.getString("kadrUser") ?: "tester"

    private val password: String
        get() = requireNotNull(arguments.getString("kadrPassword")) {
            "Pass -e kadrPassword <password>"
        }

    private lateinit var database: KadrDatabase
    private lateinit var settings: SettingsStore
    private lateinit var apiProvider: ApiProvider
    private lateinit var repository: BackupRepository
    private val seeded = mutableListOf<Uri>()

    /** The installed app shares this settings file — leave it as we found it. */
    private lateinit var savedSettings: com.kadr.app.data.prefs.KadrSettings

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, KadrDatabase::class.java).build()
        settings = SettingsStore(context)
        savedSettings = settings.current
        settings.clearPairing()
        apiProvider = ApiProvider(settings, json)
        repository = BackupRepository(
            context = context,
            dao = database.assets(),
            scanner = MediaStoreScanner(context),
            hasher = Sha256Hasher(context),
            apiProvider = apiProvider,
            settings = settings,
            json = json,
        )
    }

    @After
    fun tearDown() {
        seeded.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        database.close()
    }

    @Test
    fun signs_in_indexes_mediastore_and_uploads_one_file_intact() = runBlocking {
        val stamp = System.currentTimeMillis()
        val bigName = "kadr_big_$stamp.jpg"
        seeded += TestMedia.seedJpeg(resolver, "kadr_small_$stamp.jpg", 640, 480)
        // Deliberately over 4 MB so the upload has to take the chunked path.
        seeded += TestMedia.seedJpeg(resolver, bigName, 2048, 1536, padTo = 5L * 1024 * 1024)

        // ── Sign in ─────────────────────────────────────────────────────────
        val health = repository.health(serverUrl).getOrThrow()
        assertTrue("Server should report a version", health.version.isNotBlank())

        repository.login(serverUrl, username, password).getOrThrow()
        assertTrue("A token should be stored after signing in", settings.current.isPaired)

        // ── Scan (§10.1) ────────────────────────────────────────────────────
        val scan = repository.scan().getOrThrow()
        assertTrue("The seeded images should be indexed", scan.added >= 2)
        assertEquals("Every scanned file should land in Room", scan.total, database.assets().count())

        // A second scan must be a no-op: the identity key has not moved.
        val rescan = repository.scan().getOrThrow()
        assertEquals("Re-scanning must not duplicate rows", 0, rescan.added)
        assertEquals("Re-scanning must not churn rows", 0, rescan.changed)

        // ── Upload the large file, so chunking is exercised (§10.4) ─────────
        val indexed = database.assets().observeAll().first()
        val target = requireNotNull(indexed.firstOrNull { it.filename == bigName }) {
            "Seeded file $bigName never made it into the index"
        }
        assertTrue("Test file should exceed one 4 MB chunk", target.sizeBytes > 4L * 1024 * 1024)

        val remoteId = repository.upload(target.id).getOrThrow()
        assertTrue("Server should hand back an asset id", remoteId.isNotBlank())

        val uploaded = requireNotNull(database.assets().findById(target.id))
        assertEquals(AssetState.VERIFIED, uploaded.state)
        assertEquals(remoteId, uploaded.remoteId)
        assertNotNull("The hash should be cached after upload", uploaded.sha256)

        // ── The server really has those exact bytes ─────────────────────────
        val api = apiProvider.api()
        val missing = apiCall(json) { api.check(CheckRequest(listOf(uploaded.sha256!!))) }.data.missing
        assertFalse("Server should no longer report the hash as missing", uploaded.sha256 in missing)

        assertEquals(
            "Round-tripped bytes must hash identically",
            uploaded.sha256,
            downloadAndHash(remoteId),
        )

        // ── Re-uploading must send nothing (§15) ────────────────────────────
        val duplicate = apiCall(json) {
            api.createUpload(
                CreateUploadRequest(
                    sha256 = uploaded.sha256!!,
                    sizeBytes = uploaded.sizeBytes,
                    filename = uploaded.filename,
                    mimeType = uploaded.mimeType,
                ),
            )
        }.data
        assertTrue("A known hash must short-circuit", duplicate.alreadyExists)
        assertEquals(remoteId, duplicate.assetId)
    }

    private fun downloadAndHash(assetId: String): String {
        val request = Request.Builder()
            .url("${ApiProvider.normalize(serverUrl)}api/v1/assets/$assetId/file")
            .header("Authorization", "Bearer ${settings.current.token}")
            .build()

        OkHttpClient().newCall(request).execute().use { response ->
            assertTrue("Download failed: HTTP ${response.code}", response.isSuccessful)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            requireNotNull(response.body).byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
