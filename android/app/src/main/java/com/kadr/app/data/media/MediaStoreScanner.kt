package com.kadr.app.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class ScannedMedia(
    val mediaStoreId: Long,
    val contentUri: String,
    val relativePath: String,
    val filename: String,
    val sizeBytes: Long,
    /** Milliseconds, converted from the seconds MediaStore reports. */
    val dateModified: Long,
    /** Raw MediaStore value; zero when it does not know. Resolve with [MediaStoreScanner.resolveCapturedAt]. */
    val dateTaken: Long,
    val mimeType: String,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val orientation: Int,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val uri: Uri get() = Uri.parse(contentUri)
}

/**
 * Walks MediaStore for images and video (§10.1).
 *
 * MediaStore is treated as untrusted (§17): URIs go stale, `dateTaken` is
 * sometimes zero, and EXIF dates lie in their own way.
 *
 * The cursor walk deliberately does **not** resolve capture dates. Reading EXIF
 * means opening a stream per file, which is fine once but ruinous on every
 * re-scan of a 10,000 photo library — so the caller resolves it only for rows
 * it is actually going to write.
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    suspend fun scan(
        excludedFolders: Set<String>,
        onProgress: (scanned: Int) -> Unit = {},
    ): List<ScannedMedia> = withContext(Dispatchers.IO) {
        val out = ArrayList<ScannedMedia>(512)
        var seen = 0
        val bump: (Int) -> Unit = { count -> seen += count; onProgress(seen) }
        collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, excludedFolders, out, bump)
        collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, excludedFolders, out, bump)
        out
    }

    /**
     * §17's fallback chain: EXIF → MediaStore `dateTaken` → `dateModified` → now.
     */
    suspend fun resolveCapturedAt(media: ScannedMedia): Long = withContext(Dispatchers.IO) {
        if (!media.isVideo) {
            exifDate(media.uri)?.let { return@withContext it }
        }
        if (media.dateTaken > 0L) return@withContext media.dateTaken
        if (media.dateModified > 0L) return@withContext media.dateModified
        System.currentTimeMillis()
    }

    private suspend fun collect(
        collection: Uri,
        isVideo: Boolean,
        excludedFolders: Set<String>,
        out: MutableList<ScannedMedia>,
        onBatch: (Int) -> Unit,
    ) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(if (isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN)
            if (isVideo) {
                add(MediaStore.Video.Media.DURATION)
                // Video rotation only became queryable in API 29.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.MediaColumns.ORIENTATION)
                }
            } else {
                add(MediaStore.Images.Media.ORIENTATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        val cursor = context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        ) ?: return

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val takenCol = c.getColumnIndex(
                if (isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN,
            )
            val durationCol = if (isVideo) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            val orientationCol = c.getColumnIndex(
                if (isVideo) MediaStore.MediaColumns.ORIENTATION else MediaStore.Images.Media.ORIENTATION,
            )
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                c.getColumnIndex(MediaStore.MediaColumns.DATA)
            }

            var sinceLastReport = 0
            while (c.moveToNext()) {
                coroutineContext.ensureActive()

                val id = c.getLong(idCol)
                val rawPath = if (pathCol >= 0) c.getString(pathCol).orEmpty() else ""
                val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rawPath
                } else {
                    rawPath.substringBeforeLast('/', "") + "/"
                }

                if (isExcluded(relativePath, excludedFolders)) continue

                // A row with no size is a placeholder for a file that is still
                // being written, or one the provider cannot reach. Skip it; the
                // next scan will pick it up for real.
                val size = c.getLong(sizeCol)
                if (size <= 0L) continue

                out += ScannedMedia(
                    mediaStoreId = id,
                    contentUri = ContentUris.withAppendedId(collection, id).toString(),
                    relativePath = relativePath,
                    filename = c.getString(nameCol) ?: "unnamed-$id",
                    sizeBytes = size,
                    dateModified = c.getLong(modifiedCol) * 1000L,
                    dateTaken = if (takenCol >= 0) c.getLong(takenCol) else 0L,
                    mimeType = c.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*",
                    durationMs = if (durationCol >= 0) c.getLong(durationCol) else null,
                    width = if (widthCol >= 0) c.getInt(widthCol).takeIf { it > 0 } else null,
                    height = if (heightCol >= 0) c.getInt(heightCol).takeIf { it > 0 } else null,
                    orientation = if (orientationCol >= 0) c.getInt(orientationCol) else 0,
                )

                if (++sinceLastReport >= 200) {
                    onBatch(sinceLastReport)
                    sinceLastReport = 0
                }
            }
            if (sinceLastReport > 0) onBatch(sinceLastReport)
        }
    }

    private fun exifDate(uri: Uri): Long? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            exif.dateTimeOriginal ?: exif.dateTime
        }
    }.getOrNull()?.takeIf { it > 0L }

    private fun isExcluded(relativePath: String, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return false
        val path = relativePath.lowercase()
        return excluded.any { folder -> folder.isNotBlank() && path.contains(folder.lowercase()) }
    }

    companion object {
        /** Defaults from §10.1; the user can change them in Settings. */
        val DEFAULT_EXCLUDED_FOLDERS = setOf(".thumbnails", "WhatsApp", "Screenshots")
    }
}
