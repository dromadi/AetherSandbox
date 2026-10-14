package com.aether.sandbox.api;

/**
 * Opt-in camera input for trusted QA fixtures. Frames come from a video the user selected in the
 * host, never from a physical camera, and only guests that implement
 * {@link GuestEntry#onVirtualCamera} receive them. Other applications' cameras are not touched.
 *
 * <p>Delivery is deterministic: every decoded frame is delivered once, in presentation order,
 * with the timestamp stored in the video. Nothing is dropped or paced by wall-clock time, so the
 * same video on the same device yields the same frame sequence on every run.
 */
public interface VirtualCamera {
    /**
     * Starts delivery on a background thread, restarting from the first frame if already running.
     * The next frame is decoded only after {@link FrameListener#onFrame} returns.
     */
    void start(FrameListener listener);

    /** Stops delivery and waits briefly for the decoder thread. Safe to call repeatedly. */
    void stop();

    /** Human-readable description of the source, for display only. */
    String description();

    /** Callbacks run on the camera thread, never on the main thread. */
    interface FrameListener {
        void onFrame(VirtualCameraFrame frame);
        /** Called after the last frame when the video ended normally. */
        default void onEnd(int frameCount) {}
        default void onError(Exception error) {}
    }
}
