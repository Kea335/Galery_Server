package com.kadr.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per media file on this device (§8).
 *
 * The identity key is `(mediaStoreId, sizeBytes, dateModified)`. When any of the
 * last two change the file was edited, so the cached hash is thrown away and the
 * row walks the state machine again.
 *
 * `dateModified` is stored in **milliseconds**, not the seconds MediaStore
 * hands out, so every timestamp in this table shares one unit.
 */
@Entity(
    tableName = "local_assets",
    indices = [
        Index(value = ["mediaStoreId"], unique = true),
        Index(value = ["state"]),
    ],
)
data class LocalAsset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val contentUri: String,
    val relativePath: String,
    val filename: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val capturedAt: Long?,
    val mimeType: String,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val orientation: Int = 0,
    val sha256: String?,
    val state: AssetState,
    val remoteId: String?,
    val attemptCount: Int = 0,
    val lastError: String? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}
