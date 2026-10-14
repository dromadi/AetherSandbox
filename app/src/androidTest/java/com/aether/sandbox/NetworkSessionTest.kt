package com.aether.sandbox

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aether.sandbox.core.ProxyActivity
import com.aether.sandbox.core.ProxyActivity1
import com.aether.sandbox.core.RuntimeRouterActivity
import com.aether.sandbox.data.SandboxRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NetworkSessionTest {
    @Test fun twoRealHttpSessionsStayIndependentAfterProcessRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val source = File(host.cacheDir, "network-sample.apk")
        instrumentation.context.assets.open("sampleapp.apk").use { input -> source.outputStream().use { input.copyTo(it) } }
        val repo = SandboxRepository(host)
        val first = repo.importApk(Uri.fromFile(source))
        val second = repo.importApk(Uri.fromFile(source))
        val sessions = ConcurrentHashMap<String, String>()
        val failure = AtomicReference<Throwable>()
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = Thread({
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val split = line.indexOf(':')
                        if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                    }
                    var status = "200 OK"
                    val body = when {
                        request.startsWith("/login?user=") -> UUID.randomUUID().toString().also {
                            sessions[it] = URLDecoder.decode(request.substringAfter("user="), "UTF-8")
                        }
                        request == "/me" -> headers["authorization"]?.removePrefix("Bearer ")?.let { sessions[it] }
                            ?: run { status = "401 Unauthorized"; "unauthorized" }
                        else -> { status = "404 Not Found"; "not found" }
                    }.toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body); output.flush()
                }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }, "test-session-server").apply { isDaemon = true; start() }
        fun launch(id: String, activity: Class<*>) = host.startActivity(Intent(host, activity)
            .putExtra(ProxyActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun appears(text: String) = assertTrue("Missing $text; server failure=${failure.get()}",
            device.wait(Until.hasObject(By.text(text)), 15000))
        fun login(name: String) {
            appears("Sesi server: belum masuk")
            device.findObject(By.clazz("android.widget.EditText")).text = name
            val button = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Masuk server uji", java.util.regex.Pattern.CASE_INSENSITIVE))), 5000)
            assertNotNull(button); button.click()
            appears("Sesi server: $name")
        }
        try {
            for (record in listOf(first, second)) {
                File(repo.directory(record.id), "data/files").mkdirs()
                File(repo.directory(record.id), "data/files/server-url.txt").writeText("http://127.0.0.1:${server.localPort}")
            }
            launch(first.id, ProxyActivity::class.java); login("alpha")
            launch(second.id, ProxyActivity1::class.java); login("beta")
            assertTrue(repo.isRunning(first.id)); assertTrue(repo.isRunning(second.id))
            assertEquals(setOf("alpha", "beta"), sessions.values.toSet())
            assertEquals(2, sessions.size)
            repo.stop(first.id)
            launch(second.id, RuntimeRouterActivity::class.java); appears("Sesi server: beta")
            repo.stop(second.id)
            launch(first.id, ProxyActivity::class.java); appears("Sesi server: alpha")
            repo.stop(first.id)
            launch(second.id, ProxyActivity1::class.java); appears("Sesi server: beta")
            assertEquals("Relaunch must reuse tokens instead of logging in again", 2, sessions.size)
            assertNull(failure.get())
        } finally {
            runCatching { repo.stop(first.id) }; runCatching { repo.stop(second.id) }
            server.close(); worker.join(6000)
            repo.delete(first.id); repo.delete(second.id); source.delete()
        }
    }
}
