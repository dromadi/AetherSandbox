package com.aether.sandbox.core

import android.content.Context
import android.content.Intent
import com.aether.sandbox.MainActivity
import com.aether.sandbox.domain.SandboxPaths
import java.security.MessageDigest
import java.util.UUID

/** Only locally created pinned shortcuts may route an exported launcher into guest execution. */
object ShortcutGate {
    private const val TOKEN = "aether_shortcut_token"
    private fun preferences(context: Context) = context.getSharedPreferences("shortcut-capabilities", 0)
    fun intent(context: Context, id: String): Intent {
        SandboxPaths.requireInstanceId(id)
        val prefs = preferences(context)
        val token = prefs.getString(id, null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString(id, it).commit()) { "Gagal menyimpan shortcut" }
        }
        return Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(ProxyActivity.EXTRA_ID, id).putExtra(TOKEN, token)
    }
    fun accepts(context: Context, intent: Intent): Boolean {
        val id = intent.getStringExtra(ProxyActivity.EXTRA_ID) ?: return false
        val given = intent.getStringExtra(TOKEN) ?: return false
        val expected = preferences(context).getString(id, null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }
    fun revoke(context: Context, id: String) {
        preferences(context).edit().remove(id).commit()
        context.getSystemService(android.content.pm.ShortcutManager::class.java)
            .disableShortcuts(listOf(id), "Instance sudah dihapus")
    }
}
