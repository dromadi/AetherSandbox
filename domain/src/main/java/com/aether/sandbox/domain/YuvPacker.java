package com.aether.sandbox.domain;

import java.nio.ByteBuffer;

/**
 * Packs a strided YUV 4:2:0 plane (as exposed by android.media.Image) into a tight I420 layout.
 * Decoder buffers carry row padding and interleaved chroma; packing removes both so identical
 * pictures always produce identical bytes.
 */
public final class YuvPacker {
    private YuvPacker() {}

    /** Size of a packed I420 frame: full-resolution Y followed by quarter-resolution U and V. */
    public static int i420Size(int width, int height) {
        requireSize(width, height);
        long chroma = (long) ((width + 1) / 2) * ((height + 1) / 2);
        long total = (long) width * height + 2 * chroma;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Frame terlalu besar");
        return (int) total;
    }

    /**
     * Copies a {@code width}x{@code height} window starting at ({@code left}, {@code top}) from
     * [source] into [target] at [offset]. Returns the offset just after the copied bytes.
     */
    public static int copyPlane(ByteBuffer source, int rowStride, int pixelStride, int left, int top,
                                int width, int height, byte[] target, int offset) {
        requireSize(width, height);
        if (rowStride <= 0 || pixelStride <= 0 || left < 0 || top < 0) {
            throw new IllegalArgumentException("Stride atau offset plane tidak valid");
        }
        long last = (long) (top + height - 1) * rowStride + (long) (left + width - 1) * pixelStride;
        if (last >= source.limit()) throw new IllegalArgumentException("Plane lebih kecil dari ukuran frame");
        if (offset < 0 || (long) offset + (long) width * height > target.length) {
            throw new IllegalArgumentException("Buffer tujuan terlalu kecil");
        }
        ByteBuffer view = source.duplicate();
        for (int row = 0; row < height; row++) {
            int base = (top + row) * rowStride + left * pixelStride;
            if (pixelStride == 1) {
                view.position(base);
                view.get(target, offset, width);
                offset += width;
            } else {
                for (int column = 0; column < width; column++) {
                    target[offset++] = view.get(base + column * pixelStride);
                }
            }
        }
        return offset;
    }

    private static void requireSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Ukuran frame tidak valid");
    }
}
