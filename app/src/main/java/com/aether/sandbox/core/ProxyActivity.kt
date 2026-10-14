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

class ProxyActivity : ComponentActivity() {
    private var guest: GuestLifecycle? = null
    private var generalApp: android.app.Application? = null
    @Volatile private var lease: RuntimeLease? = null
    private var loadingJob: Job? = null
    private var stopReceiver: BroadcastReceiver? = null
    private var camera: VideoCameraSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { finish() }
        val loading = TextView(this).apply { text = "Memuat guest…"; gravity = Gravity.CENTER }
        setContentView(loading)
        val repository = SandboxRepository(this)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            loading.text = "Guest gagal dimuat: instance tidak ditentukan"
            return
        }
        stopReceiver = RuntimeControl.register(this, id) { finish() }
        loadingJob = lifecycleScope.launch {
            try {
                val record = withContext(Dispatchers.IO) {
                    // The host's status probe can hold the free lease for an instant: retry briefly.
                    lease = repository.acquireRuntime(id, SandboxRepository.LAUNCH_LEASE_WAIT_MILLIS)
                    repository.find(id)
                }
                if (record.trustedFixture) loadFixture(repository, record)
                else loadGeneralApp(repository, record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
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
        val loaded = withContext(Dispatchers.IO) { GeneralGuestLoader.load(this@ProxyActivity, record, repository) }
        generalApp = loaded.application
        val c = loaded.components
        val report = buildString {
            append("Kode dan resource guest dimuat di instance terisolasi.\n\n")
            append("Paket: ${record.packageName}\n")
            append("Application: ${loaded.application.javaClass.name}\n")
            append("Classloader: ${loaded.context.classLoader.javaClass.simpleName}\n")
            append("Data: ${loaded.context.filesDir.parent}\n\n")
            append("Komponen terdaftar (${c.total}):\n")
            append("• Activity: ${c.activities.size}   • Service: ${c.services.size}\n")
            append("• Provider: ${c.providers.size}   • Receiver: ${c.receivers.size}\n")
            append("Launcher: ${c.launcher ?: "tidak ada"}\n\n")
            append("Milestone 1–2a: Application.onCreate() guest berjalan dan komponen sudah diinventarisasi. Menjalankan Activity/Service/Provider guest belum didukung (perlu intersepsi ActivityManager/Binder).")
        }
        setContentView(runtimeFrame("Aether runtime • aplikasi umum") {
            addView(TextView(this@ProxyActivity).apply { text = report }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
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
                setOnClickListener { finish() }
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
    override fun onResume() { super.onResume(); dispatch { it.resume() } }
    override fun onPause() { dispatch { it.pause() }; super.onPause() }
    override fun onStop() { dispatch { it.stop() }; super.onStop() }

    private fun closeGuest() {
        val current = guest
        guest = null
        runCatching { current?.close() }.onFailure { Log.e("AetherRuntime", "Guest cleanup failed", it) }
        val app = generalApp
        generalApp = null
        runCatching { app?.onTerminate() }.onFailure { Log.e("AetherRuntime", "Guest terminate failed", it) }
        // After the guest's own onDestroy, so a guest that stops its camera there wins the race.
        camera?.close()
        camera = null
    }

    override fun onDestroy() {
        stopReceiver?.let { runCatching { unregisterReceiver(it) } }
        stopReceiver = null
        closeGuest()
        val job = loadingJob
        job?.cancel()
        // Cancellation does not interrupt file IO immediately. Keep the lease until loading exits;
        // the IO job may still be acquiring it, so read the field only after the job completes.
        val release = {
            val held = lease
            lease = null
            runCatching { held?.close() }
                .onFailure { Log.e("AetherRuntime", "Runtime lease release failed", it) }
            Unit
        }
        if (job == null || job.isCompleted) release()
        else job.invokeOnCompletion { release() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "sandbox_id"
        /** Optional video URI offered to guests through VirtualCamera. */
        const val EXTRA_CAMERA_URI = "camera_uri"
    }
}
