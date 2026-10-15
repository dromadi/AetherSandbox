package com.aether.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.sandbox.vm.VmProbeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aether.sandbox.vm.VmLabActivity

@RunWith(AndroidJUnit4::class)
class VmProbeTest {
    @Test fun labScreenRunsProbeAndShowsMeasuredResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, VmLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val button = device.wait(Until.findObject(By.text("Periksa perangkat")), 10000)
        assertNotNull(button)
        button.click()
        assertTrue(device.wait(Until.hasObject(By.text("Uji kode native RW→RX berhasil")), 15000))
        device.pressBack()
    }
    @Test fun nativeProbeRunsOutsideHostWithoutClaimingAndroidVmReadiness() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(2) {
            val report = VmProbeClient.run(host)
            assertEquals(report.toString(), 42, report.getInt("jitReturn"))
            assertEquals(0, report.getInt("jitErrno"))
            assertNotEquals(android.os.Process.myPid(), report.getInt("probePid"))
            assertEquals(android.os.Process.myUid(), report.getInt("uid"))
            assertTrue(report.getLong("totalMemoryBytes") > 0)
            assertTrue(report.getLong("freeStorageBytes") > 0)
            assertFalse(report.getBoolean("qemuPackaged"))
            assertFalse(report.getBoolean("androidGuestBooted"))
            assertFalse(report.getBoolean("physicalDeviceVerified"))
        }
    }
}
