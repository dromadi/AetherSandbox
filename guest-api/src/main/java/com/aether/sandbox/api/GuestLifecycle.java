package com.aether.sandbox.api;

/** Main-thread lifecycle dispatcher; also handles guests loaded after the host has resumed. */
public final class GuestLifecycle implements AutoCloseable {
    private final GuestEntry entry;
    private boolean started;
    private boolean resumed;
    private boolean destroyed;

    public GuestLifecycle(GuestEntry entry) { this.entry = entry; }

    public void start() {
        if (destroyed || started) return;
        started = true;
        entry.onStart();
    }

    public void resume() {
        if (destroyed || resumed) return;
        start();
        resumed = true;
        entry.onResume();
    }

    public void pause() {
        if (destroyed || !resumed) return;
        resumed = false;
        entry.onPause();
    }

    public void stop() {
        if (destroyed || !started) return;
        try { pause(); } finally {
            started = false;
            entry.onStop();
        }
    }

    @Override public void close() {
        if (destroyed) return;
        try { stop(); } finally {
            destroyed = true;
            entry.onDestroy();
        }
    }
}
