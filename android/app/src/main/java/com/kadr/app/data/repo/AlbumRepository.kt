package com.kadr.app.data.repo

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.kadr.app.data.local.AlbumDao
import com.kadr.app.data.local.AlbumItem
import com.kadr.app.data.local.AlbumSummary
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.local.RemoteAlbum
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.AddAlbumItemsRequest
import com.kadr.app.data.remote.AlbumDto
import com.kadr.app.data.remote.AlbumItemDto
import com.kadr.app.data.remote.ApiProvider
import com.kadr.app.data.remote.CreateAlbumRequest
import com.kadr.app.data.remote.RenameAlbumRequest
import com.kadr.app.data.remote.SetAlbumCoverRequest
import com.kadr.app.data.remote.apiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many photos were actually put into the album, and how many could not be.
 *
 * An album is a server-side relationship, so a photo the server has never seen
 * cannot be in one. Saying "12 added" when 3 were quietly dropped would be the
 * kind of small lie that makes people stop trusting the app.
 */
data class AddToAlbumResult(val added: Int, val notBackedUp: Int)

/**
 * Albums (§16.6): manual, held on the server, shared by every phone that signs
 * in — the same shape §16 already gave the library.
 *
 * Everything here is a cache of the server plus a way to ask it to change. The
 * contents of an album are a local join: the library is mirrored already and
 * membership arrives on its own delta stream, so there is nothing left to fetch.
 */
