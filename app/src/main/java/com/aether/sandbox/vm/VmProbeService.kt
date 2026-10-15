package com.aether.sandbox.vm

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.*
import com.aether.sandbox.BuildConfig
import com.aether.sandbox.core.NativeDiagnostics
import org.json.JSONObject

/** Bound-only worker: never starts a VM, claims readiness, or modifies device permissions. */
class VmProbeService : Service() {
    private val endpoint by lazy { Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.what == 1 && request.replyTo != null) {
            val reply = request.replyTo
            Thread({
                val report = runCatching {
                    val memory = ActivityManager.MemoryInfo()
                    getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                    JSONObject(NativeDiagnostics.vmProbe()).apply {
                        put("schemaVersion", 1)
                        put("probePid", Process.myPid())
                        put("uid", Process.myUid())
                        put("hostAbi", NativeDiagnostics.abi())
                        put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
                        put("hostApi", Build.VERSION.SDK_INT)
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("hostBuild", Build.DISPLAY)
                        put("appVersion", BuildConfig.VERSION_NAME)
                        put("totalMemoryBytes", memory.totalMem)
                        put("availableMemoryBytes", memory.availMem)
                        put("freeStorageBytes", filesDir.usableSpace)
                        put("avfFeature", packageManager.hasSystemFeature("android.software.virtualization_framework"))
                        put("avfManagePermission", checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        put("qemuPackaged", PackagedVmEngine.available(this@VmProbeService))
                        put("androidGuestBooted", false)
                        put("physicalDeviceVerified", false)
                        put("timestampUtcMillis", System.currentTimeMillis())
                    }.toString(2)
                }.getOrElse { JSONObject().put("error", "${it.javaClass.simpleName}: ${it.message}").toString() }
                runCatching { reply.send(Message.obtain(null, 1).apply { data = Bundle().apply { putString("report", report) } }) }
            }, "aether-vm-probe").start()
        }
        true
    }) }
    override fun onBind(intent: Intent): IBinder = endpoint.binder
}
