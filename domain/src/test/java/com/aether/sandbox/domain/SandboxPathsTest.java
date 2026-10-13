package com.aether.sandbox.domain;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

public class SandboxPathsTest {
    @Test public void rejectsTraversalAbsoluteAndSiblingPrefixes() throws Exception {
        File root = Files.createTempDirectory("aether-paths").toFile();
        for (String name : new String[]{"../escape", "../" + root.getName() + "-sibling/data", "/tmp/outside", "x\\y", "..", ""}) {
            assertThrows(IOException.class, () -> SandboxPaths.resolve(root, name));
        }
        assertEquals(new File(root, "files/item").getCanonicalFile(), SandboxPaths.resolve(root, "files/item"));
    }
    @Test public void rejectsSymlinkEscape() throws Exception {
        var root = Files.createTempDirectory("aether-links");
        var outside = Files.createTempDirectory("aether-outside");
        Files.createSymbolicLink(root.resolve("redirect"), outside);
        assertThrows(IOException.class, () -> SandboxPaths.resolve(root.toFile(), "redirect/payload"));
    }
    @Test public void separatesInstancesAndRejectsNonCanonicalIds() throws Exception {
        File root = Files.createTempDirectory("aether-instances").toFile();
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        assertNotEquals(SandboxPaths.instance(root, a), SandboxPaths.instance(root, b));
        assertThrows(IllegalArgumentException.class, () -> SandboxPaths.requireInstanceId("../../../data"));
        assertThrows(IllegalArgumentException.class, () -> SandboxPaths.requireInstanceId("1-1-1-1-1"));
    }
}
