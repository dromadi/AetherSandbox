package com.aether.sandbox.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import com.aether.sandbox.core.RuntimeControl
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
    val instanceName: String = label,
)

/** The guest's declared Android components, as read from its APK manifest. */
data class GuestComponents(
    val activities: List<String>,
    val services: List<String>,
    val providers: List<String>,
    val receivers: List<String>,
    val launcher: String?,
) {
    val total: Int get() = activities.size + services.size + providers.size + receivers.size
}

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
                json.optString("entry").takeIf { it.isNotEmpty() }, json.optBoolean("trusted"), json.optString("name", json.getString("label")),
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

    private val locks get() = File(context.filesDir, "runtime-locks")

    /** Takes the instance lease, retrying while it is busy for up to [waitMillis]. */
    fun acquireRuntime(id: String, waitMillis: Long = 0): RuntimeLease {
        val deadline = SystemClock.uptimeMillis() + waitMillis
        while (true) {
            try {
                return RuntimeLease.acquire(locks, id)
            } catch (busy: RuntimeLease.Busy) {
                if (SystemClock.uptimeMillis() >= deadline) throw busy
                SystemClock.sleep(LEASE_POLL_MILLIS)
            }
        }
    }

    fun isRunning(id: String): Boolean = RuntimeLease.isHeld(locks, id)

    fun runningIds(records: List<SandboxRecord> = list()): Set<String> =
        records.map { it.id }.filterTo(mutableSetOf()) { isRunning(it) }

    /** Asks the runtime of [id] to finish and waits until its lease is released. */
    fun stop(id: String, waitMillis: Long = STOP_TIMEOUT_MILLIS) {
        if (!isRunning(id)) return
        RuntimeControl.requestStop(context, id)
        val deadline = SystemClock.uptimeMillis() + waitMillis
        val forceAt = SystemClock.uptimeMillis() + 1_000
        var forced = false
        while (isRunning(id)) {
            if (!forced && SystemClock.uptimeMillis() >= forceAt) {
                forced = true
                val report = File(directory(id), "runtime-report.json")
                val pid = runCatching { JSONObject(report.inputStream().use { it.readBytesBounded(32_769).also { bytes -> check(bytes.size <= 32_768) }.toString(Charsets.UTF_8) }).getInt("pid") }.getOrNull()
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                val process = manager.runningAppProcesses.orEmpty().firstOrNull {
                    it.pid == pid && it.uid == android.os.Process.myUid() &&
                        it.processName in (0..3).map { slot -> context.packageName + ":virtual_process" + if (slot == 0) "" else slot.toString() }
                }
                // Same-UID kill, only after verifying PID and an owned runtime process name.
                if (process != null && isRunning(id)) android.os.Process.killProcess(process.pid)
            }
            if (SystemClock.uptimeMillis() >= deadline) throw IOException("Guest tidak berhenti dalam ${waitMillis / 1000} detik")
            SystemClock.sleep(LEASE_POLL_MILLIS)
        }
    }

    fun regenerateProfile(id: String) = acquireRuntime(id).use {
        find(id)
        save(list().map { if (it.id == id) it.copy(testAndroidId = TestProfile.newAndroidId()) else it })
    }

    fun delete(id: String) = acquireRuntime(id).use {
        find(id)
        // Publish removal first so new launches cannot see a half-deleted instance.
        save(list().filterNot { it.id == id })
        runCatching { com.aether.sandbox.core.ShortcutGate.revoke(context, id) }
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.startsWith("guest_${id}_") && it.name.endsWith(".xml") }
            .forEach { context.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
        File(context.codeCacheDir, id).deleteRecursively()
        if (!directory(id).deleteRecursively()) throw IOException("Data tersisa di penyimpanan; hapus ulang melalui pengaturan aplikasi")
    }

    /** A fresh instance of the same verified APK; account data is deliberately not copied. */
    fun duplicate(id: String): SandboxRecord {
        val source = find(id)
        verifyIntegrity(source)
        val copy = importApk(Uri.fromFile(apk(source)))
        rename(copy.id, "${source.instanceName.take(70)} • salinan")
        return find(copy.id)
    }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 80) { "Nama harus 1–80 karakter" }
        find(id)
        save(list().map { if (it.id == id) it.copy(instanceName = clean) else it })
    }

    /** Requires a stopped runtime; retains APK, instance UUID, and test profile. */
    fun resetData(id: String) = acquireRuntime(id).use {
        find(id)
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.startsWith("guest_${id}_") && it.name.endsWith(".xml") }
            .forEach { check(context.deleteSharedPreferences(it.name.removeSuffix(".xml"))) }
        for (path in listOf(File(directory(id), "data"), File(directory(id), "native"), File(context.codeCacheDir, id))) {
            check(!path.exists() || path.deleteRecursively()) { "Gagal membersihkan data instance" }
        }
    }

    fun runtimeLocation(id: String): Pair<Int, Int> {
        find(id)
        val report = File(directory(id), "runtime-report.json")
        val json = JSONObject(report.inputStream().use { input ->
            input.readBytesBounded(32_769).also { check(it.size <= 32_768) }.toString(Charsets.UTF_8)
        })
        val slot = json.getInt("slot")
        require(slot in 0..3)
        return json.getInt("taskId") to slot
    }

    fun runtimeReport(id: String): String {
        val record = find(id)
        val components = guestComponents(record)
        val report = File(directory(id), "runtime-report.json")
        return buildString {
            appendLine("AetherSandbox ${com.aether.sandbox.BuildConfig.VERSION_NAME}")
            appendLine("Instance: ${record.instanceName} (${record.id})")
            appendLine("Paket: ${record.packageName} ${record.versionName}")
            appendLine("SHA-256: ${record.sha256}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Berjalan: ${isRunning(id)}")
            appendLine("Launcher: ${components.launcher ?: "tidak tersedia"}")
            appendLine("Activity=${components.activities.size}; Service=${components.services.size}; Provider=${components.providers.size}; Receiver=${components.receivers.size}")
            appendLine("Runtime umum: Activity proxy eksperimental; Service/Provider belum dieksekusi.")
            appendLine("Guest memakai UID host; bukan isolasi keamanan.")
            if (report.exists() && report.length() <= 32_768) appendLine(report.readText())
        }
    }

    fun verifyFixture(record: SandboxRecord): PackageInfo {
        val info = verifyIntegrity(record)
        check(info.packageName == FIXTURE_PACKAGE && signerMatchesHost(info)
            && info.applicationInfo?.metaData?.getString(ENTRY_META) == record.entryClass
            && record.entryClass != null) { "Hanya fixture bertanda tangan sama dengan host yang dapat dijalankan" }
        return info
    }

    /**
     * Re-hashes the stored APK and returns its metadata. Confirms the file has not changed since
     * import; it does NOT establish trust. A general guest runs with the host UID, so its code can
     * read every instance's data and the host's own data. This is not a security boundary.
     */
    fun verifyIntegrity(record: SandboxRecord): PackageInfo {
        val file = apk(record)
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
        val hash = file.inputStream().use { BoundedCopy.copyAndHash(it, sink, MAX_APK_BYTES) }
        check(hash == record.sha256) { "Integritas APK berubah. Impor ulang APK." }
        val info = archiveInfo(file, PackageManager.GET_ACTIVITIES)
        info.applicationInfo?.apply { sourceDir = file.path; publicSourceDir = file.path }
        return info
    }

    fun activityRegistry(record: SandboxRecord): Map<String, android.content.pm.ActivityInfo> {
        val info = archiveInfo(apk(record), PackageManager.GET_ACTIVITIES)
        return info.activities.orEmpty().associateBy { it.name }
    }

    /**
     * Collects the guest's declared components from the stored APK. This is the registry the
     * runtime will need before it can start the guest's Activities/Services; alpha.5 only reports
     * it. Launcher resolution reads MAIN/LAUNCHER filters from the binary APK manifest.
     */
    fun guestComponents(record: SandboxRecord): GuestComponents {
        val info = archiveInfo(apk(record),
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
                or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS)
        fun names(items: Array<out android.content.pm.ComponentInfo>?) = items.orEmpty().map { it.name }
        val activities = names(info.activities)
        return GuestComponents(activities, names(info.services), names(info.providers),
            names(info.receivers), com.aether.sandbox.core.ManifestLauncher.resolve(context, requireNotNull(info.applicationInfo).apply {
                sourceDir = apk(record).path; publicSourceDir = sourceDir
            }))
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File, extra: Int = 0): PackageInfo {
        val flags = PackageManager.GET_META_DATA or extra or if (Build.VERSION.SDK_INT >= 28)
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
                .put("label", record.label).put("name", record.instanceName).put("version", record.versionName).put("sha256", record.sha256)
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
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val LAUNCH_LEASE_WAIT_MILLIS = 1_000L
        private const val LEASE_POLL_MILLIS = 50L
    }
}


private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
