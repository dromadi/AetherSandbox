package com.aether.sandbox

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aether.sandbox.api.VirtualCamera
import com.aether.sandbox.api.VirtualCameraFrame
import com.aether.sandbox.core.ProxyActivity
import com.aether.sandbox.data.SandboxRepository
import com.aether.sandbox.media.VideoCameraSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class VirtualCameraTest {
    private data class Sample(val index: Int, val timestampUs: Long, val width: Int, val height: Int, val crc: Long)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val host = instrumentation.targetContext
    private lateinit var video: File

    @Before fun copyVideo() {
        video = File(host.cacheDir, "camera-test.mp4")
        instrumentation.context.assets.open("camera-test.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
    }

    @After fun deleteVideo() { video.delete() }

    private fun capture(source: VirtualCamera, stopAfter: Int = Int.MAX_VALUE): Pair<List<Sample>, Int?> {
        val samples = mutableListOf<Sample>()
        val done = CountDownLatch(1)
        var ended: Int? = null
        var failure: Exception? = null
        source.start(object : VirtualCamera.FrameListener {
            override fun onFrame(frame: VirtualCameraFrame) {
                assertEquals(frame.i420.size, frame.width * frame.height * 3 / 2)
                samples += Sample(frame.index, frame.timestampUs, frame.width, frame.height,
                    CRC32().apply { update(frame.i420) }.value)
                if (samples.size == stopAfter) { source.stop(); done.countDown() }
            }
            override fun onEnd(frameCount: Int) { ended = frameCount; done.countDown() }
            override fun onError(error: Exception) { failure = error; done.countDown() }
        })
        assertTrue("Camera did not finish", done.await(30, TimeUnit.SECONDS))
        failure?.let { throw it }
        return samples to ended
    }

    @Test fun sameVideoProducesIdenticalFramesOnEveryRun() {
        val source = VideoCameraSource(host, Uri.fromFile(video))
        try {
            val (first, firstEnd) = capture(source)
            val (second, secondEnd) = capture(source)
            assertEquals(30, first.size)
            assertEquals(30, firstEnd)
            assertEquals(30, secondEnd)
            assertEquals((0 until 30).toList(), first.map { it.index })
            assertTrue(first.all { it.width == 160 && it.height == 120 })
            assertTrue("Timestamps must increase", first.zipWithNext().all { (a, b) -> b.timestampUs > a.timestampUs })
            assertTrue("Frames must differ over time", first.map { it.crc }.toSet().size > 1)
            assertEquals(first, second)
        } finally { source.close() }
    }

    @Test fun stopFromListenerEndsDeliveryAndRestartBeginsAtFirstFrame() {
        val source = VideoCameraSource(host, Uri.fromFile(video))
        try {
            val (partial, ended) = capture(source, stopAfter = 5)
            assertEquals(5, partial.size)
            assertNull(ended)
            val (full, _) = capture(source)
            assertEquals(partial, full.take(5))
        } finally { source.close() }
    }

    @Test fun fixtureReceivesCameraAndReportsStableFingerprint() {
        val source = File(host.cacheDir, "camera-fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { input -> source.outputStream().use { input.copyTo(it) } }
        val repository = SandboxRepository(host)
        val record = repository.importApk(Uri.fromFile(source))
        val device = UiDevice.getInstance(instrumentation)
        val button = Pattern.compile("Mulai kamera virtual", Pattern.CASE_INSENSITIVE)
        fun runCamera(run: Int): String {
            var target = device.findObject(By.text(button))
            if (target == null) device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1f)
            target = device.wait(Until.findObject(By.text(button)), 5000)
            assertNotNull("Camera button not visible", target)
            target.click()
            val result = device.wait(Until.findObject(By.textStartsWith("Kamera selesai #$run:")), 30000)
            assertNotNull(device.findObject(By.textStartsWith("Kamera gagal"))?.text ?: "Camera did not finish", result)
            return result.text.substringAfter(":").trim()
        }
        try {
            host.startActivity(Intent(host, ProxyActivity::class.java)
                .putExtra(ProxyActivity.EXTRA_ID, record.id)
                .putExtra(ProxyActivity.EXTRA_CAMERA_URI, Uri.fromFile(video).toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Guest view did not appear", device.wait(Until.hasObject(By.textContains("Persistent counter")), 15000))
            val first = runCamera(1)
            assertTrue(first, first.startsWith("30 frame • sidik "))
            assertEquals(first, runCamera(2))
        } finally {
            runCatching { repository.stop(record.id) }
            repository.delete(record.id)
            source.delete()
        }
    }
}
