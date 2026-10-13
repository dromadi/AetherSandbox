package com.aether.sandbox.domain;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class SandboxPaths {
    private SandboxPaths() {}

    public static String requireInstanceId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Invalid sandbox instance ID");
        }
        return id;
    }

    public static File resolve(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || new File(relative).isAbsolute()
                || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0) {
            throw new IOException("Invalid relative path");
        }
        File base = root.getCanonicalFile();
        File target = new File(base, relative).getCanonicalFile();
        if (!target.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("Path leaves sandbox storage");
        }
        return target;
    }

    public static File instance(File root, String id) throws IOException {
        return resolve(root, requireInstanceId(id));
    }
}
