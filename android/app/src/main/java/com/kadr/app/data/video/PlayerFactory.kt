package com.kadr.app.data.video

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kadr.app.data.remote.ApiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds players that can read both halves of the library (§11):
 *
 * - a `content://` URI plays straight off the device
 * - a server URL streams over HTTP with the device token attached, through the
 *   same OkHttp client the API uses — §13 serves no media without one
 *
 * Everything remote goes through the cache first, and the server's `Range`
 * support (§9) is what lets ExoPlayer seek without pulling the whole file.
 *
 * No transcoding is involved anywhere: the phone's hardware decoder does the
 * work, which is the entire reason the server can be a weak box (§4).
 */
@Singleton
class PlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val apiProvider: ApiProvider,
    private val cache: SimpleCache,
) {

    fun create(): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(apiProvider.httpClient)

        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory))
            // A cache read that goes wrong should fall back to the network
            // rather than failing playback outright.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /** A second, silent player for the grid's long-press previews (§11). */
    fun createPreviewPlayer(): ExoPlayer = create().apply {
        volume = 0f
        repeatMode = ExoPlayer.REPEAT_MODE_ALL
    }
}
