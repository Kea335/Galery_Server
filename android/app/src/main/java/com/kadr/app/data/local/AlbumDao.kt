package com.kadr.app.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    // ─── Delta sync (§9) ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<RemoteAlbum>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<AlbumItem>)

    @Query("DELETE FROM remote_albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM album_items")
    suspend fun clearItems()

    // ─── The album list ─────────────────────────────────────────────────────

    /**
     * Every album that still exists, with how many photos are in it and what to
     * draw on the front.
     *
     * The cover is the newest photo in the album. The server can also name one
     * explicitly, and that column is synced, but nothing offers to set it yet —
     * so reading it here would only ever return null and hide the useful answer.
     *
     * Both cover columns come from the same subquery shape because a photo this
     * phone still holds draws from disk instantly, while one that has been freed
     * has to come from the server.
     */
    @Query(
        """
        SELECT
            a.id   AS id,
            a.name AS name,
            (
                SELECT COUNT(*) FROM album_items ai
                WHERE ai.albumId = a.id AND ai.removed = 0
            ) AS itemCount,
            (
                SELECT t.localUri FROM timeline_items t
                JOIN album_items ai ON ai.assetId = t.remoteId
                WHERE ai.albumId = a.id AND ai.removed = 0
                ORDER BY t.capturedAt DESC, t.itemKey DESC LIMIT 1
            ) AS coverLocalUri,
            (
                SELECT t.remoteId FROM timeline_items t
                JOIN album_items ai ON ai.assetId = t.remoteId
                WHERE ai.albumId = a.id AND ai.removed = 0
                ORDER BY t.capturedAt DESC, t.itemKey DESC LIMIT 1
            ) AS coverRemoteId
        FROM remote_albums a
        WHERE a.deleted = 0
        ORDER BY a.name COLLATE NOCASE ASC
        """,
    )
    fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM remote_albums WHERE id = :id AND deleted = 0")
    fun observeAlbum(id: String): Flow<RemoteAlbum?>

    // ─── One album's photos ─────────────────────────────────────────────────

    /**
     * An album, page by page, in the same order the timeline uses.
     *
     * The tiebreaker is not decoration: a page is a LIMIT/OFFSET window, and a
     * burst of shots shares a capture time, so without `itemKey` a photo can
     * land on two pages or on none. The timeline learned this already; an album
     * is the same query with a join in front of it.
     */
    @Query(
        """
        SELECT t.* FROM timeline_items t
        JOIN album_items ai ON ai.assetId = t.remoteId
        WHERE ai.albumId = :albumId AND ai.removed = 0
        ORDER BY t.capturedAt DESC, t.itemKey DESC
        """,
    )
    fun pagingAlbum(albumId: String): PagingSource<Int, GalleryItem>

    @Query(
        """
        SELECT COUNT(*) FROM timeline_items t
        JOIN album_items ai ON ai.assetId = t.remoteId
        WHERE ai.albumId = :albumId AND ai.removed = 0
        """,
    )
    fun observeAlbumCount(albumId: String): Flow<Int>

    /**
     * Which of these photos the server can actually be told about. A photo that
     * has not been backed up yet has no server id, and an album is a server-side
     * relationship — so the caller has to know how many were left behind.
     */
    @Query("SELECT remoteId FROM timeline_items WHERE itemKey IN (:keys) AND remoteId IS NOT NULL")
    suspend fun remoteIdsFor(keys: List<String>): List<String>
}
