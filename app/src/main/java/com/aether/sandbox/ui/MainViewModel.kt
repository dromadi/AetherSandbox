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
    val busy: Boolean = false,
    val message: String? = null,
    val video: Uri? = null,
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
                    val records = withContext(Dispatchers.IO) { block(); repository.list() }
                    mutableState.update { it.copy(records = records, busy = false, message = success) }
                } catch (error: Exception) {
                    mutableState.update { it.copy(busy = false, message = error.message ?: "Operasi gagal") }
                }
            }
        }
    }

    fun refresh() = operation { }
    fun importApk(uri: Uri) = operation("APK berhasil diimpor") { repository.importApk(uri) }
    fun delete(id: String) = operation("Instance dihapus") { repository.delete(id) }
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
