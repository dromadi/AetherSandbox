package com.aether.sandbox.domain;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Extracts only a compatible process ABI with bounded output. Caller owns the instance lease. */
public final class NativeLibraries {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private NativeLibraries() {}

    public static File extract(File apk, File destination, List<String> supportedAbis) throws IOException {
        ArchiveValidator.validate(apk);
        try (ZipFile zip = new ZipFile(apk)) {
            var libraries = new ArrayList<java.util.zip.ZipEntry>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().matches("lib/[^/]+/[^/]+\\.so")) libraries.add(entry);
            }
            if (libraries.isEmpty()) return null;
            String abi = supportedAbis.stream().filter(candidate -> libraries.stream()
                .anyMatch(entry -> entry.getName().startsWith("lib/" + candidate + "/")))
                .findFirst().orElseThrow(() -> new IOException("APK native ABI tidak cocok dengan proses host"));
            File staging = new File(destination.getParentFile(), "native-pending");
            deleteTree(staging);
            if (!staging.mkdirs()) throw new IOException("Gagal menyiapkan library native");
            boolean committed = false;
            try {
                long remaining = MAX_BYTES;
                for (var entry : libraries) {
                    if (!entry.getName().startsWith("lib/" + abi + "/")) continue;
                    if (entry.getSize() > remaining) throw new IOException("Native library melebihi batas");
                    String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    File output = SandboxPaths.resolve(staging, name);
                    try (var input = zip.getInputStream(entry); var stream = new FileOutputStream(output)) {
                        BoundedCopy.copyAndHash(input, stream, remaining);
                        stream.getFD().sync();
                    }
                    remaining -= output.length();
                    if (!output.setReadOnly()) throw new IOException("Gagal mengunci native library");
                }
                deleteTree(destination);
                if (!staging.renameTo(destination)) throw new IOException("Gagal menerbitkan native library");
                committed = true;
                return destination;
            } finally { if (!committed) deleteTree(staging); }
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Gagal membaca direktori native");
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Gagal membersihkan library native");
    }
}
