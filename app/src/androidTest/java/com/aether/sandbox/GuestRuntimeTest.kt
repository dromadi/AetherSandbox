package com.aether.sandbox

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.os.Process
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.sandbox.core.VirtualClassLoader
import com.aether.sandbox.core.ProxyActivity
import com.aether.sandbox.data.SandboxRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

@RunWith(AndroidJUnit4::class)
class GuestRuntimeTest {
    @Test fun proxyActivityRunsGuestInAnotherProcessAndPersistsCounter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.targetContext
        val source = File(host.cacheDir, "ui-fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { input -> source.outputStream().use { input.copyTo(it) } }
        val repository = SandboxRepository(host)
        val record = repository.importApk(Uri.fromFile(source))
        val device = UiDevice.getInstance(instrumentation)
        fun launch() = host.startActivity(Intent(host, ProxyActivity::class.java)
            .putExtra(ProxyActivity.EXTRA_ID, record.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            launch()
            val appeared = device.wait(Until.hasObject(By.textContains("Persistent counter: 0")), 15000)
            assertTrue(device.findObject(By.textStartsWith("Fixture gagal dimuat"))?.text ?: "Guest view did not appear", appeared)
            val process = host.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
                .first { it.processName == "${host.packageName}:virtual_process" }
            assertNotEquals(Process.myPid(), process.pid)
            assertEquals(Process.myUid(), process.uid)
            device.findObject(By.text("Increment isolated counter")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Persistent counter: 1")), 5000))
            device.pressBack()
            device.waitForIdle()
            launch()
            assertTrue("Counter did not persist on relaunch", device.wait(Until.hasObject(By.textContains("Persistent counter: 1")), 10000))
        } finally {
            device.pressBack()
            device.waitForIdle()
            repository.delete(record.id)
            source.delete()
        }
    }

    @Test fun fixtureLoadsAndInstancesKeepIndependentPersistentState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.targetContext
        val source = File(host.cacheDir, "fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        val repository = SandboxRepository(host)
        val first = repository.importApk(Uri.fromFile(source))
        val second = repository.importApk(Uri.fromFile(source))
        try {
            assertTrue(first.trustedFixture)
            assertFalse(repository.apk(first).canWrite())
            val a = VirtualClassLoader.load(host, first, repository)
            val b = VirtualClassLoader.load(host, second, repository)
            assertNotEquals(a.context.filesDir, b.context.filesDir)
            assertNotEquals(a.context.getDatabasePath("fixture.db"), b.context.getDatabasePath("fixture.db"))
            a.context.getSharedPreferences("counter", Context.MODE_PRIVATE).edit().putInt("count", 7).commit()
            assertEquals(0, b.context.getSharedPreferences("counter", Context.MODE_PRIVATE).getInt("count", 0))
            val reloaded = VirtualClassLoader.load(host, repository.find(first.id), repository)
            assertEquals(7, reloaded.context.getSharedPreferences("counter", Context.MODE_PRIVATE).getInt("count", 0))
            assertEquals(first.testAndroidId, SandboxRepository(host).find(first.id).testAndroidId)
            instrumentation.runOnMainSync {
                val view = reloaded.entry.createView(reloaded.context, mapOf("androidId" to first.testAndroidId, "instanceId" to first.id))
                assertNotNull(view)
                reloaded.entry.onDestroy()
            }
            assertTrue(a.context.getDatabasePath("fixture.db").exists())
            repository.delete(first.id)
            assertFalse(repository.directory(first.id).exists())
            assertEquals(0, host.getSharedPreferences("guest_${first.id}_counter", Context.MODE_PRIVATE).getInt("count", 0))
        } finally {
            repository.list().filter { it.id == first.id || it.id == second.id }.forEach { repository.delete(it.id) }
            source.delete()
        }
    }
    @Test fun corruptImportRollsBackAndIndexSurvives() {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SandboxRepository(host)
        val before = repository.list()
        val directories = repository.root.list().orEmpty().toSet()
        val file = File(host.cacheDir, "invalid.apk").apply { writeText("bad archive") }
        try {
            org.junit.Assert.assertThrows(Exception::class.java) { repository.importApk(Uri.fromFile(file)) }
            assertEquals(before, repository.list())
            assertEquals(directories, repository.root.list().orEmpty().toSet())
        } finally { file.delete() }
    }
}
