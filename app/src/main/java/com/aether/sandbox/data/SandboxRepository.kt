package com.aether.sandbox.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import com.aether.sandbox.domain.ArchiveValidator
import com.aether.sandbox.domain.BoundedCopy
import com.aether.sandbox.domain.SandboxPaths
import com.aether.sandbox.domain.RuntimeLease
import com.aether.sandbox.domain.TestProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class SandboxRecord(
    val id: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val sha256: String,
    val bytes: Long,
    val testAndroidId: String,
    val entryClass: String?,
    val trustedFixture: Boolean,
)

/** All mutations belong to the host ViewModel IO mutex. Child process only reads the index. */
class SandboxRepository(private val context: Context) {
    val root = File(context.filesDir, "sandboxes").apply { mkdirs() }
    private val index = AtomicFile(File(context.filesDir, "sandbox-index.json"))

    fun list(): List<SandboxRecord> {
        if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) return emptyList()
        val data = index.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONArray(data)
        return (0 until array.length()).map { i ->
            val json = array.getJSONObject(i)
            SandboxRecord(
                SandboxPaths.requireInstanceId(json.getString("id")), json.getString("package"),
                json.getString("label"), json.getString("version"), json.getString("sha256"),
                json.getLong("bytes"), TestProfile.requireAndroidId(json.getString("testId")),
                json.optString("entry").takeIf { it.isNotEmpty() }, json.optBoolean("trusted"),
            )
        }
    }

    fun find(id: String): SandboxRecord = list().firstOrNull { it.id == id }
        ?: throw IOException("Sandbox tidak ditemukan")

    fun apk(record: SandboxRecord): File = SandboxPaths.resolve(directory(record.id), "guest.apk")
    fun directory(id: String): File = SandboxPaths.instance(root, id)

    fun importApk(uri: Uri): SandboxRecord {
        val records = list()
        val id = UUID.randomUUID().toString()
        val directory = directory(id).apply { check(mkdirs()) { "Gagal membuat penyimpanan sandbox" } }
        try {
            val file = SandboxPaths.resolve(directory, "guest.apk")
            val sha = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    // Android 14+: set read-only BEFORE writing through the already-open descriptor.
                    check(file.setReadOnly()) { "Tidak dapat mengunci APK" }
                    BoundedCopy.copyAndHash(input, output, MAX_APK_BYTES).also { output.fd.sync() }
                }
            } ?: throw IOException("Berkas APK tidak dapat dibuka")
            ArchiveValidator.validate(file)
            val info = archiveInfo(file)
            require(info.splitNames.isNullOrEmpty()) {
                "APK split belum didukung. Gunakan APK universal."
            }
            val app = requireNotNull(info.applicationInfo)
            app.sourceDir = file.path
            app.publicSourceDir = file.path
            val entry = app.metaData?.getString(ENTRY_META)
            val trusted = entry != null && info.packageName == FIXTURE_PACKAGE && signerMatchesHost(info)
            val record = SandboxRecord(id, info.packageName,
                runCatching { app.loadLabel(context.packageManager).toString() }.getOrDefault(info.packageName),
                info.versionName.orEmpty(), sha, file.length(), TestProfile.newAndroidId(), entry, trusted)
            save(records + record)
            return record
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun acquireRuntime(id: String): RuntimeLease =
        RuntimeLease.acquire(File(context.filesDir, "runtime-locks"), id)

    fun regenerateProfile(id: String) = acquireRuntime(id).use {
        find(id)
        save(list().map { if (it.id == id) it.copy(testAndroidId = TestProfile.newAndroidId()) else it })
    }

    fun delete(id: String) = acquireRuntime(id).use {
        find(id)
        // Publish removal first so new launches cannot see a half-deleted instance.
        save(list().filterNot { it.id == id })
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.startsWith("guest_${id}_") && it.name.endsWith(".xml") }
            .forEach { context.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
        File(context.codeCacheDir, id).deleteRecursively()
        if (!directory(id).deleteRecursively()) throw IOException("Data tersisa di penyimpanan; hapus ulang melalui pengaturan aplikasi")
    }

    fun verifyFixture(record: SandboxRecord): PackageInfo {
        val file = apk(record)
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
        val hash = file.inputStream().use { BoundedCopy.copyAndHash(it, sink, MAX_APK_BYTES) }
        check(hash == record.sha256) { "Integritas APK berubah. Impor ulang fixture." }
        val info = archiveInfo(file)
        check(info.packageName == FIXTURE_PACKAGE && signerMatchesHost(info)
            && info.applicationInfo?.metaData?.getString(ENTRY_META) == record.entryClass
            && record.entryClass != null) { "Hanya fixture bertanda tangan sama dengan host yang dapat dijalankan" }
        return info
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo {
        val flags = PackageManager.GET_META_DATA or if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: throw IOException("APK tidak valid atau tidak kompatibel")
    }

    @Suppress("DEPRECATION")
    private fun signerMatchesHost(info: PackageInfo): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val host = context.packageManager.getPackageInfo(context.packageName, flags)
        fun signers(value: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) value.signingInfo?.apkContentsSigners else value.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val guest = signers(info)
        return guest.isNotEmpty() && guest == signers(host)
    }

    private fun save(records: List<SandboxRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().put("id", record.id).put("package", record.packageName)
                .put("label", record.label).put("version", record.versionName).put("sha256", record.sha256)
                .put("bytes", record.bytes).put("testId", record.testAndroidId)
                .put("entry", record.entryClass.orEmpty()).put("trusted", record.trustedFixture))
        }
        val output = index.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(output)
        } catch (error: Throwable) {
            index.failWrite(output)
            throw error
        }
    }

    companion object {
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        const val ENTRY_META = "com.aether.sandbox.GUEST_ENTRY"
        const val FIXTURE_PACKAGE = "com.aether.fixture"
    }
}

