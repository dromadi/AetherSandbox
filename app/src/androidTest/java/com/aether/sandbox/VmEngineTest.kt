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
    private fun bodyHasContent(bitmap: Bitmap): Boolean {
        // Exclude system bars: a resumed Activity with a blank white window is not usable UI.
        var samples = 0
        var foreground = 0
        for (y in bitmap.height / 5 until bitmap.height * 4 / 5 step 4) {
            for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step 4) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = android.graphics.Color.red(pixel) +
                    android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                if (brightness < 600) foreground++
                samples++
            }
        }
        return samples > 0 && foreground * 200 >= samples && foreground * 10 < samples * 9
    }
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
        var frames = 0; var booted = false; var stopCycles = 0; var profileApplied = false; var coreApplied = false
        try {
            // Reproduce the old read-interrupted crash while a serial drain is active.
            repeat(2) {
                model.start()
                delay(2_000)
                withContext(Dispatchers.Main) { model.stop() }
                withTimeout(10_000) { model.awaitStopped() }
                assertFalse("Stop must clear the running state", model.state.value.running)
                stopCycles++
            }
            model.start()
            for (round in 0 until 30) {
                delay(30_000)
                model.state.value.frame?.let { bitmap ->
                    File(root, "frame-$round.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    frames++
                }
                if (round == 5 || round == 17) model.requestDiagnostics(token)
                model.requestBootEvidence(token)
                delay(1_000)
                val log = File(root, "console.log").takeIf { it.exists() }?.readText().orEmpty()
                booted = booted || log.contains("AETHER_GUEST_BOOT_${token}=1")
                profileApplied = profileApplied || log.contains("AETHER_GUEST_PROFILE_${token}=extract")
                coreApplied = coreApplied || Regex("(?m)^AETHER_GUEST_FLAVOR_${token}=core\\r?$").containsMatchIn(log)
                if (!model.state.value.running) error(model.state.value.status)
                if (booted) break
            }
            if (!booted) { model.requestDiagnostics(token); delay(20_000) }
            var settings = false
            var rendered = false
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
                if (settings) {
                    for (attempt in 0 until 8) {
                        delay(15_000)
                        val bitmap = model.state.value.frame ?: continue
                        File(root, "frame-settings.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        rendered = bodyHasContent(bitmap)
                        if (rendered) break
                    }
                }
            }
            File(root, "android-boot-report.json").writeText(JSONObject()
                .put("hostApi", Build.VERSION.SDK_INT).put("hostAbi", Build.SUPPORTED_ABIS.first())
                .put("displayFrames", frames).put("sysBootCompletedVerified", booted)
                .put("stopCyclesVerified", stopCycles)
                .put("firstBootExtractProfileVerified", profileApplied)
                .put("coreGuestProfileVerified", coreApplied)
                .put("settingsActivityResumed", settings)
                .put("settingsDisplayRendered", rendered)
                .put("physicalPhoneVerified", false).toString(2))
            assertTrue("No display frames received", frames > 0)
            assertTrue("Guest did not apply the TCG first-boot profile", profileApplied)
            assertTrue("Guest did not apply the core profile or a package mount failed", coreApplied)
            assertTrue("Guest did not finish Android boot; see console.log and saved display frames", booted)
            assertTrue("Guest Settings Activity did not resume", settings)
            assertTrue("Settings resumed but its display remained blank", rendered)
        } finally { withContext(Dispatchers.Main) { model.stop(); activity.finish() } }
    }
}
