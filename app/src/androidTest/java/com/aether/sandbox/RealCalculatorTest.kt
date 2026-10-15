package com.aether.sandbox

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aether.sandbox.core.ProxyActivity
import com.aether.sandbox.core.ProxyActivity1
import com.aether.sandbox.data.SandboxRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Unmodified upstream release APK, never installed into Android's PackageManager. */
@RunWith(AndroidJUnit4::class)
class RealCalculatorTest {
    @Test fun upstreamCalculatorCalculatesAndKeepsTwoGuestTasksIndependent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val apk = File(host.cacheDir, "real-calculator.apk")
        instrumentation.context.assets.open("real-calculator.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        val repo = SandboxRepository(host)
        val first = repo.importApk(Uri.fromFile(apk))
        val second = repo.importApk(Uri.fromFile(apk))
        val pkg = first.packageName
        fun report(): String = listOf(first, second).joinToString("\n") { record ->
            runCatching { File(repo.directory(record.id), "runtime-report.json").readText() }.getOrDefault("no runtime report")
        }
        fun launch(id: String, type: Class<*>) = host.startActivity(Intent(host, type)
            .putExtra(ProxyActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun button(id: String) = By.res(pkg, id)
        fun ready() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 25000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                device.findObject(By.res("android", "button1"))?.let { it.click(); device.waitForIdle() }
                if (device.hasObject(button("btn_2"))) return
                android.os.SystemClock.sleep(200)
            }
            val dump = File(host.cacheDir, "calculator-failure.xml"); device.dumpWindowHierarchy(dump)
            fail("Calculator did not launch: ${report()}\n${dump.readText()}")
        }
        fun click(id: String) {
            val target = device.wait(Until.findObject(button(id)), 5000)
            assertNotNull("Missing calculator button $id", target); target.click(); device.waitForIdle()
        }
        try {
            assertFalse(first.trustedFixture)
            assertEquals("com.simplemobiletools.calculator", pkg)
            launch(first.id, ProxyActivity::class.java); ready()
            click("btn_clear"); click("btn_2"); click("btn_plus"); click("btn_3"); click("btn_equals")
            assertTrue("2+3 must equal 5", device.wait(Until.hasObject(button("result").text("5")), 5000))
            launch(second.id, ProxyActivity1::class.java); ready()
            click("btn_clear"); click("btn_7"); click("btn_multiply"); click("btn_8"); click("btn_equals")
            assertTrue("7x8 must equal 56", device.wait(Until.hasObject(button("result").text("56")), 5000))
            assertTrue(repo.isRunning(first.id)); assertTrue(repo.isRunning(second.id))
            repo.stop(first.id)
            assertTrue(repo.isRunning(second.id))
            repo.stop(second.id)
            launch(first.id, ProxyActivity::class.java); ready()
            click("btn_clear"); click("btn_9"); click("btn_plus"); click("btn_1"); click("btn_equals")
            assertTrue(device.wait(Until.hasObject(button("result").text("10")), 5000))
        } finally {
            runCatching { repo.stop(first.id) }; runCatching { repo.stop(second.id) }
            repo.delete(first.id); repo.delete(second.id); apk.delete()
        }
    }
}
