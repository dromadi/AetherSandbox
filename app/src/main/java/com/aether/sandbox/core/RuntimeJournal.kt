package com.aether.sandbox.core

import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Last lifecycle event, not a complete trace. Reports never contain guest file contents. */
class RuntimeJournal(directory: File) {
    private val file = AtomicFile(File(directory, "runtime-report.json"))
    private var lastFailure: String? = null
    @Synchronized fun record(event: String, message: String = "") {
        if (event in setOf("FAILED", "CRASHED")) lastFailure = message.take(2000)
        runCatching {
            val data = JSONObject().put("event", event).put("time", System.currentTimeMillis())
                .put("lastFailure", lastFailure).put("pid", Process.myPid()).put("message", message.take(2000)).toString()
            val output = file.startWrite()
            try { output.write(data.toByteArray()); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
}
