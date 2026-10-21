package com.aether.sandbox.media

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.aether.sandbox.api.VirtualCamera
import com.aether.sandbox.api.VirtualCameraFrame
import com.aether.sandbox.domain.YuvPacker

/**
 * Deterministic [VirtualCamera] backed by a local video. Frames are decoded in ByteBuffer mode
 * and copied to packed I420, one at a time: the decoder does not advance until the listener
 * returns, so no frame is dropped and the timestamps come from the file, not the clock.
 */
class VideoCameraSource(
    private val context: Context,
    private val uri: Uri,
) : VirtualCamera, AutoCloseable {
    private val lock = Any()
    private var worker: Thread? = null
    @Volatile private var generation = 0

    override fun description(): String = "Video uji: ${uri.lastPathSegment ?: uri}"

    override fun start(listener: VirtualCamera.FrameListener) {
        synchronized(lock) {
            stopLocked()
            val id = ++generation
            worker = Thread({ run(id, listener) }, "aether-virtual-camera").apply { start() }
        }
    }

    override fun stop() = synchronized(lock) { stopLocked() }

    override fun close() = stop()

    private fun stopLocked() {
        val current = worker ?: return
        worker = null
        generation++
        current.interrupt()
        // A listener may call stop() from its own callback; never join the current thread.
        if (current !== Thread.currentThread()) {
            current.join(STOP_TIMEOUT_MILLIS)
            if (current.isAlive) Log.w(TAG, "Camera thread did not stop within $STOP_TIMEOUT_MILLIS ms")
        }
    }

    private fun active(id: Int) = generation == id && !Thread.currentThread().isInterrupted

    private fun run(id: Int, listener: VirtualCamera.FrameListener) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Video tidak memiliki track gambar")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // Flexible YUV lets every decoder expose its output through android.media.Image.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var delivered = 0
            while (active(id)) {
                if (!inputEnded) {
                    val input = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val count = extractor.readSampleData(requireNotNull(decoder.getInputBuffer(input)), 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(input, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (output < 0) continue
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val frame = if (info.size > 0 || !end) {
                    decoder.getOutputImage(output)?.use { image -> pack(delivered, info.presentationTimeUs, image) }
                } else null
                decoder.releaseOutputBuffer(output, false)
                if (frame != null && active(id)) {
                    listener.onFrame(frame)
                    delivered++
                }
                if (end) {
                    if (active(id)) listener.onEnd(delivered)
                    return
                }
            }
        } catch (_: InterruptedException) {
            // stop() requested.
        } catch (error: Exception) {
            if (active(id)) runCatching { listener.onError(error) }
                .onFailure { Log.e(TAG, "Camera error listener failed", it) }
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pack(index: Int, timestampUs: Long, image: Image): VirtualCameraFrame {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val out = ByteArray(YuvPacker.i420Size(width, height))
        val (y, u, v) = image.planes
        var offset = YuvPacker.copyPlane(y.buffer, y.rowStride, y.pixelStride, crop.left, crop.top, width, height, out, 0)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        for (plane in listOf(u, v)) {
            offset = YuvPacker.copyPlane(plane.buffer, plane.rowStride, plane.pixelStride,
                crop.left / 2, crop.top / 2, chromaWidth, chromaHeight, out, offset)
        }
        return VirtualCameraFrame(index, timestampUs, width, height, out)
    }

    private companion object {
        const val TAG = "AetherCamera"
        const val TIMEOUT_US = 10_000L
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
