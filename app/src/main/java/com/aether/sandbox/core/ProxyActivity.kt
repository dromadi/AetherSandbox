package com.aether.sandbox.core

import android.content.BroadcastReceiver
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyActivity : ComponentActivity() {
    private var guest: GuestLifecycle? = null
    @Volatile private var lease: RuntimeLease? = null
    private var loadingJob: Job? = null
    private var stopReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { finish() }
        val loading = TextView(this).apply { text = "Memuat fixture…"; gravity = Gravity.CENTER }
        setContentView(loading)
        val repository = SandboxRepository(this)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            loading.text = "Fixture gagal dimuat: instance tidak ditentukan"
            return
        }
        stopReceiver = RuntimeControl.register(this, id) { finish() }
        loadingJob = lifecycleScope.launch {
            try {
                val (record, loaded) = withContext(Dispatchers.IO) {
                    // The host's status probe can hold the free lease for an instant: retry briefly.
                    lease = repository.acquireRuntime(id, SandboxRepository.LAUNCH_LEASE_WAIT_MILLIS)
                    val record = repository.find(id)
                    record to VirtualClassLoader.load(this@ProxyActivity, record, repository)
                }
                guest = GuestLifecycle(loaded.entry)
                val layout = LinearLayout(this@ProxyActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 72, 24, 24)
                    addView(TextView(this@ProxyActivity).apply {
                        text = "Aether QA runtime • PID ${Process.myPid()} • UID ${Process.myUid()}\nProses terpisah, UID host sama"
                    })
                    addView(Button(this@ProxyActivity).apply {
                        text = "Hentikan guest"
                        setOnClickListener { finish() }
                    })
                    addView(loaded.entry.createView(loaded.context, mapOf(
                        "androidId" to record.testAndroidId, "instanceId" to record.id,
                    )), LinearLayout.LayoutParams(-1, 0, 1f))
                }
                setContentView(layout)
                // Loading is asynchronous: synchronize with the Activity's current state.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) guest?.start()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) guest?.resume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                closeGuest()
                loading.text = "Fixture gagal dimuat: ${error.message}"
                setContentView(loading)
            }
        }
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

    companion object { const val EXTRA_ID = "sandbox_id" }
}
