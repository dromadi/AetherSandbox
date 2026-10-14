package com.aether.sandbox.domain;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashSet;
import java.util.Set;

/** Exclusive per-instance lease shared by runtime and destructive mutations. */
public final class RuntimeLease implements AutoCloseable {
    private static final Set<String> ACTIVE = new HashSet<>();
    private final RandomAccessFile file;
    private final FileLock lock;
    private final String key;
    private boolean closed;

    private RuntimeLease(RandomAccessFile file, FileLock lock, String key) {
        this.file = file;
        this.lock = lock;
        this.key = key;
    }

    public static synchronized RuntimeLease acquire(File lockRoot, String id) throws IOException {
        SandboxPaths.requireInstanceId(id);
        if (!lockRoot.isDirectory() && !lockRoot.mkdirs() && !lockRoot.isDirectory()) {
            throw new IOException("Gagal membuat direktori kontrol runtime");
        }
        // Never unlink lock files: replacing the inode would permit two simultaneous leases.
        File target = SandboxPaths.resolve(lockRoot, id + ".lock");
        String key = target.getPath();
        // POSIX locks can be released when ANY descriptor for the inode is closed by this
        // process. Reject local duplicates before opening another descriptor.
        if (ACTIVE.contains(key)) throw busy();
        RandomAccessFile file = new RandomAccessFile(target, "rw");
        try {
            FileLock lock;
            try {
                lock = file.getChannel().tryLock();
            } catch (OverlappingFileLockException busy) {
                lock = null;
            }
            if (lock == null) {
                throw busy();
            }
            ACTIVE.add(key);
            return new RuntimeLease(file, lock, key);
        } catch (IOException | RuntimeException error) {
            try { file.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /**
     * Reports whether another owner holds the lease. The probe briefly takes the lease when it is
     * free, so a runtime starting at the same moment must retry {@link Busy} for a short time.
     */
    public static synchronized boolean isHeld(File lockRoot, String id) throws IOException {
        try (RuntimeLease ignored = acquire(lockRoot, id)) {
            return false;
        } catch (Busy busy) {
            return true;
        }
    }

    private static Busy busy() {
        return new Busy();
    }

    /** The instance is leased by a runtime or a destructive mutation. */
    public static final class Busy extends IOException {
        Busy() { super("Instance sedang digunakan. Hentikan guest sebelum mengubah atau menghapus data."); }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        synchronized (RuntimeLease.class) {
            try { lock.release(); } finally {
                try { file.close(); } finally { ACTIVE.remove(key); }
            }
        }
    }
}
