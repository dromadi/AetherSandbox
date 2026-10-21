package com.aether.sandbox.vm

import android.app.Application
import android.graphics.*
import android.os.Bundle
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

data class AndroidVmState(val running: Boolean = false, val busy: Boolean = false,
    val status: String = "Berhenti", val frame: Bitmap? = null)
class AndroidVmModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(AndroidVmState())
    val state = mutable.asStateFlow()
    @Volatile private var process: Process? = null
    @Volatile private var display: LocalRfb? = null
    private var job: Job? = null
    private val inputs = Channel<Pair<LocalRfb, (LocalRfb) -> Unit>>(64)
    init { viewModelScope.launch(Dispatchers.IO) { for ((target, command) in inputs) runCatching { command(target) } } }
    private val app get() = getApplication<Application>()
    fun import(uri: android.net.Uri) {
        if (mutable.value.running || mutable.value.busy || job?.isCompleted == false) return
        mutable.value = AndroidVmState(busy = true, status = "Menyalin dan memverifikasi ISO…")
        viewModelScope.launch {
            try { PackagedVmEngine.importIso(app, uri); mutable.value = AndroidVmState(status = "ISO Android 9 siap. Data sesi bersifat sementara.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = AndroidVmState(status = "Impor gagal: ${e.message}") }
        }
    }
    fun linux() {
        if (mutable.value.running || mutable.value.busy) return
        mutable.value = AndroidVmState(busy = true, status = "Boot Linux ARM64 melalui engine…")
        viewModelScope.launch {
            try { PackagedVmEngine.linuxBoot(app); mutable.value = AndroidVmState(status = "Linux ARM64: PID 1 berjalan dan shutdown berhasil di proses Android ini.") }
            catch (e: CancellationException) { if (e is TimeoutCancellationException) mutable.value = AndroidVmState(status = "Boot Linux timeout") else throw e }
            catch (e: Exception) { mutable.value = AndroidVmState(status = "Boot Linux gagal: ${e.message}") }
        }
    }
    fun start(coreGuest: Boolean = true) {
        if (mutable.value.running || mutable.value.busy || job?.isCompleted == false) return
        mutable.value = AndroidVmState(running = true, status = "Memulai Android 9 guest…")
        job = viewModelScope.launch(Dispatchers.IO) {
            val socket = File(app.cacheDir, "vm-display.sock").also { it.delete() }
            try {
                require(PackagedVmEngine.available(app)) { "Engine tidak tersedia untuk ABI perangkat" }
                val child = ProcessBuilder(PackagedVmEngine.androidArguments(app, socket, coreGuest)).redirectErrorStream(true).start()
                process = child
                val log = File(PackagedVmEngine.directory(app), "console.log")
                val consoleFailure = AtomicReference<IOException?>(null)
                val drain = launch {
                    // Read bounded chunks: a guest can emit an arbitrarily long line.
                    // Closing the process during stop interrupts this blocking read on Android.
                    try {
                        child.inputStream.use { input ->
                            log.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var written = 0
                                while (isActive) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    val keep = minOf(count, 4 * 1024 * 1024 - written)
                                    if (keep > 0) {
                                        output.write(buffer, 0, keep); output.flush(); written += keep
                                    }
                                }
                            }
                        }
                    } catch (e: IOException) {
                        // A child launch must not escape into the ViewModel's exception handler.
                        // Unexpected console loss terminates this session and is reported below.
                        if (isActive) { consoleFailure.set(e); child.destroyForcibly() }
                    }
                }
                try {
                    withTimeout(30_000) { while (!socket.exists() && child.isAlive) delay(100) }
                    check(child.isAlive) { "Engine berhenti (${child.exitValue()}): ${log.takeIf { it.exists() }?.readText()?.takeLast(2000)}" }
                    val rfb = LocalRfb(socket); display = rfb
                    rfb.frames { frame ->
                        mutable.value = AndroidVmState(running = true, status = "Display guest tersambung • Android 9 • sesi sementara", frame = frame)
                    }
                } finally {
                    drain.cancel()
                    display?.close(); display = null
                    if (child.isAlive) child.destroyForcibly()
                }
                consoleFailure.get()?.let { throw IOException("Pembacaan konsol VM gagal", it) }
                if (currentCoroutineContext().isActive) {
                    mutable.value = AndroidVmState(status = "Sesi VM berakhir.")
                }
            } catch (e: CancellationException) { if (e is TimeoutCancellationException) mutable.value = AndroidVmState(status = "Display VM timeout") else throw e }
            catch (e: Exception) { mutable.value = AndroidVmState(status = "VM berhenti: ${e.message}") }
            finally { process?.let { if (it.isAlive) it.destroyForcibly() }; process = null; socket.delete() }
        }
    }
    fun stop() {
        job?.cancel(); display?.close(); process?.destroyForcibly()
        mutable.value = AndroidVmState(status = "VM dihentikan. Data sesi sementara tidak disimpan.")
    }
    internal suspend fun awaitStopped() { job?.join() }
    internal suspend fun requestBootEvidence(nonce: String) = withContext(Dispatchers.IO) {
        require(nonce.matches(Regex("[a-zA-Z0-9]+")))
        // The serial command runs INSIDE the guest, never in a host shell. Format and token are
        // split so terminal echo cannot be mistaken for a successful getprop response.
        runCatching { process?.outputStream?.let { stream ->
            stream.write(("printf 'AETHER_GUEST_BOOT_%s=' '$nonce'; getprop sys.boot_completed; " +
                "getprop ro.build.version.release; pidof system_server; pidof surfaceflinger; " +
                "printf 'AETHER_GUEST_PROFILE_%s=' '$nonce'; getprop pm.dexopt.first-boot; " +
                "printf 'AETHER_GUEST_FLAVOR_%s=' '$nonce'; getprop sys.aether.profile\n").toByteArray())
            stream.flush()
        } }
    }
    internal suspend fun requestDiagnostics(nonce: String) = withContext(Dispatchers.IO) {
        require(nonce.matches(Regex("[a-zA-Z0-9]+")))
        // Read-only commands inside the guest.
        runCatching { process?.outputStream?.let { stream ->
            stream.write(("printf '\\nAETHER_DIAGNOSTICS_%s_BEGIN\\n' '$nonce'; " +
                "logcat -d -b crash -t 100; " +
                "for f in /data/anr/*; do [ ! -f \"\$f\" ] || tail -n 100 \"\$f\"; done; " +
                "getprop; ps -A; cat /proc/meminfo; df /data; " +
                "cat /proc/sys/kernel/random/entropy_avail; cat /sys/class/misc/hw_random/rng_current; " +
                "logcat -d -b all -t 160; " +
                "printf '\\nAETHER_DIAGNOSTICS_%s_END\\n' '$nonce'\n").toByteArray())
            stream.flush()
        } }
    }
    internal suspend fun requestSettingsEvidence(nonce: String, launch: Boolean) = withContext(Dispatchers.IO) {
        require(nonce.matches(Regex("[a-zA-Z0-9]+")))
        runCatching { process?.outputStream?.let { stream ->
            val start = if (launch) "am start -a android.settings.SETTINGS >/dev/null; " else ""
            stream.write((start + "printf 'AETHER_GUEST_SETTINGS_%s=' '$nonce'; dumpsys activity activities | grep mResumedActivity\n").toByteArray())
            stream.flush()
        } }
    }
    private fun input(command: (LocalRfb) -> Unit) { display?.let { inputs.trySend(it to command) } }
    fun pointer(x: Int, y: Int, down: Boolean) = input { it.pointer(x, y, down) }
    fun key(code: Int) = input { it.key(code, true); it.key(code, false) }
    fun text(value: String) = input { target -> value.codePoints().forEach { c ->
        val key = if (c < 128) c else c or 0x01000000; target.key(key, true); target.key(key, false)
    } }
    override fun onCleared() { display?.close(); process?.destroyForcibly(); super.onCleared() }
}

