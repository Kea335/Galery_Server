package com.kadr.app.data.remote

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

/**
 * Streams one slice of a media file straight from MediaStore to the socket.
 *
 * Nothing is buffered: a 4 GB video is sent 64 KB at a time, which is what keeps
 * the phone's heap flat no matter how large the file is.
 */
class ChunkRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
    mimeType: String,
) : RequestBody() {

    private val type: MediaType? = mimeType.toMediaTypeOrNull()

    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val stream = resolver.openInputStream(uri)
            ?: throw IOException("MediaStore would not open $uri")

        stream.use { input ->
            // InputStream.skip is allowed to skip fewer bytes than asked for,
            // so keep going until the cursor is really where we want it.
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0L) throw IOException("Could not seek to $offset in $uri")
                skipped += n
            }

            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0L) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, want)
                if (read == -1) {
                    throw IOException("File ended $remaining bytes early — it changed under us")
                }
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}
