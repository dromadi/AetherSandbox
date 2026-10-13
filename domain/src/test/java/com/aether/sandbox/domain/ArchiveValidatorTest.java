package com.aether.sandbox.domain;

import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ArchiveValidatorTest {
    private File archive(String... names) throws Exception {
        var file = Files.createTempFile("aether-archive", ".apk").toFile();
        try (var zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1}); zip.closeEntry(); }
        }
        return file;
    }
    @Test public void permitsManifestAndResources() throws Exception {
        ArchiveValidator.validate(archive("AndroidManifest.xml", "classes.dex", "res/raw/test.mp4"));
    }
    @Test public void rejectsMissingManifestAndDangerousNames() throws Exception {
        assertThrows(IOException.class, () -> ArchiveValidator.validate(archive("classes.dex")));
        for (String name : new String[]{"../escape", "/absolute", "data/../../escape", "data\\escape"}) {
            assertThrows(IOException.class, () -> ArchiveValidator.validate(archive("AndroidManifest.xml", name)));
        }
    }
    @Test public void rejectsNonZipFile() throws Exception {
        var file = Files.createTempFile("aether-text", ".apk").toFile();
        Files.writeString(file.toPath(), "not an APK");
        assertThrows(IOException.class, () -> ArchiveValidator.validate(file));
    }
}
