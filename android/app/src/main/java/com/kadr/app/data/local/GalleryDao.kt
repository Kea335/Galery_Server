package com.kadr.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryDao {

    /**
     * Local and server rows merged into one stream, newest first (§12).
     *
     * A photo that exists on both sides appears once: the local row wins,
     * because it can be shown instantly from disk. Server rows only surface when
     * no local file has the same hash — which is exactly the "freed up space"
     * and "photos from another phone" case.
     */
    @Query(
        """
        SELECT
            'l' || l.id                           AS itemKey,
            l.contentUri                          AS localUri,
            l.remoteId                            AS remoteId,
            COALESCE(l.capturedAt, l.dateModified) AS capturedAt,
            l.mimeType                            AS mimeType,
            l.durationMs                          AS durationMs,
            l.width                               AS width,
            l.height                              AS height,
            l.filename                            AS filename,
            l.state                               AS backupState
        FROM local_assets AS l
        WHERE l.state != 'SKIPPED'

        UNION ALL

        SELECT
            'r' || r.id AS itemKey,
            NULL        AS localUri,
            r.id        AS remoteId,
            r.capturedAt AS capturedAt,
            r.mimeType  AS mimeType,
            r.durationMs AS durationMs,
            r.width     AS width,
            r.height    AS height,
            r.filename  AS filename,
            NULL        AS backupState
        FROM remote_assets AS r
        WHERE r.deleted = 0
          AND (
                r.sha256 IS NULL
                OR r.sha256 NOT IN (
                    SELECT sha256 FROM local_assets
                    WHERE sha256 IS NOT NULL AND state != 'SKIPPED'
                )
              )

        ORDER BY capturedAt DESC
        """,
    )
    fun observeTimeline(): Flow<List<GalleryItem>>

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
