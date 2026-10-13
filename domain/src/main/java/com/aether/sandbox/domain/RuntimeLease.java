package com.aether.sandbox.domain;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/** Exclusive per-instance lease shared by runtime and destructive mutations. */
public final class RuntimeLease implements AutoCloseable {
    private final RandomAccessFile file;
    private final FileLock lock;
    private boolean closed;

    private RuntimeLease(RandomAccessFile file, FileLock lock) {
        this.file = file;
        this.lock = lock;
    }

    public static RuntimeLease acquire(File lockRoot, String id) throws IOException {
        SandboxPaths.requireInstanceId(id);
        if (!lockRoot.isDirectory() && !lockRoot.mkdirs() && !lockRoot.isDirectory()) {
            throw new IOException("Gagal membuat direktori kontrol runtime");
        }
        // Never unlink lock files: replacing the inode would permit two simultaneous leases.
        RandomAccessFile file = new RandomAccessFile(SandboxPaths.resolve(lockRoot, id + ".lock"), "rw");
        try {
            FileLock lock;
            try {
                lock = file.getChannel().tryLock();
            } catch (OverlappingFileLockException busy) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException("Instance sedang digunakan. Hentikan guest sebelum mengubah atau menghapus data.");
            }
            return new RuntimeLease(file, lock);
        } catch (IOException | RuntimeException error) {
            try { file.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { file.close(); }
    }
}
