package com.aether.sandbox.core

import android.content.Context
import com.aether.sandbox.domain.RuntimeLease
import java.io.File

/** Four guest processes. Process separation is for lifecycle control, not UID isolation. */
object RuntimeSlots {
    val activities = listOf(ProxyActivity::class.java, ProxyActivity1::class.java,
        ProxyActivity2::class.java, ProxyActivity3::class.java)
    private fun id(slot: Int) = "00000000-0000-4000-8000-${slot.toString().padStart(12, '0')}"
    private fun locks(context: Context) = File(context.filesDir, "runtime-slot-locks")
    fun acquire(context: Context, slot: Int): RuntimeLease = RuntimeLease.acquire(locks(context), id(slot))
    fun free(context: Context): Int? = activities.indices.firstOrNull { !RuntimeLease.isHeld(locks(context), id(it)) }
}
class ProxyActivity1 : ProxyActivity() { override val slot = 1 }
class ProxyActivity2 : ProxyActivity() { override val slot = 2 }
class ProxyActivity3 : ProxyActivity() { override val slot = 3 }
