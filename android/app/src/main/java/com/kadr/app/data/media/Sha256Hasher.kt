package com.kadr.app.data.media

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Streaming SHA-256 (§10.2). 64 KB buffers, never on the main thread, and
 * bounded to two concurrent files (§17) so hashing a pair of 4 GB videos cannot
 * saturate the disk or the CPU.
 */
@Singleton
class Sha256Hasher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val bounded = Dispatchers.IO.limitedParallelism(2)

    suspend fun hash(uri: Uri): String = withContext(bounded) {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("MediaStore would not open $uri")

        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }

        digest.digest().toHexString()
    }
}

private fun ByteArray.toHexString(): String {
    val hex = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        hex[i * 2] = digits[v ushr 4]
        hex[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(hex)
}
