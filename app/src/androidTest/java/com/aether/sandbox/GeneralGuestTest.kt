package com.aether.sandbox

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.sandbox.core.GeneralGuestLoader
import com.aether.sandbox.data.SandboxRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GeneralGuestTest {
    @Test fun thirdPartyAppLoadsResourcesAndRunsItsApplicationInIsolatedStorage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.targetContext
        val source = File(host.cacheDir, "sampleapp.apk")
        instrumentation.context.assets.open("sampleapp.apk").use { input -> source.outputStream().use { input.copyTo(it) } }
        val repository = SandboxRepository(host)
        val first = repository.importApk(Uri.fromFile(source))
        val second = repository.importApk(Uri.fromFile(source))
        try {
            // A plain third-party app carries no GuestEntry metadata.
            assertFalse(first.trustedFixture)
            assertEquals("com.aether.sample", first.packageName)

            val guest = GeneralGuestLoader.load(host, first, repository)
            assertEquals("com.aether.sample.SampleApplication", guest.application.javaClass.name)
            assertEquals("com.aether.sample.SampleActivity", guest.launcherActivity)
            assertTrue(guest.activityCount >= 1)
            // Resources resolve against the guest APK, not the host.
            assertEquals("Aether Sample App", guest.context.getString(
                guest.context.resources.getIdentifier("sample_label", "string", "com.aether.sample")))

            // Application.onCreate wrote its marker into this instance's private storage only.
            val markerFirst = File(repository.directory(first.id), "data/files/started.txt")
            assertTrue("Application.onCreate did not run", markerFirst.exists())
            assertEquals("onCreate:com.aether.sample", markerFirst.readText())
            assertFalse("Instances must not share storage",
                File(repository.directory(second.id), "data/files/started.txt").exists())
            runCatching { guest.application.onTerminate() }
        } finally {
            repository.list().filter { it.id == first.id || it.id == second.id }.forEach { repository.delete(it.id) }
            source.delete()
        }
    }
}
