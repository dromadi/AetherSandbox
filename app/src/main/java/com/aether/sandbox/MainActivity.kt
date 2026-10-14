package com.aether.sandbox

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aether.sandbox.core.NativeDiagnostics
import com.aether.sandbox.core.ProxyActivity
import com.aether.sandbox.data.SandboxRecord
import com.aether.sandbox.media.FloatingAssistantService
import com.aether.sandbox.media.VideoPreview
import com.aether.sandbox.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(ProxyActivity.EXTRA_ID)?.let { id ->
            if (com.aether.sandbox.core.ShortcutGate.accepts(this, intent)) {
                startActivity(Intent(this, com.aether.sandbox.core.RuntimeRouterActivity::class.java)
                    .putExtra(ProxyActivity.EXTRA_ID, id))
            } else android.widget.Toast.makeText(this, "Shortcut tidak valid", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xff78d6c6), secondary = Color(0xffa7c7ec),
                background = Color(0xff0c1422), surface = Color(0xff142136),
            )) { AetherScreen() }
        }
    }
}

@Composable
private fun AetherScreen(model: MainViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var resetting by remember { mutableStateOf<SandboxRecord?>(null) }
    var renaming by remember { mutableStateOf<SandboxRecord?>(null) }
    var newName by remember { mutableStateOf("") }
    var launching by remember { mutableStateOf<SandboxRecord?>(null) }
    var videoCameraEnabled by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SandboxRecord?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importApk) }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::chooseVideo) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(model::exportReport) }
    fun launchGuest(record: SandboxRecord) {
        context.startActivity(Intent(context, com.aether.sandbox.core.RuntimeRouterActivity::class.java)
            .putExtra(ProxyActivity.EXTRA_ID, record.id)
            .putExtra(ProxyActivity.EXTRA_CAMERA_URI, state.video?.toString())
            .putExtra(ProxyActivity.EXTRA_CAMERA2_VIDEO, videoCameraEnabled && state.video != null))
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val abi = remember { runCatching { NativeDiagnostics.abi() }.getOrDefault("native library unavailable") }
    val snackbar = remember { SnackbarHostState() }
    // Guests run in another process: re-read their status whenever the host returns to front.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inset ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(inset).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("AetherSandbox", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("VirtuBox Core • 0.1.0-alpha.7", color = MaterialTheme.colorScheme.secondary)
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Laboratorium Android", style = MaterialTheme.typography.titleLarge)
                        Text("Impor APK, kelola instance, jalankan fixture penuh atau muat aplikasi umum dengan data terpisah.")
                        Text("Android ${Build.VERSION.RELEASE} • $abi • ${state.records.size} instance • ${state.running.size} berjalan",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Empat slot runtime. Activity APK umum memakai proxy eksperimental. Semua guest memakai UID host; jalankan APK yang Anda percayai.", color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !state.busy, onClick = { import.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) {
                            Text("Impor APK")
                        }
                    }
                }
            }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Video uji", style = MaterialTheme.typography.titleLarge)
                        Text("Preview lokal dengan loop, pause, rotasi, dan cermin. Video ini juga menjadi kamera virtual deterministik untuk fixture yang dijalankan.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { video.launch(arrayOf("video/*")) }) { Text("Pilih video") }
                            OutlinedButton(enabled = state.video != null, onClick = {
                                if (!Settings.canDrawOverlays(context)) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                    model.message("Aktifkan izin overlay, lalu tekan Panel lagi")
                                } else {
                                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    try {
                                        context.startForegroundService(Intent(context, FloatingAssistantService::class.java)
                                            .putExtra(FloatingAssistantService.EXTRA_URI, state.video.toString()))
                                    } catch (error: Exception) { model.message("Panel gagal dibuka: ${error.message}") }
                                }
                            }) { Text("Panel") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = videoCameraEnabled, enabled = state.video != null,
                                onCheckedChange = { videoCameraEnabled = it })
                            Text("Gunakan video untuk Camera2 (eksperimental)")
                        }
                        if (videoCameraEnabled) Text("Berlaku saat guest dimulai ulang. Preview dan encoder Surface saja; kompatibilitas aplikasi video call belum terbukti.")
                        state.video?.let { VideoPreview(it) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Instance APK", style = MaterialTheme.typography.titleLarge)
                    if (state.running.isNotEmpty()) {
                        TextButton(enabled = !state.busy, onClick = model::stopAll) { Text("Hentikan semua") }
                    }
                }
            }
            if (state.records.isEmpty()) item {
                Text("Belum ada APK. Untuk menguji runtime, impor fixture-debug.apk dari hasil build.", color = MaterialTheme.colorScheme.secondary)
            }
            items(state.records, key = { it.id }) { record ->
                val running = record.id in state.running
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.instanceName, style = MaterialTheme.typography.titleMedium)
                        Text(if (running) "● Berjalan" else "○ Berhenti", style = MaterialTheme.typography.labelMedium,
                            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        Text("${record.packageName} • ${record.versionName}", style = MaterialTheme.typography.bodySmall)
                        Text("${record.bytes / 1024 / 1024} MB • Instance ${record.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                        Text("Test ID: ${record.testAndroidId}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256: ${record.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
                        Text(if (record.trustedFixture) "Fixture kompatibel: UI penuh + kamera virtual"
                            else "Aplikasi umum: Activity proxy eksperimental",
                            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (running) {
                                Button(enabled = !state.busy, onClick = { launchGuest(record) }) { Text("Buka") }
                                Button(enabled = !state.busy, onClick = { model.stop(record.id) }) { Text("Hentikan") }
                            } else {
                                Button(enabled = !state.busy, onClick = {
                                    if (record.trustedFixture) launchGuest(record) else launching = record
                                }) { Text("Jalankan") }
                            }
                            OutlinedButton(enabled = !running && !state.busy, onClick = { model.regenerate(record.id) }) { Text("Profil baru") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !state.busy, onClick = { model.duplicate(record.id) }) { Text("Duplikasi") }
                            TextButton(enabled = !state.busy, onClick = { renaming = record; newName = record.instanceName }) { Text("Nama") }
                            TextButton(enabled = !state.busy, onClick = { model.inspect(record.id) }) { Text("Laporan") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !state.busy, onClick = { resetting = record }) { Text("Reset data") }
                            TextButton(enabled = !state.busy, onClick = {
                                val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
                                if (!manager.isRequestPinShortcutSupported) model.message("Launcher HP tidak mendukung shortcut")
                                else {
                                    val shortcut = android.content.pm.ShortcutInfo.Builder(context, record.id)
                                        .setShortLabel(record.instanceName.take(30))
                                        .setIcon(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_aether))
                                        .setIntent(com.aether.sandbox.core.ShortcutGate.intent(context, record.id)).build()
                                    if (manager.requestPinShortcut(shortcut, null)) model.message("Permintaan shortcut dikirim ke launcher")
                                    else model.message("Shortcut tidak dapat dibuat")
                                }
                            }) { Text("Shortcut") }
                        }
                        TextButton(enabled = !state.busy, onClick = { deleting = record }) { Text("Hapus instance") }
                    }
                }
            }
        }
    }
    launching?.let { record ->
        AlertDialog(onDismissRequest = { launching = null }, title = { Text("Jalankan kode APK?") },
            text = { Text("${record.instanceName} akan menjalankan Application dengan UID host dan dapat mengakses data host serta instance lain. Activity proxy bersifat eksperimental; layanan dan kompatibilitas aplikasi belum lengkap.") },
            confirmButton = { TextButton(onClick = { launchGuest(record); launching = null }) { Text("Jalankan") } },
            dismissButton = { TextButton(onClick = { launching = null }) { Text("Batal") } })
    }
    renaming?.let { record ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Nama instance") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it.take(80) }, singleLine = true, label = { Text("Nama") }) },
            confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = { model.rename(record.id, newName); renaming = null }) { Text("Simpan") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Batal") } })
    }
    resetting?.let { record ->
        AlertDialog(onDismissRequest = { resetting = null }, title = { Text("Reset data instance?") },
            text = { Text("Data akun, file, database, dan preferences ${record.instanceName} dihapus. APK dan profil uji tetap tersedia. Guest aktif akan dihentikan.") },
            confirmButton = { TextButton(onClick = { model.resetData(record.id); resetting = null }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text("Batal") } })
    }
    state.report?.let { report ->
        AlertDialog(onDismissRequest = model::dismissReport, title = { Text("Laporan runtime") },
            text = { androidx.compose.foundation.layout.Box(Modifier.heightIn(max = 400.dp)) {
                Text(report, modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { export.launch("AetherSandbox-runtime.txt") }) { Text("Ekspor") } },
            dismissButton = { TextButton(onClick = model::dismissReport) { Text("Tutup") } })
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Hapus instance?") },
            text = {
                val note = if (record.id in state.running) " Guest yang sedang berjalan akan dihentikan terlebih dahulu." else ""
                Text("APK dan data instance ${record.id.take(8)} akan dihapus.$note")
            },
            confirmButton = { TextButton(onClick = { model.delete(record.id); deleting = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } })
    }
}
