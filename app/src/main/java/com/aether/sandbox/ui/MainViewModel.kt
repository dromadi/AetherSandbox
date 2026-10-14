package com.aether.sandbox.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aether.sandbox.data.SandboxRecord
import com.aether.sandbox.data.SandboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class UiState(
    val records: List<SandboxRecord> = emptyList(),
    val running: Set<String> = emptySet(),
    val busy: Boolean = false,
    val message: String? = null,
    val video: Uri? = null,
    val report: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SandboxRepository(application)
    private val mutex = Mutex()
    private val preferences = application.getSharedPreferences("host-media", 0)
    private val mutableState = MutableStateFlow(UiState(video = preferences.getString("video", null)?.let(Uri::parse)))
    val state = mutableState.asStateFlow()

    init { refresh() }

    private fun operation(success: String? = null, block: () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                mutableState.update { it.copy(busy = true, message = null) }
                try {
                    val (records, running) = withContext(Dispatchers.IO) {
                        block()
                        repository.list().let { it to repository.runningIds(it) }
                    }
                    mutableState.update { it.copy(records = records, running = running, busy = false, message = success) }
                } catch (error: Exception) {
                    val running = withContext(Dispatchers.IO) { runCatching { repository.runningIds() }.getOrNull() }
                    mutableState.update {
                        it.copy(running = running ?: it.running, busy = false, message = error.message ?: "Operasi gagal")
                    }
                }
            }
        }
    }

    fun refresh() = operation { }
    fun importApk(uri: Uri) = operation("APK berhasil diimpor") { repository.importApk(uri) }
    fun stop(id: String) = operation("Guest dihentikan") { repository.stop(id) }
    fun stopAll() = operation("Semua guest dihentikan") { repository.runningIds().forEach(repository::stop) }
    /** Stops a running guest first so deletion does not require a trip to the runtime screen. */
    fun delete(id: String) = operation("Instance dihapus") { repository.stop(id); repository.delete(id) }
    fun duplicate(id: String) = operation("Instance baru dibuat; data akun tidak disalin") { repository.duplicate(id) }
    fun rename(id: String, name: String) = operation("Nama instance diperbarui") { repository.rename(id, name) }
    fun resetData(id: String) = operation("Data instance direset; APK dan profil dipertahankan") {
        repository.stop(id); repository.resetData(id)
    }
    fun inspect(id: String) = operation {
        val report = repository.runtimeReport(id)
        mutableState.update { it.copy(report = report) }
    }
    fun dismissReport() { mutableState.update { it.copy(report = null) } }
    fun exportReport(uri: Uri) {
        val report = state.value.report ?: return
        operation("Laporan disimpan") {
            val resolver = getApplication<Application>().contentResolver
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(report.toByteArray()) }
        }
    }
    fun regenerate(id: String) = operation("Profil uji diperbarui") { repository.regenerateProfile(id) }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }
    fun chooseVideo(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val previous = state.value.video
            preferences.edit().putString("video", uri.toString()).apply()
            mutableState.update { it.copy(video = uri) }
            if (previous != null && previous != uri) runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) { message("Akses video gagal: ${error.message}") }
    }
}
