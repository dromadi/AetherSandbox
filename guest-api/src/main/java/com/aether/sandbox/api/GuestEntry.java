package com.aether.sandbox.api;

import android.content.Context;
import android.view.View;
import java.util.Map;

/** Explicit opt-in contract for trusted QA fixtures. Not an Android Activity virtualization API. */
public interface GuestEntry {
    View createView(Context context, Map<String, String> testProfile);
    default void onDestroy() {}
}
