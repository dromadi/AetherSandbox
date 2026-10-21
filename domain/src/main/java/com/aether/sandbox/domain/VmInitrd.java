package com.aether.sandbox.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/** Adds one fixed early-boot script; the verified upstream initrd remains unchanged. */
public final class VmInitrd {
    private static final long LIMIT = 64L * 1024 * 1024;
    private static final byte[] SCRIPT = ("# Aether Android 9 TCG first-boot profile\n" +
        "# Android-x86 sources /scripts after unpacking /android, before starting init.\n" +
        "# extract defers verification to ART class loading, unlike assume-verified.\n" +
        "cat >> /android/init.rc <<'AETHER_INIT'\n" +
        "\non post-fs-data\n" +
        "    setprop pm.dexopt.first-boot extract\n" +
        "    setprop pm.dexopt.boot extract\n" +
        "AETHER_INIT\n").getBytes(StandardCharsets.UTF_8);

    public static File prepare(File directory) throws IOException {
        File source = new File(directory, "initrd.img");
        if (!source.isFile() || source.length() == 0 || source.length() > LIMIT)
            throw new IOException("Invalid upstream initrd size");
        File target = new File(directory, "initrd-aether.img");
        File pending = File.createTempFile("initrd-aether-", ".pending", directory);
        try {
            try (InputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(pending)) {
                BoundedCopy.copyAndHash(input, output, LIMIT);
                // Linux accepts concatenated compressed newc archives. No input archive
                // paths are extracted on the host and no upstream file is rewritten.
                ByteArrayOutputStream extra = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(extra)) {
                    entry(gzip, 1, "scripts", 0040755, new byte[0]);
                    entry(gzip, 2, "scripts/99-aether", 0100644, SCRIPT);
                    entry(gzip, 0, "TRAILER!!!", 0, new byte[0]);
                }
                output.write(extra.toByteArray());
                output.getFD().sync();
            }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally { Files.deleteIfExists(pending.toPath()); }
    }

    private static void entry(OutputStream out, int inode, String name, int mode, byte[] data) throws IOException {
        byte[] path = (name + "\0").getBytes(StandardCharsets.US_ASCII);
        StringBuilder header = new StringBuilder("070701");
        for (int value : new int[]{inode, mode, 0, 0, 1, 0, data.length, 0, 0, 0, 0, path.length, 0})
            header.append(String.format(Locale.ROOT, "%08x", value));
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(path); pad(out, 110 + path.length);
        out.write(data); pad(out, data.length);
    }
    private static void pad(OutputStream out, int size) throws IOException {
        for (int n = (4 - size % 4) % 4; n > 0; n--) out.write(0);
    }
    private VmInitrd() {}
}
