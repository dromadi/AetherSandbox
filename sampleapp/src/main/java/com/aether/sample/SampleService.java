package com.aether.sample;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Declared only, to exercise the host's guest-component registry. Not started by the runtime yet. */
public final class SampleService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
