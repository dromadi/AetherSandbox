package com.aether.sandbox.core

import android.content.BroadcastReceiver
import androidx.core.net.toUri
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.aether.sandbox.api.GuestLifecycle
import com.aether.sandbox.data.SandboxRepository
import com.aether.sandbox.domain.RuntimeLease
import com.aether.sandbox.media.VideoCameraSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class ProxyActivity : ComponentActivity() {
    open val slot = 0
    @Volatile private var slotLease: RuntimeLease? = null
    private var journal: RuntimeJournal? = null
    private val terminating = java.util.concurrent.atomic.AtomicBoolean(false)

    private var guest: GuestLifecycle? = null
    private var autoLaunchWhenResumed = false
    private var activityRuntime: GuestActivityRuntime? = null
    private var frameworkBridge: GuestFrameworkBridge? = null
    private var providers: List<android.content.ContentProvider> = emptyList()
    private var generalApp: android.app.Application? = null
    @Volatile private var lease: RuntimeLease? = null
    private var loadingJob: Job? = null
    private var stopReceiver: BroadcastReceiver? = null
    private var cameraBridge: com.aether.sandbox.media.Camera2VideoBridge? = null
    private var camera: VideoCameraSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { finishAndRemoveTask() }
        val loading = TextView(this).apply { text = "Memuat guest…"; gravity = Gravity.CENTER }
        setContentView(loading)
        val repository = SandboxRepository(this)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            loading.text = "Guest gagal dimuat: instance tidak ditentukan"
            return
        }
        stopReceiver = RuntimeControl.register(this, id) { finishAndRemoveTask() }
        loadingJob = lifecycleScope.launch {
            try {
                val record = withContext(Dispatchers.IO) {
                    // The host's status probe can hold the free lease for an instant: retry briefly.
                    slotLease = RuntimeSlots.acquire(this@ProxyActivity, slot)
                    lease = repository.acquireRuntime(id, SandboxRepository.LAUNCH_LEASE_WAIT_MILLIS)
                    repository.find(id)
                }
                journal = RuntimeJournal(repository.directory(record.id), taskId, slot).also { it.record("STARTING") }
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    journal?.record("CRASHED", "${error.javaClass.name}: ${error.message}")
                    // Some OS crash handlers wait for a dialog and never return (notably API 26).
                    // Arm termination before delegating so a dead guest cannot retain instance/slot leases.
                    Thread({ android.os.SystemClock.sleep(250); Process.killProcess(Process.myPid()) }, "aether-crash-stop").start()
                    previous?.uncaughtException(thread, error)
                    Process.killProcess(Process.myPid())
                }
                if (record.trustedFixture) loadFixture(repository, record)
                else loadGeneralApp(repository, record)
                journal?.record("RUNNING")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("AetherRuntime", "Guest startup failed", error)
                journal?.record("FAILED", "${error.javaClass.name}: ${error.message}")
                closeGuest()
                loading.text = "Guest gagal dimuat: ${error.message}"
                setContentView(loading)
            }
        }
    }

    private suspend fun loadFixture(repository: SandboxRepository, record: com.aether.sandbox.data.SandboxRecord) {
        val loaded = withContext(Dispatchers.IO) { VirtualClassLoader.load(this@ProxyActivity, record, repository) }
        guest = GuestLifecycle(loaded.entry)
        intent.getStringExtra(EXTRA_CAMERA_URI)?.let { source ->
            camera = VideoCameraSource(applicationContext, source.toUri()).also(loaded.entry::onVirtualCamera)
        }
        val layout = runtimeFrame("Aether QA runtime • fixture") {
            addView(loaded.entry.createView(loaded.context, mapOf(
                "androidId" to record.testAndroidId, "instanceId" to record.id,
            )), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(layout)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) guest?.start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) guest?.resume()
    }

    private suspend fun loadGeneralApp(repository: SandboxRepository, record: com.aether.sandbox.data.SandboxRecord) {
        val prepared = withContext(Dispatchers.IO) { GeneralGuestLoader.prepare(this@ProxyActivity, record, repository) }
        val activities = withContext(Dispatchers.IO) { repository.activityRegistry(record) }
        val bridge = GuestActivityRuntime.install(this, record, prepared, activities)
        activityRuntime = bridge
        if (intent.getBooleanExtra(EXTRA_CAMERA2_VIDEO, false)) {
            val video = requireNotNull(intent.getStringExtra(EXTRA_CAMERA_URI)) { "Pilih video untuk Camera2" }
            cameraBridge = com.aether.sandbox.media.Camera2VideoBridge.install(applicationContext, video.toUri())
            prepared.context.videoCameraActive = true
            journal?.record("CAMERA2_VIDEO_READY", "Synthetic Surface camera; physical camera is not opened")
        }
        val loaded = GeneralGuestLoader.start(prepared)
        bridge.attachApplication(loaded.application)
        generalApp = loaded.application
        providers = loaded.providers
        frameworkBridge = loaded.framework
        val c = loaded.components
        val report = buildString {
            append("Kode dan resource guest dimuat di direktori per instance (UID host sama).\n\n")
            append("Paket: ${record.packageName}\n")
            append("Application: ${loaded.application.javaClass.name}\n")
            append("Classloader: ${loaded.context.classLoader.javaClass.simpleName}\n")
            append("Data: ${loaded.context.filesDir.parent}\n\n")
            append("Komponen terdaftar (${c.total}):\n")
            append("• Activity: ${c.activities.size}   • Service: ${c.services.size}\n")
            append("• Provider: ${c.providers.size}   • Receiver: ${c.receivers.size}\n")
            append("Launcher: ${c.launcher ?: "tidak ada"}\n\n")
            append("Activity guest memakai proxy Android (eksperimental). Provider lokal, identitas paket, dan navigasi guest tersedia. Service/receiver, notifikasi, split APK dan task flag lanjutan belum lengkap.")
        }
        setContentView(runtimeFrame("Aether runtime • aplikasi umum") {
            addView(Button(this@ProxyActivity).apply {
                text = "Buka layar aplikasi"
                isEnabled = prepared.components.launcher != null
                setOnClickListener { runCatching { bridge.launch() }.onFailure {
                    android.widget.Toast.makeText(this@ProxyActivity, it.message, android.widget.Toast.LENGTH_LONG).show()
                } }
            })
            addView(TextView(this@ProxyActivity).apply { text = report }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        autoLaunchWhenResumed = true
        launchWhenResumed()
    }

    private fun launchWhenResumed() {
        if (autoLaunchWhenResumed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            autoLaunchWhenResumed = false
            activityRuntime?.launch()
        }
    }

    private inline fun runtimeFrame(header: String, body: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 72, 24, 24)
            addView(TextView(this@ProxyActivity).apply {
                text = "$header • PID ${Process.myPid()} • UID ${Process.myUid()}\nProses terpisah, UID host sama"
            })
            addView(Button(this@ProxyActivity).apply {
                text = "Hentikan guest"
                setOnClickListener { finishAndRemoveTask() }
            })
            body()
        }

    private fun dispatch(block: (GuestLifecycle) -> Unit) {
        val current = guest ?: return
        try {
            block(current)
        } catch (error: Exception) {
            closeGuest()
            setContentView(TextView(this).apply {
                text = "Lifecycle fixture gagal: ${error.message}. Kembali untuk menghentikan runtime."
                gravity = Gravity.CENTER
            })
        }
    }

    override fun onStart() { super.onStart(); dispatch { it.start() } }
    override fun onResume() { super.onResume(); dispatch { it.resume() }; window.decorView.post { launchWhenResumed() } }
    override fun onPause() { dispatch { it.pause() }; super.onPause() }
    override fun onStop() { dispatch { it.stop() }; super.onStop() }

    private fun closeGuest() {
        val current = guest
        guest = null
        runCatching { current?.close() }.onFailure { Log.e("AetherRuntime", "Guest cleanup failed", it) }
        providers.asReversed().forEach { runCatching { it.shutdown() }.onFailure { Log.e("AetherRuntime", "Provider cleanup failed", it) } }
        providers = emptyList()
        val app = generalApp
        generalApp = null
        runCatching { app?.onTerminate() }.onFailure { Log.e("AetherRuntime", "Guest terminate failed", it) }
        // After the guest's own onDestroy, so a guest that stops its camera there wins the race.
        frameworkBridge?.close()
        frameworkBridge = null
        activityRuntime?.close()
        activityRuntime = null
        cameraBridge?.close()
        cameraBridge = null
        camera?.close()
        camera = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // A rapid host launch may race slot selection. Never replace the existing guest.
        if (intent.getStringExtra(EXTRA_ID) != this.intent.getStringExtra(EXTRA_ID)) {
            android.widget.Toast.makeText(this, "Slot sedang digunakan. Coba jalankan kembali dari host.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        val ownsSlot = slotLease != null
        if (ownsSlot && terminating.compareAndSet(false, true)) {
            // Keep both OS leases until death, so 'stopped' implies every guest thread is gone.
            // Start BEFORE callbacks: a hung guest cleanup must not prevent process termination.
            Thread({
                android.os.SystemClock.sleep(250)
                journal?.record("STOPPED")
                Process.killProcess(Process.myPid())
            }, "aether-runtime-stop").start()
        }
        stopReceiver?.let { runCatching { unregisterReceiver(it) } }
        stopReceiver = null
        loadingJob?.let { job ->
            job.cancel()
            job.invokeOnCompletion {
                if (slotLease != null && terminating.compareAndSet(false, true)) {
                    journal?.record("STOPPED")
                    Process.killProcess(Process.myPid())
                }
            }
        }
        closeGuest()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "sandbox_id"
        /** Optional video URI offered to guests through VirtualCamera. */
        const val EXTRA_CAMERA_URI = "camera_uri"
        const val EXTRA_CAMERA2_VIDEO = "camera2_video"
    }
}
