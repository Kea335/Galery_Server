package com.kadr.app.data.local

/**
 * One cell in the timeline. Local and server rows are folded into this shape by
 * [LocalAssetDao.observeTimeline] so the UI never has to care which side a photo
 * came from (§12).
 */
data class GalleryItem(
    /**
     * Stable, unique identity across a re-query — the shared-element key and
     * the LazyGrid key.
     *
     * Built from the row id rather than the hash on purpose: a phone really can
     * hold the same photo twice, and two cells sharing a key crashes the grid.
     */
    @androidx.room.ColumnInfo(name = "itemKey") val key: String,
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
