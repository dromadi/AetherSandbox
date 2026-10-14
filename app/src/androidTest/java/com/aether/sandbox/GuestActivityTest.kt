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
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class GuestActivityTest {
    @Test fun normalApkActivitiesNavigateReturnResultsAndKeepTwoLocalAccountsIndependent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val file = File(host.cacheDir, "activity-sample.apk")
        instrumentation.context.assets.open("sampleapp.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
        val repo = SandboxRepository(host)
        val first = repo.importApk(Uri.fromFile(file))
        val second = repo.importApk(Uri.fromFile(file))
        fun launch(id: String, slot: Class<*>) = host.startActivity(Intent(host, slot)
            .putExtra(ProxyActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun appears(text: String) {
            assertTrue("Missing guest UI '$text': " + device.findObject(By.textStartsWith("Guest gagal"))?.text,
                device.wait(Until.hasObject(By.text(text)), 15_000))
        }
        fun click(text: String) {
            val target = device.wait(Until.findObject(By.text(Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE))), 5_000)
            assertNotNull("Missing button: $text", target); target.click()
        }
        fun account(name: String) {
            appears("Sample account screen")
            device.findObject(By.clazz("android.widget.EditText")).text = name
            click("Simpan akun contoh")
            appears("Akun contoh: $name")
        }
        try {
            assertFalse(first.trustedFixture)
            launch(first.id, ProxyActivity::class.java)
            appears("Akun contoh: belum masuk")
            account("alpha")
            click("Layar kedua")
            appears("Layar kedua akun: alpha")
            click("Kembali dengan hasil")
            appears("Hasil: ok")
            click("Layar kedua")
            appears("Layar kedua akun: alpha")
            device.pressBack()
            appears("Akun contoh: alpha")
            launch(second.id, ProxyActivity1::class.java)
            appears("Akun contoh: belum masuk")
            account("beta")
            assertTrue(repo.isRunning(first.id)); assertTrue(repo.isRunning(second.id))
            // Move the first task forward without replacing its Activity stack.
            launch(first.id, RuntimeRouterActivity::class.java)
            appears("Akun contoh: alpha")
            assertTrue(repo.isRunning(second.id))
            repo.stop(first.id)
            launch(second.id, RuntimeRouterActivity::class.java)
            appears("Akun contoh: beta")
            repo.stop(second.id)
            launch(first.id, ProxyActivity::class.java)
            appears("Akun contoh: alpha")
            repo.stop(first.id)
        } finally {
            runCatching { repo.stop(first.id) }; runCatching { repo.stop(second.id) }
            repo.delete(first.id); repo.delete(second.id); file.delete()
        }
    }
}
