package com.aether.sandbox.vm

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VmProbeClient {
    suspend fun run(context: Context): JSONObject = withContext(Dispatchers.Main.immediate) {
        val app = context.applicationContext
        var bound = false
        var binder: IBinder? = null
        var death: IBinder.DeathRecipient? = null
        var connection: ServiceConnection? = null
        try {
            withTimeout(15_000) {
                suspendCancellableCoroutine { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    fun fail(error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
                    val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (continuation.isActive) {
                            runCatching {
                                JSONObject(requireNotNull(message.data.getString("report"))).also { report ->
                                    check(!report.has("error")) { report.optString("error") }
                                    check(report.getInt("probePid") != Process.myPid()) { "Probe tidak terpisah dari UI" }
                                    report.put("clientPid", Process.myPid())
                                }
                            }.fold({ continuation.resume(it) }, ::fail)
                        }
                        true
                    })
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (!continuation.isActive) return
                            binder = service
                            death = IBinder.DeathRecipient { handler.post { fail(IllegalStateException("Proses probe berhenti/crash")) } }
                            try {
                                service.linkToDeath(death!!, 0)
                                Messenger(service).send(Message.obtain(null, 1).apply { replyTo = callback })
                            } catch (error: Exception) { fail(error) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) = fail(IllegalStateException("Koneksi probe terputus"))
                        override fun onNullBinding(name: ComponentName) = fail(IllegalStateException("Probe tidak menyediakan endpoint"))
                        override fun onBindingDied(name: ComponentName) = fail(IllegalStateException("Binding probe berhenti"))
                    }
                    bound = app.bindService(Intent(app, VmProbeService::class.java), connection!!, Context.BIND_AUTO_CREATE)
                    if (!bound) fail(IllegalStateException("Proses probe tidak dapat diikat"))
                }
            }
        } finally {
            death?.let { recipient -> runCatching { binder?.unlinkToDeath(recipient, 0) } }
            if (bound) connection?.let { app.unbindService(it) }
        }
    }
}
