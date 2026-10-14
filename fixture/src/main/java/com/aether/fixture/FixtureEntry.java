package com.aether.fixture;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.database.sqlite.SQLiteDatabase;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import com.aether.sandbox.api.GuestEntry;
import com.aether.sandbox.api.VirtualCamera;
import com.aether.sandbox.api.VirtualCameraFrame;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

public final class FixtureEntry implements GuestEntry {
    private VirtualCamera camera;
    private int cameraRuns;

    @Override public void onVirtualCamera(VirtualCamera camera) { this.camera = camera; }

    @Override public void onStop() { if (camera != null) camera.stop(); }

    /** Deterministic camera check: a CRC32 over every frame's index, timestamp, size and pixels. */
    private View cameraPanel(Context context) {
        var panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        var status = new TextView(context);
        var preview = new ImageView(context);
        preview.setAdjustViewBounds(true);
        if (camera == null) {
            status.setText("Kamera virtual: tidak ada video. Pilih video uji di host, lalu jalankan lagi.");
            panel.addView(status);
            return panel;
        }
        status.setText("Kamera virtual: " + camera.description());
        var button = new Button(context);
        button.setText("Mulai kamera virtual");
        button.setOnClickListener(view -> {
            var digest = new CRC32();
            int run = ++cameraRuns;
            status.setText("Kamera virtual: membaca frame…");
            camera.start(new VirtualCamera.FrameListener() {
                @Override public void onFrame(VirtualCameraFrame frame) {
                    var header = java.nio.ByteBuffer.allocate(20).putInt(frame.getIndex())
                        .putLong(frame.getTimestampUs()).putInt(frame.getWidth()).putInt(frame.getHeight());
                    digest.update(header.array());
                    digest.update(frame.getI420());
                    var text = String.format(Locale.ROOT, "Frame %d • %d ms • %dx%d",
                        frame.getIndex() + 1, frame.getTimestampUs() / 1000, frame.getWidth(), frame.getHeight());
                    var thumbnail = luma(frame);
                    panel.post(() -> { status.setText(text); preview.setImageBitmap(thumbnail); });
                }
                @Override public void onEnd(int frameCount) {
                    var text = String.format(Locale.ROOT, "Kamera selesai #%d: %d frame • sidik %08x", run, frameCount, digest.getValue());
                    panel.post(() -> status.setText(text));
                }
                @Override public void onError(Exception error) {
                    panel.post(() -> status.setText("Kamera gagal: " + error.getMessage()));
                }
            });
        });
        panel.addView(button);
        panel.addView(status);
        panel.addView(preview);
        return panel;
    }

    /** Grayscale preview from the Y plane, downscaled to at most 160 px wide. */
    private static Bitmap luma(VirtualCameraFrame frame) {
        int step = Math.max(1, (frame.getWidth() + 159) / 160);
        int width = frame.getWidth() / step;
        int height = frame.getHeight() / step;
        int[] pixels = new int[width * height];
        byte[] y = frame.getI420();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int value = y[row * step * frame.getWidth() + column * step] & 0xff;
                pixels[row * width + column] = 0xff000000 | value << 16 | value << 8 | value;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
    @Override public View createView(Context context, Map<String, String> profile) {
        var layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        var status = new TextView(context);
        var preferences = context.getSharedPreferences("counter", Context.MODE_PRIVATE);
        var database = context.openOrCreateDatabase("fixture.db", Context.MODE_PRIVATE, null);
        database.execSQL("CREATE TABLE IF NOT EXISTS events (value INTEGER NOT NULL)");
        database.close();
        Runnable refresh = () -> status.setText(context.getString(R.string.fixture_title)
                + "\nTest Android ID: " + profile.get("androidId")
                + "\nInstance: " + profile.get("instanceId")
                + "\nFiles: " + context.getFilesDir()
                + "\nDatabase: " + context.getDatabasePath("fixture.db")
                + "\nPersistent counter: " + preferences.getInt("count", 0));
        var button = new Button(context);
        button.setText("Increment isolated counter");
        button.setOnClickListener(view -> {
            int next = preferences.getInt("count", 0) + 1;
            preferences.edit().putInt("count", next).apply();
            try (SQLiteDatabase db = context.openOrCreateDatabase("fixture.db", Context.MODE_PRIVATE, null)) {
                db.execSQL("INSERT INTO events VALUES (?)", new Object[]{next});
            }
            refresh.run();
        });
        refresh.run();
        layout.addView(status);
        layout.addView(button);
        layout.addView(cameraPanel(context));
        var scroll = new ScrollView(context);
        scroll.addView(layout);
        return scroll;
    }
}
