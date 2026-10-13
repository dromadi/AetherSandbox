package com.aether.sandbox.domain;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class BoundedCopy {
    private BoundedCopy() {}

    /** Caller owns streams. Does not trust InputStream.available() or provider-reported sizes. */
    public static String copyAndHash(InputStream input, OutputStream output, long maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Size limit must be positive");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
            if (read == 0) continue;
            if (read > maxBytes - total) throw new IOException("APK exceeds import limit");
            total += read;
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
        }
        if (total == 0) throw new IOException("Empty APK");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
