package com.kadr.app.data.repo

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.kadr.app.data.local.GalleryDao
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.local.RemoteAsset
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.ApiProvider
import com.kadr.app.data.remote.HealthResponse
import com.kadr.app.data.remote.RemoteAssetDto
import com.kadr.app.data.remote.TrashListResponse
import com.kadr.app.data.remote.apiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(val fetched: Int, val removed: Int, val linked: Int)

/**
 * Mirrors the server library locally so the timeline can show everything at
 * once — the photos still on this phone and the ones that only live on the
 * server (§12, screen 1).
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val galleryDao: GalleryDao,
    private val apiProvider: ApiProvider,
    private val settings: SettingsStore,
    private val json: Json,
) {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * The timeline, a page at a time (§15). Placeholders stay off: the grid
     * folds month dividers in with `insertSeparators`, which cannot decide where
     * a month begins when the rows on either side are still null.
     */
    fun timelinePages(): Flow<PagingData<GalleryItem>> =
        Pager(
            config = PagingConfig(
                // Three screens of a 3-column grid, so a fast flick has somewhere
                // to land before the next page arrives.
                pageSize = TIMELINE_PAGE_SIZE,
                prefetchDistance = TIMELINE_PAGE_SIZE,
                initialLoadSize = TIMELINE_PAGE_SIZE * 2,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = galleryDao::pagingTimeline,
        ).flow

    /**
     * The same timeline for the viewer, but with placeholders, because it opens
     * at a position rather than scrolling to one — page 4,000 has to exist
     * before anything has been loaded around it.
     */
    fun viewerPages(): Flow<PagingData<GalleryItem>> =
        Pager(
            config = PagingConfig(
                pageSize = VIEWER_PAGE_SIZE,
                prefetchDistance = VIEWER_PAGE_SIZE,
                enablePlaceholders = true,
            ),
            pagingSourceFactory = galleryDao::pagingTimeline,
        ).flow

    fun observeTimelineCount(): Flow<Int> = galleryDao.observeTimelineCount()

    /** Where [key] sits in the timeline, or -1 if it is no longer there. */
    suspend fun positionOf(key: String, capturedAt: Long): Int =
        withContext(Dispatchers.IO) { galleryDao.positionOf(key, capturedAt) }

    /**
     * Delta sync (§9): page forward from the stored cursor until the server has
     * nothing newer. Tombstones arrive in the same stream, which is how a photo
     * deleted on the server disappears here too.
     */
    suspend fun sync(): Result<SyncResult> = withContext(Dispatchers.IO) {
        if (!settings.current.isPaired) {
            return@withContext Result.failure(IllegalStateException("Not paired with a server yet."))
        }

        _syncing.value = true
        runCatching {
            val api = apiProvider.api()
            var cursor = settings.current.librarySince
            var fetched = 0
            var removed = 0

            while (true) {
                val page = apiCall(json) { api.assets(since = cursor, limit = PAGE_SIZE) }.data
                if (page.assets.isEmpty()) {
                    cursor = maxOf(cursor, page.nextCursor)
                    break
                }

                val (tombstones, live) = page.assets.partition { it.deleted }

                if (live.isNotEmpty()) {
                    galleryDao.upsertRemote(live.map { it.toEntity() })
                    fetched += live.size
                }
                tombstones.forEach { asset ->
                    galleryDao.tombstoneRemote(asset.id, asset.updatedAt)
                    removed++
                }

                cursor = maxOf(cursor, page.nextCursor)
                settings.saveLibrarySince(cursor)

                if (!page.hasMore) break
            }

            settings.saveLibrarySince(cursor)
            val linked = galleryDao.linkRemoteIds()

            Log.i(TAG, "sync: $fetched updated, $removed removed, $linked ids linked, cursor=$cursor")
            SyncResult(fetched, removed, linked)
        }.also { _syncing.value = false }
    }

    suspend fun health(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runCatching { apiCall(json) { apiProvider.api().health() }.data }
    }

    /**
     * What is in the trash, with enough detail to show it. Delta sync only
     * carries tombstones, so the server has a dedicated endpoint for this.
     */
    suspend fun trash(): Result<TrashListResponse> = withContext(Dispatchers.IO) {
        runCatching { apiCall(json) { apiProvider.api().trash() }.data }
    }

    suspend fun restore(assetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            apiCall(json) { apiProvider.api().restoreAsset(assetId) }
            Unit
        }
    }

    suspend fun moveToTrash(assetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            apiCall(json) { apiProvider.api().deleteAsset(assetId) }
            Unit
        }
    }

    /** Full URL for a server-side thumbnail; Coil adds the token via OkHttp. */
    fun thumbnailUrl(remoteId: String, size: Int = 512): String =
        "${ApiProvider.normalize(settings.current.serverUrl)}api/v1/assets/$remoteId/thumb?size=$size"

    /** Full URL for the original bytes — Range-capable, so video seeking works. */
    fun fileUrl(remoteId: String): String =
        "${ApiProvider.normalize(settings.current.serverUrl)}api/v1/assets/$remoteId/file"

    private fun RemoteAssetDto.toEntity() = RemoteAsset(
        id = id,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        filename = filename,
        // A server row with no capture date still has to land somewhere on the
        // timeline; upload time is the least wrong answer.
        capturedAt = capturedAt ?: uploadedAt,
        uploadedAt = uploadedAt,
        width = width,
        height = height,
        durationMs = durationMs,
        orientation = orientation,
        deleted = deleted,
        updatedAt = updatedAt,
    )

    private companion object {
        const val TAG = "KadrLibrary"

        /** How many assets one delta-sync request asks the server for (§9). */
        const val PAGE_SIZE = 500

        const val TIMELINE_PAGE_SIZE = 90
        const val VIEWER_PAGE_SIZE = 12
    }
}
