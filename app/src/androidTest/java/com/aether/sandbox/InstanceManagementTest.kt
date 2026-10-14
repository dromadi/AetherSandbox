package com.aether.sandbox

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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

@RunWith(AndroidJUnit4::class)
class InstanceManagementTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host get() = instrumentation.targetContext
    private fun sample(repository: SandboxRepository): com.aether.sandbox.data.SandboxRecord {
        val file = File(host.cacheDir, "management-sample.apk")
        instrumentation.context.assets.open("sampleapp.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
        return try { repository.importApk(Uri.fromFile(file)) } finally { file.delete() }
    }
    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) fail(message)
            SystemClock.sleep(50)
        }
    }

    @Test fun exportedLauncherRejectsForgedShortcut() {
        val id = java.util.UUID.randomUUID().toString()
        val valid = com.aether.sandbox.core.ShortcutGate.intent(host, id)
        try {
            assertTrue(com.aether.sandbox.core.ShortcutGate.accepts(host, valid))
            assertFalse(com.aether.sandbox.core.ShortcutGate.accepts(host,
                Intent(host, MainActivity::class.java).putExtra(ProxyActivity.EXTRA_ID, id)))
            assertFalse(com.aether.sandbox.core.ShortcutGate.accepts(host,
                Intent(valid).putExtra("aether_shortcut_token", "forged")))
            com.aether.sandbox.core.ShortcutGate.revoke(host, id)
            assertFalse(com.aether.sandbox.core.ShortcutGate.accepts(host, valid))
        } finally { com.aether.sandbox.core.ShortcutGate.revoke(host, id) }
    }

    @Test fun duplicateStartsCleanAndResetPreservesApkAndProfile() {
        val repo = SandboxRepository(host)
        val a = sample(repo)
        var b: com.aether.sandbox.data.SandboxRecord? = null
        try {
            val files = File(repo.directory(a.id), "data/files").apply { mkdirs() }
            File(files, "account.txt").writeText("first-account")
            host.getSharedPreferences("guest_${a.id}_login", 0).edit().putString("user", "first").commit()
            repo.rename(a.id, "Akun kerja")
            b = repo.duplicate(a.id)
            assertNotEquals(a.id, b.id)
            assertNotEquals(a.testAndroidId, b.testAndroidId)
            assertEquals(a.sha256, b.sha256)
            assertFalse(File(repo.directory(b.id), "data/files/account.txt").exists())
            assertEquals("Akun kerja • salinan", repo.find(b.id).instanceName)
            repo.resetData(a.id)
            assertTrue(repo.apk(a).exists())
            assertFalse(files.exists())
            assertEquals(a.testAndroidId, repo.find(a.id).testAndroidId)
            assertNull(host.getSharedPreferences("guest_${a.id}_login", 0).getString("user", null))
            assertTrue(repo.runtimeReport(a.id).contains("com.aether.sample.SampleLauncher"))
        } finally { b?.let { repo.delete(it.id) }; repo.delete(a.id) }
    }

    @Test fun stoppingOneProcessKillsItsWorkerAndLeavesOtherInstanceAlive() {
        val repo = SandboxRepository(host)
        val a = sample(repo)
        val b = sample(repo)
        val first = File(repo.directory(a.id), "data/files").apply { mkdirs() }
        val second = File(repo.directory(b.id), "data/files").apply { mkdirs() }
        File(first, "start-worker").writeText("1"); File(second, "start-worker").writeText("1")
        val heartbeatA = File(first, "heartbeat.txt")
        val heartbeatB = File(second, "heartbeat.txt")
        try {
            host.startActivity(Intent(host, ProxyActivity::class.java).putExtra(ProxyActivity.EXTRA_ID, a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitCondition("First worker did not start") { heartbeatA.exists() && heartbeatA.length() > 0 }
            // Alpha.7 launches a real child Activity after Application startup. Wait for that
            // launch to settle before requesting the second task, as a user switching apps does.
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("First guest UI did not become ready", device.wait(Until.hasObject(By.text("Sample account screen")), 15_000))
            device.waitForIdle()
            host.startActivity(Intent(host, ProxyActivity1::class.java).putExtra(ProxyActivity.EXTRA_ID, b.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitCondition("Second worker did not start") { heartbeatB.exists() && heartbeatB.length() > 0 }
            assertTrue(repo.isRunning(a.id)); assertTrue(repo.isRunning(b.id))
            repo.stop(a.id)
            val stopped = heartbeatA.readText()
            val live = heartbeatB.readText()
            awaitCondition("Other instance stopped unexpectedly") { heartbeatB.readText() != live }
            SystemClock.sleep(150)
            assertEquals(stopped, heartbeatA.readText())
            assertTrue(repo.isRunning(b.id))
            assertFalse(repo.isRunning(a.id))
        } finally {
            runCatching { repo.stop(a.id) }; runCatching { repo.stop(b.id) }
            repo.delete(a.id); repo.delete(b.id)
        }
    }

    @Test fun hostCanStopGuestThatBlocksMainThreadDuringStartup() {
        val repo = SandboxRepository(host)
        val record = sample(repo)
        val files = File(repo.directory(record.id), "data/files").apply { mkdirs() }
        File(files, "block-startup").writeText("1")
        try {
            host.startActivity(Intent(host, ProxyActivity::class.java).putExtra(ProxyActivity.EXTRA_ID, record.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitCondition("Application did not begin") { File(files, "started.txt").exists() }
            repo.stop(record.id)
            assertFalse(repo.isRunning(record.id))
        } finally { runCatching { repo.stop(record.id) }; repo.delete(record.id) }
    }
}
