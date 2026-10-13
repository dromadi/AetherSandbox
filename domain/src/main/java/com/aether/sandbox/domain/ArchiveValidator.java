package com.aether.sandbox.domain;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.zip.ZipFile;

public final class ArchiveValidator {
    private ArchiveValidator() {}

    /** Validate without extracting or executing entries. PackageManager verifies Android metadata later. */
    public static void validate(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            var entries = zip.entries();
            var seen = new HashSet<String>();
            long expanded = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
                    throw new IOException("Invalid APK entry path");
                }
                for (String part : name.split("/")) {
                    if (part.equals("..")) throw new IOException("APK path traversal");
                }
                if (!seen.add(name) || seen.size() > 20000) throw new IOException("Invalid APK entry count");
                long size = entry.getSize();
                if (size < 0 || size > 512L * 1024 * 1024 || size > 1024L * 1024 * 1024 - expanded) {
                    throw new IOException("APK expanded size exceeds limit");
                }
                expanded += size;
            }
            var manifest = zip.getEntry("AndroidManifest.xml");
            if (manifest == null || manifest.getSize() <= 0 || manifest.getSize() > 4L * 1024 * 1024) {
                throw new IOException("APK has no valid manifest");
            }
        }
    }
}
