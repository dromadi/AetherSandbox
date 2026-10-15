package com.aether.sandbox.vm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class VmLabState(val running: Boolean = false, val report: String? = null, val error: String? = null)
class VmLabModel : ViewModel() {
    private val mutable = MutableStateFlow(VmLabState())
    val state = mutable.asStateFlow()
    fun probe(context: android.content.Context) {
        if (mutable.value.running) return
        val app = context.applicationContext
        mutable.value = VmLabState(running = true)
        viewModelScope.launch {
            try { mutable.value = VmLabState(report = VmProbeClient.run(app).toString(2)) }
            catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) mutable.value = VmLabState(error = "Probe melewati batas 15 detik")
                else throw cancelled
            } catch (error: Exception) { mutable.value = VmLabState(error = error.message ?: "Probe gagal") }
        }
    }
}

class VmLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Lab() } }
    }
    @Composable private fun Lab(model: VmLabModel = viewModel()) {
        val state by model.state.collectAsStateWithLifecycle()
        var exportMessage by remember { mutableStateOf<String?>(null) }
        var pendingExport by remember { mutableStateOf<String?>(null) }
        val export = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val content = pendingExport
            pendingExport = null
            if (uri != null && content != null) model.viewModelScope.launch {
                exportMessage = try {
                    withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) } }
                    "Laporan disimpan"
                } catch (error: Exception) { "Gagal menyimpan: ${error.message}" }
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Aether VM • Laboratorium", style = MaterialTheme.typography.headlineSmall)
                Text("Tahap G0: pemeriksaan perangkat. Mesin QEMU dan Android guest belum disertakan dalam APK ini.")
                Text("Pemeriksaan menjalankan kode native pendek dalam proses terpisah, membaca akses KVM dan fitur AVF, RAM serta ruang penyimpanan. Hasilnya tidak membuktikan VM Android bisa berjalan.")
                Button(enabled = !state.running, onClick = { exportMessage = null; model.probe(applicationContext) }) { Text(if (state.running) "Memeriksa…" else "Periksa perangkat") }
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.report?.let { raw ->
                    val report = remember(raw) { JSONObject(raw) }
                    Text(if (report.optInt("jitReturn", -1) == 42) "Uji kode native RW→RX berhasil" else "Uji kode native gagal/ditolak")
                    Text("KVM API: ${report.optInt("kvmApi", -1)} • errno akses: ${report.optInt("kvmOpenErrno")}")
                    Text("Membaca versi KVM bukan bukti izin membuat VM. AVF tersedia juga belum berarti aplikasi mendapat akses.")
                    Text("RAM: ${report.optLong("totalMemoryBytes") / 1024 / 1024} MiB • Ruang kosong: ${report.optLong("freeStorageBytes") / 1024 / 1024} MiB")
                    OutlinedButton(onClick = { pendingExport = raw; export.launch("Aether-VM-device-report.json") }) { Text("Ekspor laporan") }
                    Text(raw, style = MaterialTheme.typography.bodySmall)
                }
                exportMessage?.let { Text(it) }
                TextButton(onClick = { finish() }) { Text("Kembali") }
            }
        }
    }
}
