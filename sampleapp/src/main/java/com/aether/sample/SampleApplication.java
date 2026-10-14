package com.aether.sample;

import android.app.Application;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Plain third-party app: on startup it writes a marker into its own files dir. */
public final class SampleApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try {
            File marker = new File(getFilesDir(), "started.txt");
            Files.write(marker.toPath(), ("onCreate:" + getPackageName()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
