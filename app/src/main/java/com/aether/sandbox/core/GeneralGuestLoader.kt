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

data class PreparedGuest(val context: VirtualContext, val applicationClass: Class<out Application>,
    val components: com.aether.sandbox.data.GuestComponents)

object GeneralGuestLoader {
    /** IO-only preparation. The caller starts the Application on Android's main thread. */
    fun prepare(host: Context, record: SandboxRecord, repository: SandboxRepository): PreparedGuest {
        val info = repository.verifyIntegrity(record)
        val components = repository.guestComponents(record)
        val apk = repository.apk(record)
        val appInfo = android.content.pm.ApplicationInfo(requireNotNull(info.applicationInfo) { "APK tidak memiliki ApplicationInfo" })
        val resources = host.packageManager.getResourcesForApplication(appInfo)
        val dataRoot = File(repository.directory(record.id), "data").apply { mkdirs() }
        val optimized = File(host.codeCacheDir, record.id).apply { mkdirs() }
        // Parent is the boot classloader (framework only), so guest classes do not see host app
        // classes and the guest's own bundled libraries win over the host's.
        val native = com.aether.sandbox.domain.NativeLibraries.extract(apk,
            File(repository.directory(record.id), "native"), android.os.Build.SUPPORTED_ABIS.toList()
                .filter { it.contains("64") == android.os.Process.is64Bit() })
        appInfo.dataDir = dataRoot.path
        appInfo.nativeLibraryDir = native?.path
        val loader = DexClassLoader(apk.path, optimized.path, native?.path,
            ClassLoader.getSystemClassLoader().parent)
        val context = VirtualContext(host, dataRoot, record.packageName, record.id, loader, resources, appInfo)

        val className = appInfo.className ?: Application::class.java.name
        val applicationClass = loader.loadClass(className).asSubclass(Application::class.java)
        return PreparedGuest(context, applicationClass, components)
    }

    fun start(prepared: PreparedGuest): GeneralGuest {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { "Application harus dimulai di main thread" }
        val application = prepared.applicationClass.getDeclaredConstructor().newInstance()
        attach(application, prepared.context)
        prepared.context.application = application
        application.onCreate()
        return GeneralGuest(prepared.context, application, prepared.components)
    }

    /** Calls the protected Application.attachBaseContext(Context) through reflection. */
    private fun attach(application: Application, base: Context) {
        val method = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        method.isAccessible = true
        method.invoke(application, base)
    }
}
