package com.aether.sandbox.api;

/** One decoded picture in tightly packed I420: Y (width*height), then U, then V (each quarter size, rounded up). */
public final class VirtualCameraFrame {
    private final int index;
    private final long timestampUs;
    private final int width;
    private final int height;
    private final byte[] i420;

    public VirtualCameraFrame(int index, long timestampUs, int width, int height, byte[] i420) {
        this.index = index;
        this.timestampUs = timestampUs;
        this.width = width;
        this.height = height;
        this.i420 = i420;
    }

    /** Zero-based position in the delivered sequence. */
    public int getIndex() { return index; }
    /** Presentation timestamp from the video, in microseconds. */
    public long getTimestampUs() { return timestampUs; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    /** Packed I420 bytes. The array belongs to this frame; the host does not reuse it. */
    public byte[] getI420() { return i420; }
}
