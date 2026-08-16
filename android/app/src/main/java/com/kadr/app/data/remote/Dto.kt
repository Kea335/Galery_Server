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
