package com.aether.sandbox

import android.content.Context
import android.view.View
import com.aether.sandbox.api.GuestEntry
import com.aether.sandbox.api.GuestLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GuestLifecycleTest {
    private class Entry(val failPause: Boolean = false) : GuestEntry {
        val events = mutableListOf<String>()
        override fun createView(context: Context, testProfile: Map<String, String>): View = error("Unused")
        override fun onStart() { events += "start" }
        override fun onResume() { events += "resume" }
        override fun onPause() { events += "pause"; if (failPause) error("pause failed") }
        override fun onStop() { events += "stop" }
        override fun onDestroy() { events += "destroy" }
    }

    @Test fun lateLoadAndRepeatedCallbacksAreBalanced() {
        val entry = Entry()
        val lifecycle = GuestLifecycle(entry)
        lifecycle.resume(); lifecycle.start(); lifecycle.resume()
        lifecycle.pause(); lifecycle.pause(); lifecycle.resume()
        lifecycle.close(); lifecycle.close(); lifecycle.resume()
        assertEquals(listOf("start", "resume", "pause", "resume", "pause", "stop", "destroy"), entry.events)
    }

    @Test fun stoppedGuestCanRestart() {
        val entry = Entry()
        val lifecycle = GuestLifecycle(entry)
        lifecycle.resume(); lifecycle.stop(); lifecycle.stop(); lifecycle.resume(); lifecycle.close()
        assertEquals(listOf("start", "resume", "pause", "stop", "start", "resume", "pause", "stop", "destroy"), entry.events)
    }

    @Test fun failingPauseStillStopsAndDestroysExactlyOnce() {
        val entry = Entry(failPause = true)
        val lifecycle = GuestLifecycle(entry)
        lifecycle.resume()
        assertThrows(IllegalStateException::class.java) { lifecycle.close() }
        lifecycle.close()
        assertEquals(listOf("start", "resume", "pause", "stop", "destroy"), entry.events)
    }
}
