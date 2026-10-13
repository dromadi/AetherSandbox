package com.aether.sandbox.core

import android.content.Context
import com.aether.sandbox.api.GuestEntry
import com.aether.sandbox.data.SandboxRecord
import com.aether.sandbox.data.SandboxRepository
import dalvik.system.DexClassLoader
import java.io.File

data class LoadedGuest(val context: VirtualContext, val entry: GuestEntry)

object VirtualClassLoader {
    fun load(host: Context, record: SandboxRecord, repository: SandboxRepository): LoadedGuest {
        val info = repository.verifyFixture(record)
        val apk = repository.apk(record)
        val app = requireNotNull(info.applicationInfo).apply {
            sourceDir = apk.path
            publicSourceDir = apk.path
        }
        val resources = host.packageManager.getResourcesForApplication(app)
        val root = File(repository.directory(record.id), "data").apply { mkdirs() }
        val optimized = File(host.codeCacheDir, record.id).apply { mkdirs() }
        val loader = DexClassLoader(apk.path, optimized.path, null, GuestEntry::class.java.classLoader)
        val entry = loader.loadClass(requireNotNull(record.entryClass)).getDeclaredConstructor().newInstance()
        check(entry is GuestEntry) { "Fixture tidak mengimplementasikan GuestEntry" }
        return LoadedGuest(VirtualContext(host, root, record.packageName, record.id, loader, resources), entry)
    }
}
