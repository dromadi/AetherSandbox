package com.aether.sandbox.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Host → runtime control channel. Broadcasts are package-scoped and the runtime receiver is not
 * exported, so other applications can neither send nor observe stop requests.
 */
object RuntimeControl {
    private const val ACTION_STOP = "com.aether.sandbox.action.STOP_GUEST"
    private const val EXTRA_ID = "sandbox_id"

    fun requestStop(context: Context, id: String) {
        context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName).putExtra(EXTRA_ID, id))
    }

    /** Registers [onStop] for requests addressed to instance [id]. Unregister the returned receiver. */
    fun register(context: Context, id: String, onStop: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(EXTRA_ID) == id) onStop()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}
