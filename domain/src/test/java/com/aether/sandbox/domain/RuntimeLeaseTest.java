package com.aether.sandbox.domain;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class RuntimeLeaseTest {
    private int probe(File root, String id) throws Exception {
        String classpath = new File(RuntimeLeaseProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()
            + File.pathSeparator + new File(RuntimeLease.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
            "-cp", classpath, RuntimeLeaseProbe.class.getName(), root.getPath(), id).redirectErrorStream(true).start();
        try {
            assertTrue("Probe timed out", process.waitFor(5, TimeUnit.SECONDS));
            return process.exitValue();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test public void failedLocalDuplicateDoesNotReleaseOperatingSystemLock() throws Exception {
        File root = Files.createTempDirectory("runtime-lease").toFile();
        String id = UUID.randomUUID().toString();
        try {
            try (RuntimeLease held = RuntimeLease.acquire(root, id)) {
                assertThrows(IOException.class, () -> RuntimeLease.acquire(root, id));
                assertEquals(3, probe(root, id));
            }
            assertEquals(0, probe(root, id));
        } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
    }

    @Test public void sameInstanceIsExclusiveAndCanBeReacquiredAfterClose() throws Exception {
        File root = Files.createTempDirectory("runtime-lease").toFile();
        String id = UUID.randomUUID().toString();
        try {
            RuntimeLease held = RuntimeLease.acquire(root, id);
            try { assertThrows(IOException.class, () -> RuntimeLease.acquire(root, id)); }
            finally { held.close(); held.close(); }
            try (RuntimeLease next = RuntimeLease.acquire(root, id)) {
                assertThrows(IOException.class, () -> RuntimeLease.acquire(root, id));
            }
            assertTrue(new File(root, id + ".lock").isFile());
        } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
    }

    @Test public void differentInstancesCanRunTogether() throws Exception {
        File root = Files.createTempDirectory("runtime-lease").toFile();
        try (RuntimeLease first = RuntimeLease.acquire(root, UUID.randomUUID().toString());
             RuntimeLease second = RuntimeLease.acquire(root, UUID.randomUUID().toString())) {
            assertNotNull(first);
            assertNotNull(second);
        } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
    }

    @Test public void rejectsInvalidInstanceWithoutCreatingLockRoot() throws Exception {
        File parent = Files.createTempDirectory("runtime-lease").toFile();
        File root = new File(parent, "locks");
        try {
            assertThrows(IllegalArgumentException.class, () -> RuntimeLease.acquire(root, "../escape"));
            assertFalse(root.exists());
        } finally { parent.delete(); }
    }
}
