package com.aether.sandbox.api;

import android.content.Context;
import android.view.View;
import java.util.Map;

/** Explicit opt-in contract for trusted QA fixtures. Not an Android Activity virtualization API. */
public interface GuestEntry {
    View createView(Context context, Map<String, String> testProfile);
    /**
     * Called before {@link #createView} when the user started this instance with a test video.
     * Guests that ignore it never receive camera frames.
     */
    default void onVirtualCamera(VirtualCamera camera) {}
    default void onStart() {}
    default void onResume() {}
    default void onPause() {}
    default void onStop() {}
    default void onDestroy() {}
}
