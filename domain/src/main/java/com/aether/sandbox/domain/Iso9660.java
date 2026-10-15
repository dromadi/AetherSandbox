package com.aether.sandbox.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded ISO9660 root reader. No Rock Ridge paths, symlinks or arbitrary extraction. */
public final class Iso9660 {
    private static final int SECTOR = 2048;
    private record Entry(long offset, long size) {}
    private static long uint(byte[] data, int at) {
        return Integer.toUnsignedLong((data[at] & 255) | ((data[at + 1] & 255) << 8) |
            ((data[at + 2] & 255) << 16) | ((data[at + 3] & 255) << 24));
    }
    private static void bounds(long offset, long size, long length) throws IOException {
        if (offset < 0 || size < 0 || offset > length || size > length - offset) throw new IOException("ISO extent outside image");
    }
    public static void extractBootFiles(File iso, File directory) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(iso, "r")) {
            byte[] pvd = new byte[SECTOR];
            input.seek(16L * SECTOR); input.readFully(pvd);
            if (pvd[0] != 1 || !new String(pvd, 1, 5, StandardCharsets.US_ASCII).equals("CD001") || pvd[6] != 1)
                throw new IOException("Unsupported ISO9660 volume");
            long offset = uint(pvd, 158) * SECTOR, size = uint(pvd, 166);
            bounds(offset, size, input.length());
            if (size > 1024 * 1024) throw new IOException("ISO root too large");
            byte[] root = new byte[(int)size]; input.seek(offset); input.readFully(root);
            Map<String, Entry> entries = new HashMap<>();
            for (int at = 0; at < root.length;) {
                int count = root[at] & 255;
                if (count == 0) { at = (at / SECTOR + 1) * SECTOR; continue; }
                if (count < 34 || count > root.length - at) throw new IOException("Invalid ISO directory record");
                int names = root[at + 32] & 255;
                if (33 + names > count) throw new IOException("Invalid ISO filename");
                String name = new String(root, at + 33, names, StandardCharsets.US_ASCII).replaceFirst(";[0-9]+$", "").toUpperCase(Locale.ROOT);
                if (name.equals("KERNEL") || name.equals("INITRD.IMG")) {
                    if ((root[at + 25] & (2 | 128)) != 0 || root[at + 1] != 0 || root[at + 26] != 0 || root[at + 27] != 0)
                        throw new IOException("Unsupported boot extent");
                    Entry entry = new Entry(uint(root, at + 2) * SECTOR, uint(root, at + 10));
                    bounds(entry.offset, entry.size, input.length());
                    if (entry.size == 0 || entry.size > 64L * 1024 * 1024 || entries.put(name, entry) != null)
                        throw new IOException("Invalid or duplicate boot file");
                }
                at += count;
            }
            if (entries.size() != 2) throw new IOException("ISO needs root KERNEL and INITRD.IMG");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create boot directory");
            for (var item : entries.entrySet()) {
                File output = new File(directory, item.getKey().equals("KERNEL") ? "kernel" : "initrd.img");
                input.seek(item.getValue().offset);
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    byte[] buffer = new byte[65536]; long remaining = item.getValue().size;
                    while (remaining > 0) { int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining)); if (read < 0) throw new EOFException(); stream.write(buffer, 0, read); remaining -= read; }
                    stream.getFD().sync();
                }
            }
        }
    }
    private Iso9660() {}
}
