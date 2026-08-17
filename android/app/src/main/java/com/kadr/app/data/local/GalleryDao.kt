package com.kadr.app.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryDao {

    /**
     * One page of the timeline, newest first (§12).
     *
     * The order has to be **total**, not just "by date": a page is a LIMIT/OFFSET
     * window, so two rows the database is free to return in either order would
     * let a photo appear on two pages or on none. `itemKey` breaks every tie and
     * is unique by construction, which makes the window deterministic.
     */
    @Query("SELECT * FROM timeline_items ORDER BY capturedAt DESC, itemKey DESC")
    fun pagingTimeline(): PagingSource<Int, GalleryItem>

    /**
     * How many photos there are, for the header count. Cheap next to loading
     * them: SQLite counts rows without building a single [GalleryItem].
     */
    @Query("SELECT COUNT(*) FROM timeline_items")
    fun observeTimelineCount(): Flow<Int>

    /**
     * Where one photo sits in the timeline, by counting everything that sorts
     * ahead of it under the exact ordering [pagingTimeline] uses. The viewer
     * needs a position to open at, and asking the database is the only answer
     * that stays right when only part of the list has been loaded.
     *
     * Returns -1 when the photo is gone — deleted while the viewer was opening.
     */
    @Query(
        """
        SELECT CASE
            WHEN NOT EXISTS (SELECT 1 FROM timeline_items WHERE itemKey = :itemKey) THEN -1
            ELSE (
                SELECT COUNT(*) FROM timeline_items
                WHERE capturedAt > :capturedAt
                   OR (capturedAt = :capturedAt AND itemKey > :itemKey)
            )
        END
        """,
    )
    suspend fun positionOf(itemKey: String, capturedAt: Long): Int

    @Query("SELECT COUNT(*) FROM remote_assets WHERE deleted = 0")
    suspend fun remoteCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRemote(assets: List<RemoteAsset>)

    @Query("DELETE FROM remote_assets WHERE id IN (:ids)")
    suspend fun deleteRemote(ids: List<String>)

    @Query("UPDATE remote_assets SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun tombstoneRemote(id: String, updatedAt: Long)

    /**
     * Fills in the server ids that §9's check endpoint cannot report. A file the
     * server already had is marked VERIFIED with no id; one delta sync later we
     * can match it up by hash.
     */
    @Query(
        """
        UPDATE local_assets
        SET remoteId = (
            SELECT r.id FROM remote_assets r
            WHERE r.sha256 = local_assets.sha256 AND r.deleted = 0
            LIMIT 1
        )
        WHERE sha256 IS NOT NULL
          AND remoteId IS NULL
          AND EXISTS (
            SELECT 1 FROM remote_assets r
            WHERE r.sha256 = local_assets.sha256 AND r.deleted = 0
          )
        """,
    )
    suspend fun linkRemoteIds(): Int

    @Query("DELETE FROM remote_assets")
    suspend fun clearRemote()
}
