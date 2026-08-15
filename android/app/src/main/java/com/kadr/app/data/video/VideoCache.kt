package com.kadr.app.data.video

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.kadr.app.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The media cache from §11: 512 MB by default, least-recently-used eviction,
 * user-configurable.
 *
 * This is what makes scrubbing backwards free — the bytes are already on the
 * phone, so ExoPlayer never asks the server for them twice.
 *
 * SimpleCache insists on being the only instance for its directory in the
 * process, hence the singleton.
 */
@Singleton
class VideoCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    val cache: SimpleCache by lazy { open(context, settings.current.videoCacheMb, DEFAULT_DIRECTORY) }

    fun usedBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    fun clear() {
        runCatching {
            cache.keys.toList().forEach { key ->
                cache.removeResource(key)
            }
        }
    }

    companion object {
        const val DEFAULT_DIRECTORY = "media"

        /**
         * SimpleCache allows exactly one instance per folder per process, and an
         * instrumentation test shares the app's process — so anything that wants
         * its own cache has to name its own folder.
         */
        fun open(context: Context, sizeMb: Int, directoryName: String): SimpleCache = SimpleCache(
            File(context.cacheDir, directoryName),
            LeastRecentlyUsedCacheEvictor(sizeMb * 1024L * 1024L),
            StandaloneDatabaseProvider(context),
        )
    }
}
