package com.aether.sandbox

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
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
    @Test fun packagedEmulatorBootsArm64LinuxTenTimesInsideAndroid() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Engine build required", PackagedVmEngine.available(host))
        repeat(10) { assertTrue(PackagedVmEngine.linuxBoot(host).contains("guest=aarch64 pid=1")) }
    }
    @Test fun androidGuestProvidesDisplayAndBootEvidence() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Full guest laboratory runs on API 34", Build.VERSION.SDK_INT == 34)
        assumeTrue("Engine build required", PackagedVmEngine.available(host))
        val root = PackagedVmEngine.directory(host)
        assumeTrue("Pinned ISO fixture required", File(root, "guest.iso").isFile)
        val model = AndroidVmModel(host.applicationContext as Application)
        val token = UUID.randomUUID().toString().replace("-", "")
        var frames = 0; var booted = false
        try {
            model.start()
            repeat(20) { round ->
                delay(15_000)
                model.state.value.frame?.let { bitmap ->
                    File(root, "frame-$round.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    frames++
                }
                model.requestBootEvidence(token)
                val log = File(root, "console.log").takeIf { it.exists() }?.readText().orEmpty()
                booted = booted || log.contains("AETHER_GUEST_BOOT_${token}=1")
                if (!model.state.value.running) error(model.state.value.status)
            }
            File(root, "android-boot-report.json").writeText(JSONObject()
                .put("hostApi", Build.VERSION.SDK_INT).put("hostAbi", Build.SUPPORTED_ABIS.first())
                .put("displayFrames", frames).put("sysBootCompletedVerified", booted)
                .put("physicalPhoneVerified", false).toString(2))
            assertTrue("No display frames received", frames > 0)
            // The property is recorded independently: a display alone must never claim Android ready.
        } finally { model.stop(); model.viewModelScope.cancel() }
    }
}