@Singleton
class AlbumRepository @Inject constructor(
    private val albumDao: AlbumDao,
    private val apiProvider: ApiProvider,
    private val settings: SettingsStore,
    private val json: Json,
) {

    fun observeAlbums(): Flow<List<AlbumSummary>> = albumDao.observeAlbums()

    fun observeAlbum(id: String): Flow<RemoteAlbum?> = albumDao.observeAlbum(id)

    fun observeAlbumCount(id: String): Flow<Int> = albumDao.observeAlbumCount(id)

    /** One album, a page at a time — the same deal the timeline gets (§15). */
    fun albumPages(albumId: String): Flow<PagingData<GalleryItem>> =
        Pager(
            config = PagingConfig(
                pageSize = ALBUM_PAGE_SIZE,
                prefetchDistance = ALBUM_PAGE_SIZE,
                initialLoadSize = ALBUM_PAGE_SIZE * 2,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { albumDao.pagingAlbum(albumId) },
        ).flow

    /**
     * The same album for the viewer, but with placeholders: it opens **at** a
     * position rather than scrolling to one, so page 400 has to exist before
     * anything around it has been read. The grid keeps them off because
     * separators cannot reason about null rows.
     */
    fun albumViewerPages(albumId: String): Flow<PagingData<GalleryItem>> =
        Pager(
            config = PagingConfig(
                pageSize = VIEWER_PAGE_SIZE,
                prefetchDistance = VIEWER_PAGE_SIZE,
                enablePlaceholders = true,
            ),
            pagingSourceFactory = { albumDao.pagingAlbum(albumId) },
        ).flow

    /** Where a photo sits inside this album, or -1 once it is no longer in it. */
    suspend fun positionInAlbum(albumId: String, key: String, capturedAt: Long): Int =
        withContext(Dispatchers.IO) { albumDao.positionInAlbum(albumId, key, capturedAt) }

    // ─── Delta sync (§9) ────────────────────────────────────────────────────

    /**
     * Pulls both album streams forward. Kept separate from the library sync
     * because they are separate streams on the server with separate counters —
     * an upload must not drag the album cursors along with it.
     */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!settings.current.isPaired) {
            return@withContext Result.failure(IllegalStateException("Not paired with a server yet."))
        }

        runCatching {
            val api = apiProvider.api()

            var cursor = settings.current.albumsSince
            while (true) {
                val page = apiCall(json) { api.albums(since = cursor, limit = PAGE_SIZE) }.data
                if (page.albums.isNotEmpty()) {
                    albumDao.upsertAlbums(page.albums.map { it.toEntity() })
                }
                cursor = maxOf(cursor, page.nextCursor)
                settings.saveAlbumsSince(cursor)
                if (page.albums.isEmpty() || !page.hasMore) break
            }

            var itemCursor = settings.current.albumItemsSince
            while (true) {
                val page = apiCall(json) {
                    api.albumItems(since = itemCursor, limit = PAGE_SIZE)
                }.data
                if (page.items.isNotEmpty()) {
                    albumDao.upsertItems(page.items.map { it.toEntity() })
                }
                itemCursor = maxOf(itemCursor, page.nextCursor)
                settings.saveAlbumItemsSince(itemCursor)
                if (page.items.isEmpty() || !page.hasMore) break
            }

            Log.i(TAG, "album sync: albums=$cursor items=$itemCursor")
            Unit
        }
    }

    // ─── Changes ────────────────────────────────────────────────────────────

    /**
     * Every one of these goes to the server first and then syncs back, rather
     * than writing locally and hoping. Two phones share these albums; a local
     * guess that the server refused would show one of them something untrue.
     */
    suspend fun create(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val album = apiCall(json) { apiProvider.api().createAlbum(CreateAlbumRequest(name)) }.data
            sync()
            album.id
        }
    }

    suspend fun rename(id: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            apiCall(json) { apiProvider.api().renameAlbum(id, RenameAlbumRequest(name)) }
            sync()
            Unit
        }
    }

    /**
     * Names the photo the album shows on its card. The server keeps the choice,
     * so every phone sees the same front.
     */
    suspend fun setCover(albumId: String, assetId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                apiCall(json) {
                    apiProvider.api().setAlbumCover(albumId, SetAlbumCoverRequest(assetId))
                }
                sync()
                Unit
            }
        }

    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            apiCall(json) { apiProvider.api().deleteAlbum(id) }
            sync()
            Unit
        }
    }

    /**
     * Adds whatever the selection points at, by timeline key.
     *
     * Keys that have no server id yet are counted, not sent: those photos are
     * still waiting to be backed up, and the server cannot hold a relationship
     * to something it does not have.
     */
    suspend fun addByKeys(albumId: String, keys: Collection<String>): Result<AddToAlbumResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ids = albumDao.remoteIdsFor(keys.toList()).distinct()
                val notBackedUp = keys.size - ids.size
                if (ids.isEmpty()) return@runCatching AddToAlbumResult(0, notBackedUp)

                val api = apiProvider.api()
                // §9 caps a request at 500; a big selection is several requests.
                ids.chunked(MAX_ITEMS_PER_REQUEST).forEach { chunk ->
                    apiCall(json) { api.addAlbumItems(albumId, AddAlbumItemsRequest(chunk)) }
                }
                sync()
                AddToAlbumResult(ids.size, notBackedUp)
            }
        }

    suspend fun removeItem(albumId: String, assetId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                apiCall(json) { apiProvider.api().removeAlbumItem(albumId, assetId) }
                sync()
                Unit
            }
        }

    /** Forgets the mirror so the next sync rebuilds it from a zero cursor. */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        albumDao.clearItems()
        albumDao.clearAlbums()
    }

    private fun AlbumDto.toEntity() = RemoteAlbum(
        id = id,
        name = name,
        coverAssetId = coverAssetId,
        createdAt = createdAt,
        deleted = deleted,
        updatedAt = updatedAt,
    )

    private fun AlbumItemDto.toEntity() = AlbumItem(
        albumId = albumId,
        assetId = assetId,
        addedAt = addedAt,
        removed = removed,
        updatedAt = updatedAt,
    )

    private companion object {
        const val TAG = "KadrAlbums"
        const val PAGE_SIZE = 500
        const val MAX_ITEMS_PER_REQUEST = 500
        const val ALBUM_PAGE_SIZE = 90
        const val VIEWER_PAGE_SIZE = 12
    }
}
