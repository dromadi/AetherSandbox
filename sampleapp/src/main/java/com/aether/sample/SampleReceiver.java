package com.aether.sample;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Declared only, to exercise the host's guest-component registry. Not dispatched yet. */
public final class SampleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {}
}
