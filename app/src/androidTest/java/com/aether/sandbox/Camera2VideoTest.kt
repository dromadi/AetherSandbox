package com.aether.sandbox

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aether.sandbox.core.ProxyActivity
import com.aether.sandbox.data.SandboxRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class Camera2VideoTest {
    @Test fun normalGuestCamera2OutputsSelectedVideoToPreviewAndH264EncoderWithoutPhysicalCameraPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        // The host does not request CAMERA at all. Hardware cannot be used as a hidden fallback.
        assertEquals(PackageManager.PERMISSION_DENIED, host.checkSelfPermission(android.Manifest.permission.CAMERA))
        val apk = File(host.cacheDir, "camera2-sample.apk")
        val video = File(host.cacheDir, "camera2-video.mp4")
        instrumentation.context.assets.open("sampleapp.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        instrumentation.context.assets.open("camera-test.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val repo = SandboxRepository(host)
        val record = repo.importApk(Uri.fromFile(apk))
        try {
            host.startActivity(Intent(host, ProxyActivity::class.java)
                .putExtra(ProxyActivity.EXTRA_ID, record.id)
                .putExtra(ProxyActivity.EXTRA_CAMERA_URI, Uri.fromFile(video).toString())
                .putExtra(ProxyActivity.EXTRA_CAMERA2_VIDEO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Guest did not load: " + runCatching { File(repo.directory(record.id), "runtime-report.json").readText() }.getOrDefault("no report"),
                device.wait(Until.hasObject(By.text("Sample account screen")), 20000))
            val button = device.wait(Until.findObject(By.text(Pattern.compile("Kamera Camera2", Pattern.CASE_INSENSITIVE))), 5000)
            assertNotNull(button); button.click()
            val encoded = File(repo.directory(record.id), "data/files/camera2-encoded.txt")
            val error = File(repo.directory(record.id), "data/files/camera2-error.txt")
            val deadline = android.os.SystemClock.elapsedRealtime() + 30000
            var frames = 0L; var bytes = 0L
            while (android.os.SystemClock.elapsedRealtime() < deadline && frames < 35 && !error.exists()) {
                val counts = runCatching { encoded.readText().split(':').map(String::toLong) }.getOrNull()
                if (counts?.size == 2) { frames = counts[0]; bytes = counts[1] }
                android.os.SystemClock.sleep(100)
            }
            assertFalse(if (error.exists()) error.readText() else "", error.exists())
            assertTrue("Expected video looping into encoder, got $frames frames / $bytes bytes", frames >= 35 && bytes > 1000)
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Sample account screen")), 10000))
            // Closing a session must preserve guest navigation; stopping releases the whole process.
            assertTrue(repo.isRunning(record.id))
            repo.stop(record.id); assertFalse(repo.isRunning(record.id))
        } finally {
            runCatching { repo.stop(record.id) }; repo.delete(record.id); apk.delete(); video.delete()
        }
    }
}
