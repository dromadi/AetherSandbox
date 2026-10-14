package com.aether.sandbox.core

import android.app.Application
import android.content.Context
import com.aether.sandbox.data.SandboxRecord
import com.aether.sandbox.data.SandboxRepository
import dalvik.system.DexClassLoader
import java.io.File

/**
 * A loaded third-party (non-fixture) guest: its own classes and resources, with its [Application]
 * subclass instantiated and attached to a per-instance [VirtualContext].
 *
 * Milestone 1 of general-app support. This runs the guest's code and Application lifecycle in an
 * isolated storage/resource context; it does NOT yet launch the guest's Activities, Services or
 * Providers (that needs ActivityManager/Binder interception). The guest shares the host UID, so
 * this is cooperative virtualization, not a security sandbox.
 */
data class GeneralGuest(
    val context: VirtualContext,
    val application: Application,
    val components: com.aether.sandbox.data.GuestComponents,
)

object GeneralGuestLoader {
    fun load(host: Context, record: SandboxRecord, repository: SandboxRepository): GeneralGuest {
        val info = repository.verifyIntegrity(record)
        val components = repository.guestComponents(record)
        val apk = repository.apk(record)
        val appInfo = requireNotNull(info.applicationInfo) { "APK tidak memiliki ApplicationInfo" }
        val resources = host.packageManager.getResourcesForApplication(appInfo)
        val dataRoot = File(repository.directory(record.id), "data").apply { mkdirs() }
        val optimized = File(host.codeCacheDir, record.id).apply { mkdirs() }
        // Parent is the boot classloader (framework only), so guest classes do not see host app
        // classes and the guest's own bundled libraries win over the host's.
        val loader = DexClassLoader(apk.path, optimized.path, appInfo.nativeLibraryDir,
            ClassLoader.getSystemClassLoader().parent)
        val context = VirtualContext(host, dataRoot, record.packageName, record.id, loader, resources, appInfo)

        val className = appInfo.className ?: Application::class.java.name
        val application = loader.loadClass(className).asSubclass(Application::class.java)
            .getDeclaredConstructor().newInstance()
        attach(application, context)
        context.application = application
        application.onCreate()

        return GeneralGuest(context, application, components)
    }

    /** Calls the protected Application.attachBaseContext(Context) through reflection. */
    private fun attach(application: Application, base: Context) {
        val method = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        method.isAccessible = true
        method.invoke(application, base)
    }
}
