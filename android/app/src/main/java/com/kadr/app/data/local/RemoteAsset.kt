package com.kadr.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The server's copy of the library, mirrored locally so the timeline can show
 * everything — including photos this phone never had (§12, screen 1).
 *
 * Tombstones are kept rather than deleted: a row with `deleted = true` is how
 * the next delta sync knows it has already seen that removal.
 */
@Entity(
    tableName = "remote_assets",
    indices = [
        Index(value = ["sha256"]),
        Index(value = ["capturedAt"]),
        Index(value = ["updatedAt"]),
    ],
)
data class RemoteAsset(
    @PrimaryKey val id: String,
    val sha256: String?,
    val sizeBytes: Long,
    val mimeType: String,
    val filename: String,
    val capturedAt: Long,
    val uploadedAt: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val orientation: Int,
    val deleted: Boolean,
    val updatedAt: Long,
)
