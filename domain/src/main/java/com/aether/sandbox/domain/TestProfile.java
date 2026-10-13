package com.aether.sandbox.domain;

import java.security.SecureRandom;
import java.util.Locale;

/** Synthetic values passed explicitly to a fixture. Never changes host identifiers or android.os.Build. */
public final class TestProfile {
    private TestProfile() {}
    public static String newAndroidId() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder id = new StringBuilder(16);
        for (byte b : bytes) id.append(String.format(Locale.ROOT, "%02x", b & 255));
        return id.toString();
    }
    public static String requireAndroidId(String id) {
        if (id == null || !id.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("Invalid test identifier");
        return id;
    }
}
