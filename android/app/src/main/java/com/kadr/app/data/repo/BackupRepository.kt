package com.kadr.app.data.repo

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.kadr.app.data.local.AssetState
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.local.LocalAssetDao
import com.kadr.app.data.media.MediaStoreScanner
import com.kadr.app.data.media.ScannedMedia
import com.kadr.app.data.media.Sha256Hasher
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.ApiException
import com.kadr.app.data.remote.ApiProvider
import com.kadr.app.data.remote.CheckRequest
import com.kadr.app.data.remote.ChunkRequestBody
import com.kadr.app.data.remote.CreateUploadRequest
import com.kadr.app.data.remote.HealthResponse
import com.kadr.app.data.remote.LoginRequest
import com.kadr.app.data.remote.apiCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

data class ScanResult(val added: Int, val changed: Int, val unchanged: Int) {
    val total: Int get() = added + changed + unchanged
}

enum class BackupPhase { IDLE, SCANNING, HASHING, CHECKING, UPLOADING }

data class BackupProgress(
    val phase: BackupPhase,
    val done: Int = 0,
    val total: Int = 0,
    val filename: String? = null,
    val sentBytes: Long = 0,
    val fileBytes: Long = 0,
    val bytesPerSecond: Long = 0,
) {
    val fileFraction: Float get() = if (fileBytes <= 0L) 0f else sentBytes.toFloat() / fileBytes
    val overallFraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

data class BackupOutcome(
    val uploaded: Int,
    val deduped: Int,
    val skipped: Int,
    val failed: Int,
    val remaining: Int,
    val stoppedEarly: Boolean,
    /** Set when the server ran out of room; see [BackupRepository.serverFull]. */
    val serverFull: Boolean = false,
) {
    val didWork: Boolean get() = uploaded > 0 || deduped > 0 || skipped > 0
}

/**
 * The library is only as big as the disk behind it (§16: "whatever fits in
 * 50 GB"), so a full server is an ordinary ending rather than a fault. It
 * deserves one clear sentence, not a hundred failed files.
 */
data class ServerFull(
    val freeBytes: Long?,
    val requiredBytes: Long?,
)

@Singleton
class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: LocalAssetDao,
    private val scanner: MediaStoreScanner,
    private val hasher: Sha256Hasher,
    private val apiProvider: ApiProvider,
    private val settings: SettingsStore,
    private val json: Json,
) {

    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Non-null once the server has said it is out of room, until it is not. */
    private val _serverFull = MutableStateFlow<ServerFull?>(null)
    val serverFull: StateFlow<ServerFull?> = _serverFull.asStateFlow()

    // The periodic worker and a "back up now" tap can land at the same moment.
    // Only one loop may own the queue.
    private val backupLock = Mutex()

    // Batch-level counters, merged into every progress emission. Only one
    // backup runs at a time (WorkManager unique work), so plain fields are safe.
    @Volatile private var batchDone = 0
    @Volatile private var batchTotal = 0

    fun observeAssets() = dao.observeAll()
    fun observeStateCounts() = dao.observeStateCounts()
    fun observeFailed() = dao.observeFailed()

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) { dao.pendingCount(MAX_ATTEMPTS) }

    suspend fun retryFailed(): Int = withContext(Dispatchers.IO) { dao.resetFailures() }

    /**
     * Un-parks everything §10.5's rules parked, for when those rules change.
     *
     * Called from the toggles rather than from [runBackup]: doing it on every
     * run would re-park the same files every few hours and report them as
     * "skipped" each time, which is noise rather than news.
     */
    suspend fun requeueSkipped(): Int = withContext(Dispatchers.IO) { dao.requeueSkipped() }

    suspend fun reclaimableBytes(): Long = withContext(Dispatchers.IO) { dao.reclaimableBytes() }

    suspend fun freedBytes(): Long = withContext(Dispatchers.IO) { dao.freedBytes() }

    // ─── Pairing ────────────────────────────────────────────────────────────

    suspend fun health(serverUrl: String): Result<HealthResponse> = runCatching {
        withContext(Dispatchers.IO) {
            apiCall(json) { apiProvider.apiFor(serverUrl).health() }.data
        }
    }

    /**
     * Signs in and stores the device token. The password is never kept — from
     * here on every request carries the token instead.
     */
    suspend fun login(serverUrl: String, username: String, password: String): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val response = apiCall(json) {
                    apiProvider.apiFor(serverUrl).login(
                        LoginRequest(
                            username = username.trim(),
                            password = password,
                            deviceName = deviceName,
                        ),
                    )
                }.data
                settings.savePairing(serverUrl, response.deviceId, response.token)
            }
        }

    // ─── Scanning (§10.1) ───────────────────────────────────────────────────

    /**
     * Reconciles MediaStore against the Room index.
     *
     * The identity key is `(mediaStoreId, sizeBytes, dateModified)`. When the
     * last two move the file was edited, so the cached hash is dropped and the
     * row starts the state machine over — an edited photo is a different photo
     * as far as content addressing is concerned.
     *
     * Capture dates are resolved only for rows being written, which keeps a
     * re-scan from opening a stream per file just to read EXIF.
     */
    suspend fun scan(): Result<ScanResult> = runCatching {
        _scanning.value = true
        _scanProgress.value = 0
        try {
            withContext(Dispatchers.IO) {
                val found = scanner.scan(settings.current.excludedFolders) { seen ->
                    _scanProgress.value = seen
                }

                var added = 0
                var changed = 0
                var unchanged = 0

                for (media in found) {
                    val existing = dao.findByMediaStoreId(media.mediaStoreId)
                    when {
                        existing == null -> {
                            dao.insert(media.toLocalAsset(scanner.resolveCapturedAt(media)))
                            added++
                        }

                        existing.sizeBytes != media.sizeBytes ||
                            existing.dateModified != media.dateModified -> {
                            dao.update(
                                existing.copy(
                                    contentUri = media.contentUri,
                                    relativePath = media.relativePath,
                                    filename = media.filename,
                                    sizeBytes = media.sizeBytes,
                                    dateModified = media.dateModified,
                                    capturedAt = scanner.resolveCapturedAt(media),
                                    mimeType = media.mimeType,
                                    durationMs = media.durationMs,
                                    width = media.width,
                                    height = media.height,
                                    orientation = media.orientation,
                                    sha256 = null,
                                    state = AssetState.DISCOVERED,
                                    remoteId = null,
                                    attemptCount = 0,
                                    lastError = null,
                                ),
                            )
                            changed++
                        }

                        else -> unchanged++
                    }
                }

                ScanResult(added, changed, unchanged)
            }
        } finally {
            _scanning.value = false
        }
    }

    // ─── The backup loop (§10) ──────────────────────────────────────────────

    /**
     * Scan, hash, ask, send — until nothing is left or the caller says stop.
     *
     * Each file is attempted at most once per run: real retries are spread
     * across runs by `attemptCount`, so a file that is failing does not burn a
     * whole batch window failing six times in a row.
     */
    suspend fun runBackup(isStopped: () -> Boolean = { false }): Result<BackupOutcome> =
        withContext(Dispatchers.IO) {
            if (!backupLock.tryLock()) {
                return@withContext Result.success(
                    BackupOutcome(0, 0, 0, 0, dao.pendingCount(MAX_ATTEMPTS), stoppedEarly = true),
                )
            }
            _running.value = true
            runCatching {
                check(settings.current.isPaired) { "This device is not paired with a server yet." }

                publish(BackupPhase.SCANNING)
                scan().getOrThrow()

                var uploaded = 0
                var deduped = 0
                var skipped = 0
                var failed = 0
                var diskFull = false
                val attempted = mutableSetOf<Long>()

                batchDone = 0
                batchTotal = dao.pendingCount(MAX_ATTEMPTS)

                while (!isStopped() && !diskFull) {
                    val batch = dao.pendingBatch(MAX_ATTEMPTS, CHECK_BATCH_SIZE)
                        .filter { it.id !in attempted }
                    if (batch.isEmpty()) break
                    attempted += batch.map { it.id }

                    // Rules the user set (§10.5) — parked, not failed, so
                    // flipping the toggle back brings them into the queue again.
                    val (eligible, ignored) = batch.partition(::isEligible)
                    for (asset in ignored) {
                        dao.update(asset.copy(state = AssetState.SKIPPED, lastError = null))
                        skipped++
                    }
                    if (eligible.isEmpty()) continue

                    // Hash whatever still needs it (§10.2).
                    publish(BackupPhase.HASHING)
                    val hashed = eligible.mapNotNull { asset ->
                        if (isStopped()) return@mapNotNull null
                        if (!asset.sha256.isNullOrBlank()) return@mapNotNull asset
                        try {
                            val digest = hasher.hash(Uri.parse(asset.contentUri))
                            asset.copy(sha256 = digest, state = AssetState.HASHED, lastError = null)
                                .also { dao.update(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A URI that has gone stale is the usual cause (§17).
                            dao.update(asset.markFailed(e))
                            failed++
                            null
                        }
                    }
                    if (hashed.isEmpty() || isStopped()) continue

                    // One round trip for up to 500 files (§10.3).
                    publish(BackupPhase.CHECKING)
                    val missing = checkMissing(hashed.mapNotNull { it.sha256 }).toSet()

                    val alreadyThere = hashed.filter { it.sha256 !in missing }
                    if (alreadyThere.isNotEmpty()) {
                        dao.markVerified(alreadyThere.map { it.id })
                        deduped += alreadyThere.size
                        batchDone += alreadyThere.size
                    }

                    val toSend = hashed.filter { it.sha256 in missing }
                    if (toSend.isNotEmpty()) dao.markChecked(toSend.map { it.id })

                    for (asset in toSend) {
                        if (isStopped()) break
                        val fresh = dao.findById(asset.id) ?: continue
                        try {
                            // The batch check above already asked about this
                            // hash; asking again per file would undo the whole
                            // point of §10.3's batching.
                            uploadInternal(fresh, alreadyChecked = true)
                            uploaded++
                            batchDone++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // No room left means every file after this one
                            // fails too. Stop, and say it once. This file is
                            // not counted as failed — it is waiting, and
                            // `remaining` already says so.
                            if (e is ApiException && e.isDiskFull) {
                                Log.w(TAG, "server is out of room: ${e.message}")
                                diskFull = true
                                break
                            }

                            failed++
                            batchDone++
                        }
                    }
                }

                // "The server is out of space" is a claim about now, not a
                // memory. A run that reached the end without being turned away
                // has disproved it — otherwise the banner would sit there until
                // the next actual upload, which for a fully deduped library
                // never comes.
                if (!diskFull) _serverFull.value = null

                BackupOutcome(
                    uploaded = uploaded,
                    deduped = deduped,
                    skipped = skipped,
                    failed = failed,
                    remaining = dao.pendingCount(MAX_ATTEMPTS),
                    stoppedEarly = isStopped() || diskFull,
                    serverFull = diskFull,
                )
            }.also {
                _progress.value = null
                _running.value = false
                backupLock.unlock()
            }
        }

    /** Batched dedupe probe. Splits oversized lists to respect §9's 500 cap. */
    private suspend fun checkMissing(hashes: List<String>): List<String> {
        if (hashes.isEmpty()) return emptyList()
        val api = apiProvider.api()
        return hashes.chunked(CHECK_BATCH_SIZE).flatMap { chunk ->
            withRetry { apiCall(json) { api.check(CheckRequest(chunk)) }.data.missing }
        }
    }

    private fun isEligible(asset: LocalAsset): Boolean {
        val prefs = settings.current
        if (!asset.isVideo) return true
        if (!prefs.includeVideos) return false
        if (prefs.maxVideoMb <= 0) return true
        return asset.sizeBytes <= prefs.maxVideoMb * 1024L * 1024L
    }

    // ─── Single-file upload (§10.2 – §10.4) ─────────────────────────────────

    suspend fun uploadNextPending(): Result<String> {
        val next = withContext(Dispatchers.IO) { dao.nextPending(MAX_ATTEMPTS) }
            ?: return Result.failure(IllegalStateException("Nothing left to upload."))
        return upload(next.id)
    }

    suspend fun upload(assetId: Long): Result<String> = withContext(Dispatchers.IO) {
        val asset = dao.findById(assetId)
            ?: return@withContext Result.failure(IllegalStateException("Asset $assetId is gone."))
        try {
            batchDone = 0
            batchTotal = 1
            Result.success(uploadInternal(asset))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _progress.value = null
        }
    }

    /**
     * Hash → check → session → chunks → complete, for one file.
     *
     * Marks the row FAILED and rethrows on error, so the caller can count it
     * without having to interpret the exception itself. A full server is the one
     * exception — it parks the row instead of failing it.
     */
    private suspend fun uploadInternal(input: LocalAsset, alreadyChecked: Boolean = false): String {
        var asset = input
        val api = apiProvider.api()
        val uri = Uri.parse(asset.contentUri)

        try {
            if (asset.sha256.isNullOrBlank()) {
                val digest = hasher.hash(uri)
                asset = asset.copy(sha256 = digest, state = AssetState.HASHED, lastError = null)
                dao.update(asset)
            }
            val sha = asset.sha256!!

            if (!alreadyChecked) {
                val missing = withRetry {
                    apiCall(json) { api.check(CheckRequest(listOf(sha))) }.data.missing
                }
                Log.d(TAG, "check ${asset.filename}: server ${if (sha in missing) "wants" else "already has"} it")
                asset = asset.copy(state = AssetState.CHECKED)
                dao.update(asset)
            }

            val session = withRetry {
                apiCall(json) {
                    api.createUpload(
                        CreateUploadRequest(
                            sha256 = sha,
                            sizeBytes = asset.sizeBytes,
                            filename = asset.filename,
                            mimeType = asset.mimeType,
                            capturedAt = asset.capturedAt,
                            width = asset.width,
                            height = asset.height,
                            durationMs = asset.durationMs,
                            orientation = asset.orientation,
                        ),
                    )
                }.data
            }

            if (session.alreadyExists && session.assetId != null) {
                asset = asset.copy(
                    state = AssetState.VERIFIED,
                    remoteId = session.assetId,
                    lastError = null,
                )
                dao.update(asset)
                return session.assetId
            }

            val uploadId = session.uploadId
                ?: throw IllegalStateException("Server opened no session and claimed no duplicate.")

            asset = asset.copy(state = AssetState.UPLOADING)
            dao.update(asset)

            var offset = session.receivedBytes
            val throughput = Throughput()
            publish(BackupPhase.UPLOADING, asset.filename, offset, asset.sizeBytes, 0)

            while (offset < asset.sizeBytes) {
                val length = min(CHUNK_SIZE, asset.sizeBytes - offset)
                val end = offset + length - 1
                val contentRange = "bytes $offset-$end/${asset.sizeBytes}"

                offset = try {
                    withRetry {
                        val body = ChunkRequestBody(
                            resolver = context.contentResolver,
                            uri = uri,
                            offset = offset,
                            length = length,
                            mimeType = asset.mimeType,
                        )
                        apiCall(json) { api.uploadChunk(uploadId, contentRange, body) }.data.receivedBytes
                    }
                } catch (e: ApiException) {
                    if (!e.isRecoverable) throw e
                    // The server is the authority on what it holds — believe it
                    // and carry on from there rather than starting over.
                    Log.w(TAG, "resync ${asset.filename}: ${e.code}, resuming at ${e.receivedBytes}")
                    e.receivedBytes ?: 0L
                }

                publish(
                    BackupPhase.UPLOADING,
                    asset.filename,
                    offset,
                    asset.sizeBytes,
                    throughput.record(length),
                )
            }

            val completed = withRetry { apiCall(json) { api.completeUpload(uploadId) }.data }

            asset = asset.copy(
                state = AssetState.VERIFIED,
                remoteId = completed.assetId,
                lastError = null,
            )
            dao.update(asset)
            // Bytes landed, so whatever the server said last time about being
            // full is no longer true.
            _serverFull.value = null
            return completed.assetId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val apiError = e as? ApiException

            // A full server is not this file's fault. Spending one of its six
            // attempts would mean that after a few runs against a full disk the
            // photo is stuck FAILED even once room is made — so the row is left
            // ready to go and only the reason is recorded. Done here rather than
            // in the batch loop so a single-file upload raises the flag too.
            if (apiError?.isDiskFull == true) {
                _serverFull.value = ServerFull(apiError.freeBytes, apiError.requiredBytes)
                dao.update(asset.copy(state = AssetState.CHECKED, lastError = e.message))
                Log.w(TAG, "no room for ${asset.filename}: ${e.message}")
                throw e
            }

            // A hash mismatch means our cached digest describes bytes that are no
            // longer on disk, so drop it and let the next attempt recompute.
            dao.update(asset.markFailed(e, clearHash = apiError?.code == "HASH_MISMATCH"))
            Log.e(TAG, "upload failed for ${asset.filename}", e)
            throw e
        }
    }

    // ─── Retry policy (§10.4) ───────────────────────────────────────────────

    /**
     * Retries transient trouble with exponential backoff and jitter. Recoverable
     * protocol errors (RANGE_GAP, SESSION_RESET) are rethrown immediately — the
     * caller resyncs rather than repeating a request the server already answered.
     */
    private suspend fun <T> withRetry(attempts: Int = CHUNK_ATTEMPTS, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(attempts) { attempt ->
            val failure = try {
                return block()
            } catch (e: ApiException) {
                if (e.isRecoverable || e.isPermanent) throw e
                e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                e
            } catch (e: SerializationException) {
                // A truncated or empty body reads as a parse error, not an
                // IOException. On a flaky link that is transient, so it earns a
                // retry rather than failing the whole file.
                e
            }
            last = failure
            Log.w(TAG, "retry ${attempt + 1}/$attempts after ${failure::class.java.name}")
            if (attempt < attempts - 1) delay(backoffMillis(attempt))
        }
        throw last ?: IOException("Retries exhausted")
    }

    private fun backoffMillis(attempt: Int): Long {
        val exponential = min(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS)
        // Half fixed, half jitter — enough spread that a flaky link does not
        // sync every retry into the same instant.
        return exponential / 2 + Random.nextLong(exponential / 2 + 1)
    }

    // ─── Plumbing ───────────────────────────────────────────────────────────

    private fun publish(
        phase: BackupPhase,
        filename: String? = null,
        sentBytes: Long = 0,
        fileBytes: Long = 0,
        rate: Long = 0,
    ) {
        _progress.value = BackupProgress(
            phase = phase,
            done = batchDone,
            total = batchTotal,
            filename = filename,
            sentBytes = sentBytes,
            fileBytes = fileBytes,
            bytesPerSecond = rate,
        )
    }

    private fun LocalAsset.markFailed(error: Throwable, clearHash: Boolean = false) = copy(
        state = AssetState.FAILED,
        sha256 = if (clearHash) null else sha256,
        attemptCount = attemptCount + 1,
        lastError = error.message ?: error::class.simpleName,
    )

    private fun ScannedMedia.toLocalAsset(capturedAt: Long) = LocalAsset(
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        relativePath = relativePath,
        filename = filename,
        sizeBytes = sizeBytes,
        dateModified = dateModified,
        capturedAt = capturedAt,
        mimeType = mimeType,
        durationMs = durationMs,
        width = width,
        height = height,
        orientation = orientation,
        sha256 = null,
        state = AssetState.DISCOVERED,
        remoteId = null,
    )

    companion object {
        private const val TAG = "KadrBackup"

        /** §10.4 says 4–8 MB; 4 MB keeps a retry cheap on flaky Wi-Fi. */
        const val CHUNK_SIZE = 4L * 1024 * 1024

        /** §9 caps a check call at 500 hashes. */
        const val CHECK_BATCH_SIZE = 500

        /** §10.4: six attempts, then the row is FAILED and shown in the UI. */
        const val MAX_ATTEMPTS = 6

        private const val CHUNK_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}

/** Smoothed transfer rate for the notification line (§10.6). */
private class Throughput {
    private var lastAt = System.nanoTime()
    private var smoothed = 0.0

    fun record(bytes: Long): Long {
        val now = System.nanoTime()
        val seconds = (now - lastAt) / 1_000_000_000.0
        lastAt = now
        if (seconds <= 0.0) return smoothed.toLong()

        val instant = bytes / seconds
        smoothed = if (smoothed == 0.0) instant else smoothed * 0.7 + instant * 0.3
        return smoothed.toLong()
    }
}
