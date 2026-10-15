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
    fun start() {
        if (mutable.value.running || mutable.value.busy || job?.isCompleted == false) return
        mutable.value = AndroidVmState(running = true, status = "Memulai Android 9 guest…")
        job = viewModelScope.launch(Dispatchers.IO) {
            val socket = File(app.cacheDir, "vm-display.sock").also { it.delete() }
            try {
                require(PackagedVmEngine.available(app)) { "Engine tidak tersedia untuk ABI perangkat" }
                val child = ProcessBuilder(PackagedVmEngine.androidArguments(app, socket)).redirectErrorStream(true).start()
                process = child
                val log = File(PackagedVmEngine.directory(app), "console.log")
                val drain = launch {
                    child.inputStream.bufferedReader().use { reader ->
                        log.bufferedWriter().use { writer ->
                            var written = 0
                            while (true) { val line = reader.readLine() ?: break; if (written < 4 * 1024 * 1024) { writer.appendLine(line); writer.flush(); written += line.length + 1 } }
                        }
                    }
                }
                try {
                    withTimeout(30_000) { while (!socket.exists() && child.isAlive) delay(100) }
                    check(child.isAlive) { "Engine berhenti (${child.exitValue()}): ${log.takeIf { it.exists() }?.readText()?.takeLast(2000)}" }
                    val rfb = LocalRfb(socket); display = rfb
                    rfb.frames { frame ->
                        mutable.value = AndroidVmState(running = true, status = "Display guest tersambung • Android 9 • sesi sementara", frame = frame)
                    }
                } finally { display?.close(); display = null; if (child.isAlive) child.destroyForcibly(); drain.cancel() }
            } catch (e: CancellationException) { if (e is TimeoutCancellationException) mutable.value = AndroidVmState(status = "Display VM timeout") else throw e }
            catch (e: Exception) { mutable.value = AndroidVmState(status = "VM berhenti: ${e.message}") }
            finally { process?.let { if (it.isAlive) it.destroyForcibly() }; process = null; socket.delete() }
        }
    }
    fun stop() {
        display?.close(); process?.destroyForcibly(); job?.cancel()
        mutable.value = AndroidVmState(status = "VM dihentikan. Data sesi sementara tidak disimpan.")
    }
    internal suspend fun requestBootEvidence(nonce: String) = withContext(Dispatchers.IO) {
        require(nonce.matches(Regex("[a-zA-Z0-9]+")))
        // The serial command runs INSIDE the guest, never in a host shell. Format and token are
        // split so terminal echo cannot be mistaken for a successful getprop response.
        runCatching { process?.outputStream?.let { stream ->
            stream.write(("printf 'AETHER_GUEST_BOOT_%s=' '$nonce'; getprop sys.boot_completed; getprop ro.build.version.release; pidof system_server; pidof surfaceflinger\n").toByteArray())
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
            var text by remember { mutableStateOf("") }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android guest • Eksperimen", style = MaterialTheme.typography.titleLarge)
                    Text("Android-x86 9.0-r2, x86_64 melalui TCG. Data sementara; jaringan, audio dan kamera belum diaktifkan. Sesi berhenti saat layar ini ditinggalkan.", style = MaterialTheme.typography.bodySmall)
                    Text(state.status)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !state.busy && !state.running, onClick = { picker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) }) { Text("Impor ISO") }
                        Button(enabled = !state.busy && !state.running && PackagedVmEngine.available(this@AndroidVmActivity), onClick = model::start) { Text("Boot Android") }
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
    override fun onStop() { if (!isChangingConfigurations) vm?.stop(); super.onStop() }
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
