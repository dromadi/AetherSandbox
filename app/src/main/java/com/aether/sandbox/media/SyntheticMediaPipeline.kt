package com.aether.sandbox.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Decode a selected local video to an owned Surface. No third-party camera interception. */
class SyntheticMediaPipeline(
    private val context: Context,
    private val uri: Uri,
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val ownsSurface: Boolean = true,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val playing = AtomicBoolean(true)
    private val requestedSeekUs = AtomicLong(-1)
    private val main = Handler(Looper.getMainLooper())
    private val thread = Thread({ decode() }, "aether-video").apply { start() }

    fun togglePlayback(): Boolean {
        val next = !playing.get()
        playing.set(next)
        return next
    }
    fun seekToStart() { requestedSeekUs.set(0) }
    override fun close() {
        running.set(false)
        thread.interrupt()
        if (Thread.currentThread() !== thread) {
            try { thread.join(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun status(message: String) {
        main.post { if (running.get()) onStatus(message) }
    }

    private fun decode() {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Video tidak memiliki track gambar")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format, surface, null, 0)
            codec.start()
            started = true
            status("Decoder: ${codec.name}")
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var clockOriginUs = -1L
            var pauseStartUs = -1L
            while (running.get()) {
                if (!playing.get()) {
                    if (pauseStartUs == -1L) pauseStartUs = nowUs()
                    Thread.sleep(20)
                    continue
                }
                if (pauseStartUs != -1L) {
                    if (clockOriginUs != -1L) clockOriginUs += nowUs() - pauseStartUs
                    pauseStartUs = -1
                }
                val seek = requestedSeekUs.getAndSet(-1)
                if (seek >= 0) {
                    extractor.seekTo(seek, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    inputEnded = false
                    clockOriginUs = -1
                }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outputIndex >= 0) {
                    // Surface output may have zero byte size. Use presentation timestamp, not buffer size.
                    if (clockOriginUs == -1L) clockOriginUs = nowUs() - info.presentationTimeUs
                    while (running.get() && playing.get() && requestedSeekUs.get() < 0
                        && nowUs() < clockOriginUs + info.presentationTimeUs) {
                        Thread.sleep(minOf(20L, maxOf(1L, (clockOriginUs + info.presentationTimeUs - nowUs()) / 1000)))
                    }
                    codec.releaseOutputBuffer(outputIndex, running.get() && playing.get())
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                        inputEnded = false
                        clockOriginUs = -1
                    }
                }
            }
        } catch (_: InterruptedException) {
            // User left the screen or TextureView was destroyed.
        } catch (error: Exception) {
            status("Video gagal: ${error.message}")
        } finally {
            if (started) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            if (ownsSurface) surface.release()
        }
    }

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
}
