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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var deleting by remember { mutableStateOf<SandboxRecord?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importApk) }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::chooseVideo) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val abi = remember { runCatching { NativeDiagnostics.abi() }.getOrDefault("native library unavailable") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inset ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(inset).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("AetherSandbox", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("VirtuBox Core • 0.1.0-alpha.2", color = MaterialTheme.colorScheme.secondary)
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Laboratorium Android", style = MaterialTheme.typography.titleLarge)
                        Text("Impor APK, kelola instance, dan jalankan fixture QA dengan data terpisah.")
                        Text("Android ${Build.VERSION.RELEASE} • $abi • ${state.records.size} instance", style = MaterialTheme.typography.bodySmall)
                        Text("Runtime aplikasi umum dan intersepsi kamera: belum tersedia.", color = MaterialTheme.colorScheme.secondary,
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
                        Text("Preview lokal dengan loop, pause, rotasi, dan cermin.")
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
                        state.video?.let { VideoPreview(it) }
                    }
                }
            }
            item { Text("Instance APK", style = MaterialTheme.typography.titleLarge) }
            if (state.records.isEmpty()) item {
                Text("Belum ada APK. Untuk menguji runtime, impor fixture-debug.apk dari hasil build.", color = MaterialTheme.colorScheme.secondary)
            }
            items(state.records, key = { it.id }) { record ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.label, style = MaterialTheme.typography.titleMedium)
                        Text("${record.packageName} • ${record.versionName}", style = MaterialTheme.typography.bodySmall)
                        Text("${record.bytes / 1024 / 1024} MB • Instance ${record.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                        Text("Test ID: ${record.testAndroidId}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256: ${record.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
                        Text(if (record.trustedFixture) "Fixture kompatibel" else "Metadata tersimpan; runtime belum kompatibel",
                            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = record.trustedFixture && !state.busy, onClick = {
                                context.startActivity(Intent(context, ProxyActivity::class.java).putExtra(ProxyActivity.EXTRA_ID, record.id))
                            }) { Text("Jalankan") }
                            OutlinedButton(enabled = !state.busy, onClick = { model.regenerate(record.id) }) { Text("Profil baru") }
                        }
                        TextButton(enabled = !state.busy, onClick = { deleting = record }) { Text("Hapus instance") }
                    }
                }
            }
        }
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Hapus instance?") },
            text = { Text("APK dan data instance ${record.id.take(8)} akan dihapus. Jika guest masih berjalan, hentikan melalui tombol Hentikan guest terlebih dahulu.") },
            confirmButton = { TextButton(onClick = { model.delete(record.id); deleting = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } })
    }
}
