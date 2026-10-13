package com.aether.sandbox.core

import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aether.sandbox.api.GuestEntry
import com.aether.sandbox.data.SandboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyActivity : ComponentActivity() {
    private var guest: GuestEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loading = TextView(this).apply { text = "Memuat fixture…"; gravity = Gravity.CENTER }
        setContentView(loading)
        lifecycleScope.launch {
            try {
                val (record, loaded) = withContext(Dispatchers.IO) {
                    val repository = SandboxRepository(this@ProxyActivity)
                    val record = repository.find(requireNotNull(intent.getStringExtra(EXTRA_ID)))
                    record to VirtualClassLoader.load(this@ProxyActivity, record, repository)
                }
                guest = loaded.entry
                val layout = LinearLayout(this@ProxyActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 72, 24, 24)
                    addView(TextView(this@ProxyActivity).apply {
                        text = "Aether QA runtime • PID ${Process.myPid()} • UID ${Process.myUid()}\nProses terpisah, UID host sama"
                    })
                    addView(loaded.entry.createView(loaded.context, mapOf(
                        "androidId" to record.testAndroidId, "instanceId" to record.id,
                    )), LinearLayout.LayoutParams(-1, -1))
                }
                setContentView(layout)
            } catch (error: Exception) {
                loading.text = "Fixture gagal dimuat: ${error.message}"
            }
        }
    }

    override fun onDestroy() {
        runCatching { guest?.onDestroy() }
        super.onDestroy()
    }

    companion object { const val EXTRA_ID = "sandbox_id" }
}
