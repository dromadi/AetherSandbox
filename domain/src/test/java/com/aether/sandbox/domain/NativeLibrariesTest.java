package com.aether.sandbox.domain;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class NativeLibrariesTest {
    private File apk(File root, String... names) throws Exception {
        File file = new File(root, "guest.apk");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml")); zip.write(new byte[]{1}); zip.closeEntry();
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write(name.getBytes()); zip.closeEntry(); }
        }
        return file;
    }
    @Test public void selectsProcessAbiAndKeepsOtherArchitecturesOut() throws Exception {
        File root = Files.createTempDirectory("native-test").toFile();
        File output = NativeLibraries.extract(apk(root, "lib/arm64-v8a/libtest.so", "lib/x86_64/libtest.so"),
            new File(root, "native"), List.of("x86_64", "arm64-v8a"));
        assertEquals("lib/x86_64/libtest.so", Files.readString(new File(output, "libtest.so").toPath()));
        assertEquals(1, output.list().length);
        assertFalse(new File(root, "native-pending").exists());
    }
    @Test public void rejectsIncompatibleAbiInsteadOfLaunchingBrokenNativeApp() throws Exception {
        File root = Files.createTempDirectory("native-test").toFile();
        assertThrows(java.io.IOException.class, () -> NativeLibraries.extract(apk(root, "lib/armeabi-v7a/libtest.so"),
            new File(root, "native"), List.of("arm64-v8a")));
        assertFalse(new File(root, "native").exists());
    }
    @Test public void javaOnlyAppNeedsNoNativeDirectory() throws Exception {
        File root = Files.createTempDirectory("native-test").toFile();
        assertNull(NativeLibraries.extract(apk(root), new File(root, "native"), List.of("x86_64")));
    }
    @Test public void rejectsTraversalBeforeExtracting() throws Exception {
        File root = Files.createTempDirectory("native-test").toFile();
        assertThrows(java.io.IOException.class, () -> NativeLibraries.extract(apk(root, "lib/x86_64/../../outside.so"),
            new File(root, "native"), List.of("x86_64")));
        assertFalse(new File(root, "outside.so").exists());
    }
}
