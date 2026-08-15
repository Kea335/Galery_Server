package com.kadr.app

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import com.kadr.app.data.repo.LibraryRepository
import com.kadr.app.data.video.PlayerFactory
import com.kadr.app.data.video.VideoCache
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * §14 M5: local playback, remote streaming with seek, and the cache.
 *
 * The video is generated on the device (see [TestMedia.seedVideo]) because the
 * emulator ships none, then pushed to the server so the remote path exercises
 * the real `/file` endpoint and its Range support.
 *
 *   adb reverse tcp:8787 tcp:8787
 *   adb shell am instrument -w \
 *     -e kadrServerUrl http://127.0.0.1:8787 -e kadrPairCode 123456 \
 *     -e class com.kadr.app.VideoPlaybackTest \
 *     com.kadr.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val arguments = InstrumentationRegistry.getArguments()

    private val serverUrl: String
        get() = arguments.getString("kadrServerUrl") ?: "http://127.0.0.1:8787"

    private val pairCode: String
        get() = requireNotNull(arguments.getString("kadrPairCode")) {
            "Pass -e kadrPairCode <6 digits>"
        }

    private lateinit var database: KadrDatabase
    private lateinit var settings: SettingsStore
    private lateinit var savedSettings: KadrSettings
    private lateinit var apiProvider: ApiProvider
    private lateinit var backup: BackupRepository
    private lateinit var library: LibraryRepository
    private lateinit var cache: androidx.media3.datasource.cache.SimpleCache
    private lateinit var playerFactory: PlayerFactory

    private lateinit var videoUri: Uri
    private var assetRowId: Long = 0
    private val surfaces = mutableListOf<Pair<SurfaceTexture, Surface>>()

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
        apiProvider = ApiProvider(settings, json)

        // The installed app already holds a SimpleCache on `cache/media`, and
        // SimpleCache allows one instance per folder per process — the test runs
        // in that same process, so it opens its own folder.
        cache = VideoCache.open(context, sizeMb = 64, directoryName = "media-test")
        playerFactory = PlayerFactory(context, apiProvider, cache)

        backup = BackupRepository(
            context = context,
            dao = database.assets(),
            scanner = MediaStoreScanner(context),
            hasher = Sha256Hasher(context),
            apiProvider = apiProvider,
            settings = settings,
            json = json,
        )
        library = LibraryRepository(database.gallery(), apiProvider, settings, json)

        videoUri = TestMedia.seedVideo(resolver, "kadr_clip_${System.nanoTime()}.mp4")

        val size = requireNotNull(resolver.openFileDescriptor(videoUri, "r")).use { it.statSize }
        assertTrue("The encoder produced nothing", size > 1024)

        assetRowId = database.assets().insert(
            LocalAsset(
                mediaStoreId = System.nanoTime(),
                contentUri = videoUri.toString(),
                relativePath = "Movies/KadrTest/",
                filename = "kadr_clip.mp4",
                sizeBytes = size,
                dateModified = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis(),
                mimeType = "video/mp4",
                durationMs = null,
                width = 640,
                height = 480,
                orientation = 0,
                sha256 = null,
                state = AssetState.DISCOVERED,
                remoteId = null,
            ),
        )
    }

    @After
    fun tearDown() {
        runCatching { resolver.delete(videoUri, null, null) }
        if (::settings.isInitialized) {
            settings.savePairing(savedSettings.serverUrl, savedSettings.deviceId, savedSettings.token)
        }
        if (::cache.isInitialized) runCatching { cache.release() }
        surfaces.forEach { (texture, surface) ->
            runCatching { surface.release() }
            runCatching { texture.release() }
        }
        surfaces.clear()
        database.close()
    }

    @Test
    fun a_a_local_clip_plays_from_the_content_uri() {
        val player = onMain { playerFactory.create() }
        attachOffscreenSurface(player)
        try {
            onMain {
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(videoUri.toString()))
                player.prepare()
            }
            assertTrue("Local clip never reached READY", awaitReady(player))
            val duration = onMain { player.duration }
            assertTrue("Duration should be known, was $duration", duration > 1_000)
        } finally {
            onMain { player.release() }
        }
    }

    @Test
    fun b_a_remote_clip_streams_and_seeks_to_the_middle() = runBlocking {
        backup.pair(serverUrl, pairCode).getOrThrow()
        val remoteId = backup.upload(assetRowId).getOrThrow()
        assertTrue("Upload returned no asset id", remoteId.isNotBlank())

        val url = library.fileUrl(remoteId)
        val player = onMain { playerFactory.create() }
        attachOffscreenSurface(player)

        try {
            onMain {
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                player.prepare()
            }
            assertTrue("Remote clip never reached READY — check Range support", awaitReady(player))

            val duration = onMain { player.duration }
            assertTrue("Remote duration should be known, was $duration", duration > 1_000)

            // §15 wants a seek to the midpoint to land quickly; this only checks
            // that it lands at all, and that it lands where it was asked to.
            val midpoint = duration / 2
            val startedAt = System.currentTimeMillis()
            onMain { player.seekTo(midpoint) }
            assertTrue("Seek never settled", awaitReady(player))
            val elapsed = System.currentTimeMillis() - startedAt

            val landed = onMain { player.currentPosition }
            assertTrue(
                "Seek landed at $landed, expected near $midpoint",
                abs(landed - midpoint) < 1_500,
            )
            assertTrue("Seek took ${elapsed}ms", elapsed < 10_000)

            // Streaming through the cache should leave bytes behind — this is
            // what stops a backwards scrub re-downloading (§11).
            assertTrue("Nothing was cached", cache.cacheSpace > 0)
        } finally {
            onMain { player.release() }
        }
    }

    @Test
    fun c_the_scanner_records_the_clip_as_a_video_with_a_duration() = runBlocking {
        val found = MediaStoreScanner(context)
            .scan(excludedFolders = emptySet())
            .firstOrNull { it.contentUri == videoUri.toString() }

        val media = requireNotNull(found) { "The scanner missed the generated clip" }

        // Decode one frame outside ExoPlayer entirely. If this works the file is
        // sound, and any player trouble is the player's or the emulator's.
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            assertTrue(
                "The generated clip does not decode — the encoder is at fault",
                retriever.getFrameAtTime(500_000) != null,
            )
        } finally {
            retriever.release()
        }

        assertTrue("Should be recognised as video, was ${media.mimeType}", media.isVideo)
        assertEquals(640, media.width)
        assertEquals(480, media.height)
        assertTrue("Duration should be populated, was ${media.durationMs}", (media.durationMs ?: 0) > 1_000)
    }

    // ── Player plumbing ─────────────────────────────────────────────────────

    /**
     * A decoder with nowhere to draw is a decoder that fails on the emulator's
     * goldfish codec. Tests run headless, so give the player somewhere real.
     */
    private fun attachOffscreenSurface(player: ExoPlayer): Surface {
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        onMain { player.setVideoSurface(surface) }
        surfaces += texture to surface
        return surface
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            runCatching(block).onSuccess { result = it }.onFailure { failure = it }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun awaitReady(player: ExoPlayer, timeoutMs: Long = 20_000): Boolean {
        val latch = CountDownLatch(1)
        var error: PlaybackException? = null

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) latch.countDown()
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                error = playbackError
                latch.countDown()
            }
        }

        onMain {
            player.addListener(listener)
            if (player.playbackState == Player.STATE_READY) latch.countDown()
        }

        val signalled = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        onMain { player.removeListener(listener) }

        error?.let { throw AssertionError("Playback failed: ${it.errorCodeName}", it) }
        return signalled && onMain { player.playbackState } == Player.STATE_READY
    }
}
