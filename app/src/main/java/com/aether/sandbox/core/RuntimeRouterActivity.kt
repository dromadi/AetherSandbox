package com.aether.sandbox.core

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aether.sandbox.data.SandboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-side entry point used by cards and shortcuts. An occupied slot never replaces its guest. */
class RuntimeRouterActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Menyiapkan runtime…" })
        lifecycleScope.launch {
            try {
                val id = requireNotNull(intent.getStringExtra(ProxyActivity.EXTRA_ID))
                val slot = withContext(Dispatchers.IO) {
                    val repo = SandboxRepository(this@RuntimeRouterActivity)
                    repo.find(id)
                    check(!repo.isRunning(id)) { "Instance sudah berjalan" }
                    RuntimeSlots.free(this@RuntimeRouterActivity)
                        ?: error("Empat slot sedang digunakan. Hentikan salah satu guest.")
                }
                startActivity(Intent(this@RuntimeRouterActivity, RuntimeSlots.activities[slot])
                    .putExtra(ProxyActivity.EXTRA_ID, id)
                    .putExtra(ProxyActivity.EXTRA_CAMERA_URI, intent.getStringExtra(ProxyActivity.EXTRA_CAMERA_URI))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: Exception) {
                Toast.makeText(this@RuntimeRouterActivity, error.message ?: "Runtime gagal", Toast.LENGTH_LONG).show()
            } finally { finish() }
        }
    }
}