class AndroidVmActivity : ComponentActivity() {
    private var vm: AndroidVmModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            val model: AndroidVmModel = viewModel(); vm = model
            val state by model.state.collectAsStateWithLifecycle()
            DisposableEffect(state.running) {
                if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            var text by remember { mutableStateOf("") }
            var coreGuest by rememberSaveable { mutableStateOf(true) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android guest • Eksperimen", style = MaterialTheme.typography.titleLarge)
                    Text("Android-x86 9.0-r2 • RAM guest 2 GB. Data sementara; jaringan, audio dan kamera belum diaktifkan. Sesi berhenti saat layar ini ditinggalkan.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = coreGuest, enabled = !state.running && !state.busy, onCheckedChange = { coreGuest = it })
                        Text(if (coreGuest) "Android inti • tanpa Play Store dan akun Google" else "Image lengkap • eksperimental")
                    }
                    Text("Profil inti mengurangi beban boot pertama. Pilih image lengkap untuk mempertahankan seluruh paket bawaan ISO.", style = MaterialTheme.typography.bodySmall)
                    Text(state.status)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !state.busy && !state.running, onClick = { picker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }) { Text("Impor ISO") }
                        Button(enabled = !state.busy && !state.running && PackagedVmEngine.available(this@AndroidVmActivity), onClick = { model.start(coreGuest) }) { Text("Boot Android") }
                    }
                    if (state.running) Button(onClick = model::stop) { Text("Hentikan VM") }
                    if (!state.running) OutlinedButton(enabled = !state.busy && PackagedVmEngine.available(this@AndroidVmActivity), onClick = model::linux) { Text("Boot Linux uji") }
                    state.frame?.let { bitmap ->
                        AndroidView(factory = { GuestFrameView(it, model::pointer) }, update = { it.frame = bitmap; it.invalidate() }, modifier = Modifier.fillMaxWidth().height(360.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { model.key(0xff1b) }) { Text("Back") }
                            OutlinedButton(onClick = { model.key(0xff0d) }) { Text("Enter") }
                            OutlinedButton(onClick = { model.key(0xff08) }) { Text("⌫") }
                        }
                        OutlinedTextField(value = text, onValueChange = { text = it.take(512) }, label = { Text("Kirim teks ke guest") })
                        TextButton(onClick = { model.text(text); text = "" }) { Text("Kirim") }
                    }
                    Text("ISO yang diterima: android-x86_64-9.0-r2.iso dari android-x86.org, diverifikasi dengan checksum rilis. Image tidak disertakan dalam APK.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
    }
    override fun onStop() { if (!isChangingConfigurations && vm?.state?.value?.running == true) vm?.stop(); super.onStop() }
}

private class GuestFrameView(context: android.content.Context, val send: (Int, Int, Boolean) -> Unit) : View(context) {
    var frame: Bitmap? = null
    private val target = RectF()
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        frame?.let { bitmap ->
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            target.set((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2)
            canvas.drawBitmap(bitmap, null, target, null)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = frame ?: return false
        if (target.width() <= 0 || target.height() <= 0) return false
        val x = ((event.x-target.left) * bitmap.width/target.width()).toInt().coerceIn(0, bitmap.width-1)
        val y = ((event.y-target.top) * bitmap.height/target.height()).toInt().coerceIn(0, bitmap.height-1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { parent.requestDisallowInterceptTouchEvent(true); send(x,y,true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { send(x,y,false); parent.requestDisallowInterceptTouchEvent(false); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
