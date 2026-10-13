package com.aether.sandbox.domain;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class BoundedCopyTest {
    @Test public void hashesExactBoundaryAndPreservesBytes() throws Exception {
        var output = new ByteArrayOutputStream();
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                BoundedCopy.copyAndHash(new ByteArrayInputStream(new byte[]{97,98,99}), output, 3));
        assertArrayEquals(new byte[]{97,98,99}, output.toByteArray());
    }
    @Test public void rejectsEmptyAndProviderThatLiesAboutSize() {
        assertThrows(IOException.class, () -> BoundedCopy.copyAndHash(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 3));
        var input = new ByteArrayInputStream(new byte[4]) { @Override public int available() { return 0; } };
        assertThrows(IOException.class, () -> BoundedCopy.copyAndHash(input, new ByteArrayOutputStream(), 3));
    }
    @Test public void interruptedCopyDoesNotPublishData() {
        Thread.currentThread().interrupt();
        try {
            var output = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> BoundedCopy.copyAndHash(new ByteArrayInputStream(new byte[3]), output, 3));
            assertEquals(0, output.size());
        } finally { Thread.interrupted(); }
    }
}
