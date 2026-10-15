package com.aether.sandbox.core

import android.app.Application
import android.content.Context
import com.aether.sandbox.data.SandboxRecord
import com.aether.sandbox.data.SandboxRepository
import dalvik.system.DexClassLoader
import java.io.File

/** Loaded Android guest with process-local package identity, Providers and Activity routing. Same host UID. */
data class GeneralGuest(
    val context: VirtualContext,
    val application: Application,
    val components: com.aether.sandbox.data.GuestComponents,
    val providers: List<android.content.ContentProvider>,
    val framework: GuestFrameworkBridge,
)

data class PreparedGuest(val context: VirtualContext, val applicationClass: Class<out Application>,
    val components: com.aether.sandbox.data.GuestComponents,
    val providers: List<android.content.pm.ProviderInfo>,
    val packageInfo: android.content.pm.PackageInfo, val intents: GuestIntentRegistry)

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
        appInfo.uid = android.os.Process.myUid()
        appInfo.dataDir = dataRoot.path
        appInfo.nativeLibraryDir = native?.path
        val loader = DexClassLoader(apk.path, optimized.path, native?.path,
            ClassLoader.getSystemClassLoader().parent)
        val context = VirtualContext(host, dataRoot, record.packageName, record.id, loader, resources, appInfo)

        val className = appInfo.className ?: Application::class.java.name
        val applicationClass = loader.loadClass(className).asSubclass(Application::class.java)
        val snapshot = repository.packageSnapshot(record).apply {
            applicationInfo = appInfo
            listOf(activities, services, receivers, providers).forEach { items -> items?.forEach { it.applicationInfo = appInfo } }
        }
        val intents = GuestIntentRegistry.read(host, appInfo, snapshot.activities.orEmpty().toList())
        return PreparedGuest(context, applicationClass, components, repository.providerRegistry(record), snapshot, intents)
    }

    fun start(prepared: PreparedGuest): GeneralGuest {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { "Application harus dimulai di main thread" }
        val framework = GuestFrameworkBridge.install(prepared.context, prepared.packageInfo, prepared.intents)
        val application: Application
        try {
            application = prepared.applicationClass.getDeclaredConstructor().newInstance()
            attach(application, prepared.context)
            prepared.context.application = application
        } catch (failure: Throwable) { framework.close(); throw failure }
        // Android initializes same-process providers after Application.attach, before onCreate.
        // Keep them process-local: authorities are NOT published to the host/system resolver.
        val providers = mutableListOf<android.content.ContentProvider>()
        try {
            val appInfo = prepared.context.applicationInfo
            val enabled = prepared.providers.filter { it.enabled && appInfo.enabled }
            check(enabled.all { it.processName == null || it.processName == appInfo.processName }) {
                "Provider dengan proses terpisah belum didukung"
            }
            for (source in enabled.sortedByDescending { it.initOrder }) {
                val provider = prepared.context.classLoader.loadClass(source.name)
                    .asSubclass(android.content.ContentProvider::class.java).getDeclaredConstructor().newInstance()
                providers += provider
                val info = android.content.pm.ProviderInfo(source).apply { applicationInfo = appInfo }
                framework.publish(provider, info)
                provider.attachInfo(prepared.context, info)
            }
            application.onCreate()
            return GeneralGuest(prepared.context, application, prepared.components, providers, framework)
        } catch (failure: Throwable) {
            providers.asReversed().forEach { runCatching { it.shutdown() } }
            framework.close()
            prepared.context.application = null
            throw failure
        }
    }

    /** Calls the protected Application.attachBaseContext(Context) through reflection. */
    private fun attach(application: Application, base: Context) {
        val method = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        method.isAccessible = true
        method.invoke(application, base)
    }
}
