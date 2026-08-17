package com.kadr.app.data.remote

import kotlinx.serialization.Serializable

/** Every server response is `{ "data": ... }` or `{ "error": {...} }` (§9). */
@Serializable
data class Envelope<T>(val data: T)

@Serializable
data class ErrorEnvelope(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(
    val code: String = "UNKNOWN",
    val message: String = "",
    /** Present on RANGE_GAP, SESSION_RESET, HASH_MISMATCH and INCOMPLETE. */
    val receivedBytes: Long? = null,
    /** Present on DISK_FULL, so the app can say how much room is left. */
    val freeBytes: Long? = null,
    val requiredBytes: Long? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceName: String,
)

/**
 * The password is exchanged for this once and never sent again — every later
 * request carries the token instead.
 */
@Serializable
data class LoginResponse(
    val deviceId: String,
    val token: String,
    val username: String = "",
)

@Serializable
data class DeviceListResponse(val devices: List<SignedInDeviceDto> = emptyList())

@Serializable
data class SignedInDeviceDto(
    val id: String,
    val name: String = "",
    val createdAt: Long = 0,
    val lastSeenAt: Long? = null,
    val revoked: Boolean = false,
    val current: Boolean = false,
)

@Serializable
data class CheckRequest(val hashes: List<String>)

@Serializable
data class CheckResponse(val missing: List<String>)

@Serializable
data class CreateUploadRequest(
    val sha256: String,
    val sizeBytes: Long,
    val filename: String,
    val mimeType: String,
    val capturedAt: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val orientation: Int = 0,
)

@Serializable
data class CreateUploadResponse(
    val uploadId: String? = null,
    val receivedBytes: Long = 0,
    val alreadyExists: Boolean = false,
    val assetId: String? = null,
)

@Serializable
data class ChunkResponse(
    val receivedBytes: Long,
    val duplicate: Boolean = false,
)

@Serializable
data class UploadStatusResponse(
    val uploadId: String,
    val receivedBytes: Long,
    val expectedSize: Long,
    val sha256: String,
)

@Serializable
data class CompleteResponse(val assetId: String)

/** §9 delta sync. Deleted rows arrive as tombstones with only id + updatedAt. */
@Serializable
data class AssetListResponse(
    val assets: List<RemoteAssetDto> = emptyList(),
    val nextCursor: Long = 0,
    val hasMore: Boolean = false,
)

/**
 * Albums (§16.6). Two delta streams rather than one: albums and membership move
 * at completely different rates, and the server keeps a separate cursor for each.
 */
@Serializable
data class AlbumDto(
    val id: String,
    val name: String = "",
    val coverAssetId: String? = null,
    val createdAt: Long = 0,
    val deleted: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class AlbumListResponse(
    val albums: List<AlbumDto> = emptyList(),
    val nextCursor: Long = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class AlbumItemDto(
    val albumId: String,
    val assetId: String,
    val addedAt: Long = 0,
    /** True once the photo has been taken out; the row itself is never dropped. */
    val removed: Boolean = false,
    val removedAt: Long? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class AlbumItemListResponse(
    val items: List<AlbumItemDto> = emptyList(),
    val nextCursor: Long = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class AlbumDeletedResponse(
    val id: String = "",
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

@Serializable
data class AlbumItemRemovedResponse(
    val albumId: String = "",
    val assetId: String = "",
    val removed: Boolean = false,
    val removedAt: Long? = null,
)

@Serializable
data class CreateAlbumRequest(val name: String)

@Serializable
data class RenameAlbumRequest(val name: String)

/**
 * Its own request type rather than one PATCH body with two optional fields: the
 * server reads "absent" and "null" differently — absent leaves the cover alone,
 * null clears it — and a shared body would make renaming clear the cover by
 * accident the moment the serializer decided to write the default.
 */
@Serializable
data class SetAlbumCoverRequest(val coverAssetId: String)

@Serializable
data class AddAlbumItemsRequest(val assetIds: List<String>)

@Serializable
data class AddAlbumItemsResponse(val albumId: String = "", val added: Int = 0)

@Serializable
data class TrashListResponse(
    val assets: List<TrashedAssetDto> = emptyList(),
    val retentionDays: Int = 30,
)

@Serializable
data class TrashedAssetDto(
    val id: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0,
    val mimeType: String = "",
    val filename: String = "",
    val capturedAt: Long? = null,
    val deletedAt: Long = 0,
    /** How long before the server purges it for good (§7). */
    val purgesInMs: Long = 0,
)

@Serializable
data class RemoteAssetDto(
    val id: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0,
    val mimeType: String = "application/octet-stream",
    val filename: String = "",
    val capturedAt: Long? = null,
    val uploadedAt: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val orientation: Int = 0,
    val deleted: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class HealthResponse(
    val version: String,
    val uptimeSec: Long,
    val freeDiskBytes: Long? = null,
    val assetCount: Int = 0,
    val trashedCount: Int = 0,
    val pendingUploads: Int = 0,
    val dbSizeBytes: Long = 0,
    val rssBytes: Long = 0,
    val thumbnails: String = "",
)
