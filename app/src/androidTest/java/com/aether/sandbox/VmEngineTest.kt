package com.aether.sandbox

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import com.aether.sandbox.vm.AndroidVmActivity
import com.aether.sandbox.vm.AndroidVmModel
import com.aether.sandbox.vm.PackagedVmEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VmEngineTest {
    private fun engineRequired() = InstrumentationRegistry.getArguments().getString("requireVmEngine") == "true"
    private fun engine(context: android.content.Context) {
        if (engineRequired()) assertTrue("Packaged engines must be executable", PackagedVmEngine.available(context))
        else assumeTrue("Engine build required", PackagedVmEngine.available(context))
    }
    @Test fun packagedEmulatorBootsArm64LinuxTenTimesInsideAndroid() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        engine(host)
        repeat(10) { assertTrue(PackagedVmEngine.linuxBoot(host).contains("guest=aarch64 pid=1")) }
    }
    @Test fun androidGuestProvidesDisplayAndBootEvidence() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Full guest laboratory runs on API 34", Build.VERSION.SDK_INT == 34)
        engine(host)
        val root = PackagedVmEngine.directory(host)
        if (engineRequired()) assertTrue("Pinned ISO fixture required", File(root, "guest.iso").isFile)
        else assumeTrue("Pinned ISO fixture required", File(root, "guest.iso").isFile)
        // Exercise the production checksum, bounded ISO reader and atomic replacement,
        // not just the host-side bsdtar fixture extraction.
        PackagedVmEngine.importIso(host, android.net.Uri.fromFile(File(root, "guest.iso")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(host, AndroidVmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AndroidVmActivity
        val model = withContext(Dispatchers.Main) { ViewModelProvider.create(activity)[AndroidVmModel::class.java] }
        val token = UUID.randomUUID().toString().replace("-", "")
        var frames = 0; var booted = false
        try {
            // Reproduce the old read-interrupted crash while a serial drain is active.
            repeat(2) {
                model.start()
                delay(2_000)
                withContext(Dispatchers.Main) { model.stop() }
                withTimeout(10_000) { model.awaitStopped() }
                assertFalse("Stop must clear the running state", model.state.value.running)
            }
            model.start()
            for (round in 0 until 30) {
                delay(30_000)
                model.state.value.frame?.let { bitmap ->
                    File(root, "frame-$round.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    frames++
                }
                if (round == 5) model.requestDiagnostics(token)
                model.requestBootEvidence(token)
                delay(1_000)
                val log = File(root, "console.log").takeIf { it.exists() }?.readText().orEmpty()
                booted = booted || log.contains("AETHER_GUEST_BOOT_${token}=1")
                if (!model.state.value.running) error(model.state.value.status)
                if (booted) break
            }
            if (!booted) { model.requestDiagnostics(token); delay(5_000) }
            var settings = false
            if (booted) {
                model.requestSettingsEvidence(token, launch = true)
                for (attempt in 0 until 6) {
                    delay(15_000)
                    model.requestSettingsEvidence(token, launch = false)
                    delay(1_000)
                    val log = File(root, "console.log").readText()
                    settings = Regex("AETHER_GUEST_SETTINGS_${token}=.*com\\.android\\.settings").containsMatchIn(log)
                    if (settings) break
                }
                model.state.value.frame?.let { bitmap ->
                    File(root, "frame-settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            File(root, "android-boot-report.json").writeText(JSONObject()
                .put("hostApi", Build.VERSION.SDK_INT).put("hostAbi", Build.SUPPORTED_ABIS.first())
                .put("displayFrames", frames).put("sysBootCompletedVerified", booted)
                .put("settingsActivityResumed", settings)
                .put("physicalPhoneVerified", false).toString(2))
            assertTrue("No display frames received", frames > 0)
            assertTrue("Guest did not finish Android boot; see console.log and saved display frames", booted)
            assertTrue("Guest Settings Activity did not resume", settings)
        } finally { withContext(Dispatchers.Main) { model.stop(); activity.finish() } }
    }
}
