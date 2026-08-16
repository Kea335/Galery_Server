package com.kadr.app.data.remote

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * A structured server error. The upload engine steers on `code` — RANGE_GAP and
 * SESSION_RESET are recoverable, HASH_MISMATCH is not (§9).
 */
class ApiException(
    val code: String,
    val httpStatus: Int,
    override val message: String,
    val receivedBytes: Long? = null,
    val freeBytes: Long? = null,
    val requiredBytes: Long? = null,
) : IOException(message) {

    /** The server has no room left. Not a fault — an ending (§16). */
    val isDiskFull: Boolean get() = code == "DISK_FULL"

    /** The server told us where it actually is; resync and continue. */
    val isRecoverable: Boolean
        get() = code == "RANGE_GAP" || code == "SESSION_RESET"

    /**
     * Retrying will not help. Either we sent something wrong, or the server has
     * a condition no amount of waiting fixes — a full disk being the obvious one.
     */
    val isPermanent: Boolean
        get() = when {
            isRecoverable -> false
            code in PERMANENT_CODES -> true
            // 408 and 429 are the two 4xx worth waiting out.
            httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429 -> true
            else -> false
        }

    override fun toString() = "$code ($httpStatus): $message"

    private companion object {
        val PERMANENT_CODES = setOf(
            "HASH_MISMATCH",
            "DISK_FULL",
            "LENGTH_MISMATCH",
            "SIZE_MISMATCH",
            "BAD_CONTENT_RANGE",
            "UNAUTHORIZED",
        )
    }
}

/**
 * Runs a call and turns Retrofit's HttpException into the error shape the
 * server actually speaks. Anything unparseable keeps its HTTP status so the UI
 * can still say something true.
 */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        throw e.toApiException(json)
    }

private fun HttpException.toApiException(json: Json): ApiException {
    val status = code()
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()

    val parsed = raw?.let {
        runCatching { json.decodeFromString<ErrorEnvelope>(it).error }.getOrNull()
    }

    return ApiException(
        code = parsed?.code ?: "HTTP_$status",
        httpStatus = status,
        message = parsed?.message?.takeIf { it.isNotBlank() } ?: (message() ?: "HTTP $status"),
        receivedBytes = parsed?.receivedBytes,
        freeBytes = parsed?.freeBytes,
        requiredBytes = parsed?.requiredBytes,
    )
}
