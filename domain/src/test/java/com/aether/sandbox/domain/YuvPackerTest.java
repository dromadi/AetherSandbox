package com.aether.sandbox.domain;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class YuvPackerTest {
    @Test public void sizeRoundsChromaUpForOddDimensions() {
        assertEquals(4 * 2 + 2 * (2 * 1), YuvPacker.i420Size(4, 2));
        assertEquals(3 * 3 + 2 * (2 * 2), YuvPacker.i420Size(3, 3));
    }

    @Test public void removesRowPaddingAndHonorsCrop() {
        // 3 rows of stride 5; picture is the 2x2 window at (1, 1).
        byte[] plane = {
            0, 0, 0, 0, 9,
            0, 1, 2, 0, 9,
            0, 3, 4, 0, 9,
        };
        byte[] out = new byte[4];
        int end = YuvPacker.copyPlane(ByteBuffer.wrap(plane), 5, 1, 1, 1, 2, 2, out, 0);
        assertEquals(4, end);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, out);
    }

    @Test public void deinterleavesSemiPlanarChroma() {
        // NV12-style UV plane exposed as U with pixelStride 2 and V starting one byte later.
        byte[] uv = {10, 20, 11, 21, 12, 22, 13, 23};
        ByteBuffer buffer = ByteBuffer.wrap(uv);
        byte[] out = new byte[8];
        int next = YuvPacker.copyPlane(buffer, 4, 2, 0, 0, 2, 2, out, 0);
        ByteBuffer v = ByteBuffer.wrap(uv, 1, uv.length - 1).slice();
        next = YuvPacker.copyPlane(v, 4, 2, 0, 0, 2, 2, out, next);
        assertEquals(8, next);
        assertArrayEquals(new byte[]{10, 11, 12, 13, 20, 21, 22, 23}, out);
    }

    @Test public void rejectsPlanesSmallerThanTheFrame() {
        byte[] out = new byte[16];
        assertThrows(IllegalArgumentException.class,
            () -> YuvPacker.copyPlane(ByteBuffer.allocate(7), 4, 1, 0, 0, 4, 2, out, 0));
        assertThrows(IllegalArgumentException.class,
            () -> YuvPacker.copyPlane(ByteBuffer.allocate(8), 4, 1, 0, 0, 4, 2, new byte[7], 0));
    }
}
