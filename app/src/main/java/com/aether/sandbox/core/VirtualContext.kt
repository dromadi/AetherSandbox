package com.aether.sandbox.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import com.aether.sandbox.domain.SandboxPaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Cooperative guest storage/resource context. Same UID as host; this is NOT a security boundary. */
class VirtualContext(
    host: Context,
    private val guestRoot: File,
    private val guestPackage: String,
    private val instanceId: String,
    private val loader: ClassLoader,
    private val guestResources: Resources,
    private val guestApplicationInfo: ApplicationInfo? = null,
) : ContextWrapper(host) {
    var activityRouter: ((android.content.Intent) -> android.content.Intent)? = null
    var videoCameraActive: Boolean = false
    fun forActivity(host: Context) = VirtualContext(host, guestRoot, guestPackage, instanceId, loader, guestResources, guestApplicationInfo)
        .also { it.videoCameraActive = videoCameraActive; it.activityRouter = activityRouter }
    override fun checkSelfPermission(permission: String): Int =
        if (videoCameraActive && permission == android.Manifest.permission.CAMERA)
            android.content.pm.PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (videoCameraActive && permission == android.Manifest.permission.CAMERA &&
            pid == android.os.Process.myPid() && uid == android.os.Process.myUid())
            android.content.pm.PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)
    /** The guest Application once instantiated; it becomes this context's applicationContext. */
    var application: Application? = null
    private fun directory(name: String) = SandboxPaths.resolve(guestRoot, name).apply {
        check(isDirectory || mkdirs()) { "Gagal membuat direktori guest" }
    }
    private fun named(root: File, name: String): File {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..")
        return SandboxPaths.resolve(root, name)
    }

    override fun startActivity(intent: android.content.Intent) = super.startActivity(activityRouter?.invoke(intent) ?: intent)
    override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) = super.startActivity(activityRouter?.invoke(intent) ?: intent, options)
    override fun getPackageCodePath(): String = applicationInfo.sourceDir
    override fun getPackageResourcePath(): String = applicationInfo.publicSourceDir
    override fun createPackageContext(packageName: String, flags: Int): Context =
        if (packageName == guestPackage) this else super.createPackageContext(packageName, flags)
    override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): Context {
        val config = android.content.res.Configuration(resources.configuration).apply { updateFrom(overrideConfiguration) }
        @Suppress("DEPRECATION")
        val updated = Resources(assets, resources.displayMetrics, config)
        return VirtualContext(baseContext.createConfigurationContext(overrideConfiguration), guestRoot, guestPackage,
            instanceId, loader, updated, guestApplicationInfo).also {
            it.application = application; it.activityRouter = activityRouter; it.videoCameraActive = videoCameraActive
        }
    }
    override fun getPackageName() = guestPackage
    override fun getApplicationContext(): Context = application ?: this
    override fun getApplicationInfo(): ApplicationInfo = guestApplicationInfo ?: super.getApplicationInfo()
    override fun getClassLoader() = loader
    override fun getResources() = guestResources
    override fun getAssets(): AssetManager = guestResources.assets
    private val guestTheme by lazy { guestResources.newTheme().apply { applyStyle(guestApplicationInfo?.theme?.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true) } }
    override fun getTheme(): Resources.Theme = guestTheme
    private val autofillServiceName by lazy { baseContext.getSystemServiceName(android.view.autofill.AutofillManager::class.java) }
    override fun getSystemService(name: String): Any? = when (name) {
        // Autofill validates the component with PackageManager. Guest APKs are not installed;
        // exposing the host's autofill identity is also incorrect. Explicitly unsupported.
        autofillServiceName -> null
        LAYOUT_INFLATER_SERVICE -> android.view.LayoutInflater.from(baseContext).cloneInContext(this)
        else -> super.getSystemService(name)
    }
    override fun getFilesDir() = directory("files")
    override fun getCacheDir() = directory("cache")
    override fun getCodeCacheDir() = directory("code_cache")
    override fun getDataDir() = guestRoot
    override fun getNoBackupFilesDir() = directory("no_backup")
    // Android apps commonly derive the storage root by locating /Android/data in this path.
    // Preserve that shape while keeping every byte beneath this instance's own data root.
    private fun externalAppDir(child: String): File = directory("external/Android/data/$guestPackage/$child")
    override fun getExternalFilesDir(type: String?): File = if (type == null) externalAppDir("files")
        else named(externalAppDir("files"), type).apply { mkdirs() }
    override fun getExternalFilesDirs(type: String?) = arrayOf(getExternalFilesDir(type))
    override fun getExternalCacheDir() = externalAppDir("cache")
    override fun getExternalMediaDirs() = arrayOf(directory("external/Android/media/$guestPackage"))
    override fun getExternalCacheDirs() = arrayOf(externalCacheDir)
    override fun getDir(name: String, mode: Int): File {
        require(mode == MODE_PRIVATE)
        return named(directory("dirs"), name).apply { mkdirs() }
    }
    override fun getFileStreamPath(name: String) = named(filesDir, name)
    override fun openFileInput(name: String) = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        require(mode == MODE_PRIVATE || mode == MODE_APPEND)
        return FileOutputStream(getFileStreamPath(name), mode == MODE_APPEND)
    }
    override fun deleteFile(name: String) = getFileStreamPath(name).delete()
    override fun fileList() = filesDir.list().orEmpty()
    override fun getDatabasePath(name: String) = named(directory("databases"), name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
        require(mode and (MODE_PRIVATE or MODE_ENABLE_WRITE_AHEAD_LOGGING or MODE_NO_LOCALIZED_COLLATORS).inv() == 0)
        return SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, databaseFlags(mode))
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase {
        require(mode and (MODE_PRIVATE or MODE_ENABLE_WRITE_AHEAD_LOGGING or MODE_NO_LOCALIZED_COLLATORS).inv() == 0)
        return SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, databaseFlags(mode), errorHandler)
    }
    private fun databaseFlags(mode: Int) = SQLiteDatabase.CREATE_IF_NECESSARY or
        (if (mode and MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0) or
        (if (mode and MODE_NO_LOCALIZED_COLLATORS != 0) SQLiteDatabase.NO_LOCALIZED_COLLATORS else 0)
    override fun databaseList() = directory("databases").list().orEmpty()
    override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(mode == MODE_PRIVATE)
        named(directory("preferences"), name) // validate caller-provided name
        // Public SDK has no arbitrary-directory SharedPreferences constructor. Namespace in host instead.
        return baseContext.getSharedPreferences("guest_${instanceId}_$name", MODE_PRIVATE)
    }
    override fun deleteSharedPreferences(name: String): Boolean {
        named(directory("preferences"), name)
        return baseContext.deleteSharedPreferences("guest_${instanceId}_$name")
    }
}
