package com.aether.sandbox.domain;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

public class RuntimeLeaseTest {
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
