package com.kadr.app

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.FileDescriptor
import java.io.OutputStream
import kotlin.random.Random

/**
 * Plants real JPEGs in MediaStore so tests never depend on whatever happens to
 * be on the device — and never touch anyone's actual photos.
 */
object TestMedia {

    /**
     * @param padTo appends random bytes after the JPEG end-of-image marker.
     * Decoders ignore trailing data, but the file length — and so the hash and
     * the chunk count — grow, which forces a multi-chunk upload without
     * allocating an enormous bitmap on the device.
     */
    fun seedJpeg(
        resolver: ContentResolver,
        displayName: String,
        width: Int,
        height: Int,
        padTo: Long? = null,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KadrTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "MediaStore refused to create $displayName" }

        requireNotNull(resolver.openOutputStream(uri)).use { raw ->
            val counting = CountingOutputStream(raw)
            val bitmap = noiseBitmap(width, height, seed = displayName.hashCode())
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, counting)
            } finally {
                bitmap.recycle()
            }
            counting.flush()

            if (padTo != null && counting.written < padTo) {
                val random = Random(displayName.hashCode())
                val buffer = ByteArray(64 * 1024)
                while (counting.written < padTo) {
                    random.nextBytes(buffer)
                    val want = minOf(buffer.size.toLong(), padTo - counting.written).toInt()
                    counting.write(buffer, 0, want)
                }
                counting.flush()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    /**
     * Encodes a real H.264 MP4 into MediaStore.
     *
     * The emulator ships no video and the dev machine has no ffmpeg, so the only
     * way to test playback against something genuine is to make one. Frames are
     * a sliding luma gradient with a slow chroma drift — cheap to generate and
     * obviously moving, which makes a stuck decoder visible.
     */
    fun seedVideo(
        resolver: ContentResolver,
        displayName: String,
        width: Int = 640,
        height: Int = 480,
        seconds: Int = 4,
        fps: Int = 24,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/KadrTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = requireNotNull(
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values),
        ) { "MediaStore refused to create $displayName" }

        requireNotNull(resolver.openFileDescriptor(uri, "w")).use { descriptor ->
            encodeVideo(descriptor.fileDescriptor, width, height, seconds, fps)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    private fun encodeVideo(
        descriptor: FileDescriptor,
        width: Int,
        height: Int,
        seconds: Int,
        fps: Int,
    ) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // A keyframe every second so seeking has somewhere to land.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val totalFrames = seconds * fps
        var frame = 0
        var inputDone = false

        try {
            while (true) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val presentationUs = frame * 1_000_000L / fps
                        if (frame >= totalFrames) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val image = requireNotNull(codec.getInputImage(inputIndex))
                            fillFrame(image, frame, totalFrames)
                            // Rows are padded to the codec's alignment, so the
                            // real frame is rowStride-based and usually larger
                            // than width*height*3/2. Passing the smaller number
                            // truncates every frame and the stream will not
                            // decode.
                            val size = codec.getInputBuffer(inputIndex)?.capacity()
                                ?: (width * height * 3 / 2)
                            codec.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                            frame++
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) continue
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }

                    else -> if (outputIndex >= 0) {
                        val encoded = requireNotNull(codec.getOutputBuffer(outputIndex))
                        // Codec config bytes belong in the track format, not the stream.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxing) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, encoded, info)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun fillFrame(image: Image, frame: Int, totalFrames: Int) {
        val width = image.width
        val height = image.height
        val shift = frame * 255 / totalFrames.coerceAtLeast(1)

        val luma = image.planes[0]
        for (y in 0 until height) {
            val rowStart = y * luma.rowStride
            for (x in 0 until width) {
                luma.buffer.position(rowStart + x * luma.pixelStride)
                luma.buffer.put((((x * 255 / width) + shift) % 256).toByte())
            }
        }

        // U and V are written sample by sample rather than a row at a time.
        // On a semi-planar layout the two planes interleave in the same memory,
        // so a bulk row write would stamp zeros over the neighbouring plane's
        // samples and the chroma would come out as garbage.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (planeIndex in 1..2) {
            val plane = image.planes[planeIndex]
            val value = (if (planeIndex == 1) 128 + shift / 4 else 128 - shift / 4)
                .coerceIn(0, 255)
                .toByte()
            for (y in 0 until chromaHeight) {
                val rowStart = y * plane.rowStride
                for (x in 0 until chromaWidth) {
                    plane.buffer.position(rowStart + x * plane.pixelStride)
                    plane.buffer.put(value)
                }
            }
        }
    }

    private const val TIMEOUT_US = 10_000L

    /**
     * The seed comes from the file name, which carries a timestamp — so two
     * seeded images are never byte-identical.
     *
     * Seeding from the dimensions alone looks tidier and is a trap: every run
     * produces the same bytes, so a file meant to be unknown to the server is
     * already sitting there from an earlier run, and a test asserting "the
     * server has never seen this" quietly passes for the wrong reason.
     */
    private fun noiseBitmap(width: Int, height: Int, seed: Int): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

internal class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var written: Long = 0
        private set

    override fun write(b: Int) {
        delegate.write(b)
        written++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        written += len
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
