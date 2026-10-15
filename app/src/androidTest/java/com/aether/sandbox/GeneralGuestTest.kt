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

            val prepared = GeneralGuestLoader.prepare(host, first, repository)
            lateinit var guest: com.aether.sandbox.core.GeneralGuest
            instrumentation.runOnMainSync { guest = GeneralGuestLoader.start(prepared) }
            assertEquals("com.aether.sample.SampleApplication", guest.application.javaClass.name)
            // Component registry: two activities, one service, one receiver; launcher is not the first manifest Activity.
            val components = guest.components
            assertEquals("com.aether.sample.SampleLauncher", components.launcher)
            assertTrue(components.activities.containsAll(
                listOf("com.aether.sample.SampleActivity", "com.aether.sample.SampleSecondActivity")))
            assertTrue(components.services.contains("com.aether.sample.SampleService"))
            assertTrue(components.receivers.contains("com.aether.sample.SampleReceiver"))
            assertTrue(components.providers.contains("com.aether.sample.SampleInitProvider"))
            assertEquals(1, guest.providers.size)
            assertEquals("provider:com.aether.sample",
                File(repository.directory(first.id), "data/files/provider.txt").readText())
            assertFalse(File(repository.directory(second.id), "data/files/provider.txt").exists())
            val resolver = guest.context.contentResolver
            val uri = Uri.parse("content://com.aether.sample.init/state")
            fun value(): String = resolver.query(uri, null, null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getString(0)
            }
            assertEquals("empty", value())
            assertEquals(uri, resolver.insert(uri, android.content.ContentValues().apply { put("value", "alpha") }))
            assertEquals("alpha", value())
            assertEquals("alpha", resolver.call(uri, "state", null, null)!!.getString("value"))
            assertEquals(1, resolver.update(uri, android.content.ContentValues().apply { put("value", "beta") }, null, null))
            assertEquals("beta", value())
            assertFalse(host.getSharedPreferences("guest_${second.id}_provider-data", 0).contains("value"))
            assertEquals(1, resolver.delete(uri, null, null)); assertEquals("empty", value())
            // Resources resolve against the guest APK, not the host.
            assertEquals("Aether Sample App", guest.context.getString(
                guest.context.resources.getIdentifier("sample_label", "string", "com.aether.sample")))

            // Application.onCreate wrote its marker into this instance's private storage only.
            val markerFirst = File(repository.directory(first.id), "data/files/started.txt")
            assertEquals("native-library-loaded", File(repository.directory(first.id), "data/files/native.txt").readText())
            assertEquals(File(repository.directory(first.id), "data").path, guest.context.applicationInfo.dataDir)
            assertTrue("Application.onCreate did not run", markerFirst.exists())
            assertEquals("onCreate:com.aether.sample", markerFirst.readText())
            assertFalse("Instances must not share storage",
                File(repository.directory(second.id), "data/files/started.txt").exists())
            guest.providers.forEach { it.shutdown() }
            runCatching { guest.application.onTerminate() }
            guest.framework.close()
        } finally {
            repository.list().filter { it.id == first.id || it.id == second.id }.forEach { repository.delete(it.id) }
            source.delete()
        }
    }
}
