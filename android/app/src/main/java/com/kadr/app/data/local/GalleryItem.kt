package com.kadr.app.data.local

import androidx.room.ColumnInfo
import androidx.room.DatabaseView

/**
 * Local and server rows merged into one timeline (§12).
 *
 * A photo that exists on both sides appears once: the local row wins, because it
 * can be shown instantly from disk. Server rows only surface when no local file
 * has the same hash — which is exactly the "freed up space" and "photos from
 * another phone" case.
 *
 * Kept as a `const` so [KadrDatabase.MIGRATION_2_3] can create the view from the
 * very same text Room expects to find. Room compares the stored `CREATE VIEW`
 * statement character for character, so two copies that drifted apart would fail
 * to open the database rather than fail quietly.
 */
const val TIMELINE_VIEW_SQL = """
        SELECT
            'l' || l.id                            AS itemKey,
            l.contentUri                           AS localUri,
            l.remoteId                             AS remoteId,
            COALESCE(l.capturedAt, l.dateModified) AS capturedAt,
            l.mimeType                             AS mimeType,
            l.durationMs                           AS durationMs,
            l.width                                AS width,
            l.height                               AS height,
            l.filename                             AS filename,
            l.state                                AS backupState
        FROM local_assets AS l
        WHERE l.state != 'SKIPPED'

        UNION ALL

        SELECT
            'r' || r.id  AS itemKey,
            NULL         AS localUri,
            r.id         AS remoteId,
            r.capturedAt AS capturedAt,
            r.mimeType   AS mimeType,
            r.durationMs AS durationMs,
            r.width      AS width,
            r.height     AS height,
            r.filename   AS filename,
            NULL         AS backupState
        FROM remote_assets AS r
        WHERE r.deleted = 0
          AND (
                r.sha256 IS NULL
                OR r.sha256 NOT IN (
                    SELECT sha256 FROM local_assets
                    WHERE sha256 IS NOT NULL AND state != 'SKIPPED'
                )
              )
"""

/**
 * One cell in the timeline, from either side of the library.
 *
 * The view carries no `ORDER BY` on purpose — every reader states its own, and
 * they all have to agree on the same **total** order. See [GalleryDao].
 */
@DatabaseView(viewName = "timeline_items", value = TIMELINE_VIEW_SQL)
data class GalleryItem(
    /**
     * Stable, unique identity across a re-query — the shared-element key and
     * the LazyGrid key.
     *
     * Built from the row id rather than the hash on purpose: a phone really can
     * hold the same photo twice, and two cells sharing a key crashes the grid.
     */
    @ColumnInfo(name = "itemKey") val key: String,
    /** `content://` URI when the file is still on this device. */
    val localUri: String?,
    /** Server asset id when the server holds it. */
    val remoteId: String?,
    val capturedAt: Long,
    val mimeType: String,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val filename: String,
    /** [AssetState] name for local rows, null for server-only ones. */
    val backupState: String?,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    val isOnServer: Boolean
        get() = remoteId != null || backupState == AssetState.VERIFIED.name

    val isLocalOnly: Boolean get() = localUri != null && !isOnServer

    val isServerOnly: Boolean get() = localUri == null
}
