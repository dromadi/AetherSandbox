package com.aether.sample;

import android.app.Application;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Plain third-party app: on startup it writes a marker into its own files dir. */
public final class SampleApplication extends Application {
    private static native String nativeGreeting();
    @Override public void onCreate() {
        super.onCreate();
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            throw new IllegalStateException("Guest Application must start on the main thread");
        }
        if (!new File(getFilesDir(), "provider.txt").exists()) {
            throw new IllegalStateException("Provider must initialize before Application.onCreate");
        }
        try {
            try {
                var own = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_PROVIDERS);
                if (!getDataDir().getPath().equals(own.applicationInfo.dataDir)) throw new IllegalStateException("Wrong guest package dataDir");
                try (var cursor = getContentResolver().query(android.net.Uri.parse("content://com.aether.sample.init/state"), null, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Provider not available in Application.onCreate");
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
            System.loadLibrary("sample_native");
            Files.write(new File(getFilesDir(), "native.txt").toPath(), nativeGreeting().getBytes(StandardCharsets.UTF_8));
            File marker = new File(getFilesDir(), "started.txt");
            Files.write(marker.toPath(), ("onCreate:" + getPackageName()).getBytes(StandardCharsets.UTF_8));
            if (new File(getFilesDir(), "start-worker").exists()) {
                Thread worker = new Thread(() -> {
                    while (true) {
                        try {
                            Files.write(new File(getFilesDir(), "heartbeat.txt").toPath(),
                                Long.toString(android.os.SystemClock.elapsedRealtime()).getBytes(StandardCharsets.UTF_8));
                            Thread.sleep(50);
                        } catch (Exception failure) { return; }
                    }
                }, "sample-worker");
                worker.setDaemon(true);
                worker.start();
            }
            if (new File(getFilesDir(), "block-startup").exists()) {
                while (true) android.os.SystemClock.sleep(100);
            }
        } catch (IOException ignored) {
        }
    }
}
