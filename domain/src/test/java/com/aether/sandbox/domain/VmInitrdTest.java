package com.aether.sandbox.domain;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

public class VmInitrdTest {
    @Test public void preservesSourceAndAppendsValidFixedArchive() throws Exception {
        Path root = Files.createTempDirectory("vm-initrd-test");
        try {
            byte[] original = {31, -117, 8, 0, 1, 2, 3};
            Files.write(root.resolve("initrd.img"), original);
            File target = VmInitrd.prepare(root.toFile());
            byte[] derived = Files.readAllBytes(target.toPath());
            assertArrayEquals(original, Files.readAllBytes(root.resolve("initrd.img")));
            assertArrayEquals(original, Arrays.copyOf(derived, original.length));
            byte[] cpio;
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(derived,
                    original.length, derived.length - original.length))) { cpio = gzip.readAllBytes(); }
            Map<String, byte[]> entries = new LinkedHashMap<>();
            int at = 0;
            while (true) {
                assertTrue(at + 110 <= cpio.length);
                assertEquals("070701", new String(cpio, at, 6, StandardCharsets.US_ASCII));
                int size = hex(cpio, at + 54), names = hex(cpio, at + 94);
                assertTrue(names > 0 && at + 110 + names <= cpio.length);
                assertEquals(0, cpio[at + 110 + names - 1]);
                String name = new String(cpio, at + 110, names - 1, StandardCharsets.US_ASCII);
                int data = align(at + 110 + names);
                assertTrue(size >= 0 && data + size <= cpio.length);
                if (name.equals("TRAILER!!!")) { assertEquals(0, size); break; }
                assertNull(entries.put(name, Arrays.copyOfRange(cpio, data, data + size)));
                at = align(data + size);
            }
            assertEquals(List.of("scripts", "scripts/99-aether"), new ArrayList<>(entries.keySet()));
            String script = new String(entries.get("scripts/99-aether"), StandardCharsets.UTF_8);
            assertTrue(script.contains("on post-fs-data\n"));
            assertTrue(script.contains("setprop pm.dexopt.first-boot extract\n"));
            assertFalse(script.contains("setprop sys.boot_completed"));
            assertFalse(script.contains("assume-verified\n"));
        } finally { remove(root); }
    }

    @Test public void repeatedPreparationDoesNotAccumulateOverlays() throws Exception {
        Path root = Files.createTempDirectory("vm-initrd-repeat");
        try {
            Files.write(root.resolve("initrd.img"), new byte[]{31, -117, 8});
            byte[] first = Files.readAllBytes(VmInitrd.prepare(root.toFile()).toPath());
            assertArrayEquals(first, Files.readAllBytes(VmInitrd.prepare(root.toFile()).toPath()));
        } finally { remove(root); }
    }

    @Test public void invalidSourcePreservesPreviouslyPublishedImage() throws Exception {
        Path root = Files.createTempDirectory("vm-initrd-invalid");
        try {
            Path target = root.resolve("initrd-aether.img");
            Files.writeString(target, "previous");
            assertThrows(IOException.class, () -> VmInitrd.prepare(root.toFile()));
            Files.write(root.resolve("initrd.img"), new byte[0]);
            assertThrows(IOException.class, () -> VmInitrd.prepare(root.toFile()));
            try (RandomAccessFile sparse = new RandomAccessFile(root.resolve("initrd.img").toFile(), "rw")) {
                sparse.setLength(64L * 1024 * 1024 + 1);
            }
            assertThrows(IOException.class, () -> VmInitrd.prepare(root.toFile()));
            assertEquals("previous", Files.readString(target));
            try (var files = Files.list(root)) {
                assertFalse(files.anyMatch(p -> p.toString().endsWith(".pending")));
            }
        } finally { remove(root); }
    }
    private static int hex(byte[] data, int at) {
        return Integer.parseInt(new String(data, at, 8, StandardCharsets.US_ASCII), 16);
    }
    private static int align(int n) { return (n + 3) & ~3; }
    private static void remove(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
