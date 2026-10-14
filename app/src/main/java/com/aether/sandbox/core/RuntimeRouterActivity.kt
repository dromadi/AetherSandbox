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
                    if (repo.isRunning(id)) -1 else RuntimeSlots.free(this@RuntimeRouterActivity)
                        ?: error("Empat slot sedang digunakan. Hentikan salah satu guest.")
                }
                if (slot == -1) {
                    val manager = getSystemService(android.app.ActivityManager::class.java)
                    val (taskId, runtimeSlot) = withContext(Dispatchers.IO) {
                        SandboxRepository(this@RuntimeRouterActivity).runtimeLocation(id)
                    }
                    val allowedRoots = setOf(RuntimeSlots.activities[runtimeSlot].name, GuestActivityRuntime.STUBS[runtimeSlot].name)
                    val task = manager.appTasks.firstOrNull {
                        val info = it.taskInfo
                        val currentId = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else @Suppress("DEPRECATION") info.id
                        currentId == taskId && info.baseIntent.component?.packageName == packageName &&
                            info.baseIntent.component?.className in allowedRoots
                    }
                        ?: error("Task guest tidak tersedia; hentikan lalu jalankan ulang")
                    task.moveToFront()
                } else startActivity(Intent(this@RuntimeRouterActivity, RuntimeSlots.activities[slot])
                    .putExtra(ProxyActivity.EXTRA_ID, id)
                    .putExtra(ProxyActivity.EXTRA_CAMERA_URI, intent.getStringExtra(ProxyActivity.EXTRA_CAMERA_URI))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: Exception) {
                android.util.Log.w("AetherActivity", "Task routing failed", error)
                Toast.makeText(this@RuntimeRouterActivity, error.message ?: "Runtime gagal", Toast.LENGTH_LONG).show()
            } finally { finish() }
        }
    }
}
