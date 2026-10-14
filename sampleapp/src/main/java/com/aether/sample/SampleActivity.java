package com.aether.sample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A normal APK Activity: no Aether API, no GuestEntry, no host dependency. */
public final class SampleActivity extends Activity {
    private TextView status;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);
        var title = new TextView(this); title.setText("Sample account screen"); layout.addView(title);
        var user = new EditText(this); user.setHint("Nama akun contoh"); layout.addView(user);
        status = new TextView(this); layout.addView(status);
        var save = new Button(this); save.setText("Simpan akun contoh");
        save.setOnClickListener(v -> {
            getSharedPreferences("ui-account", MODE_PRIVATE).edit().putString("user", user.getText().toString()).commit();
            user.clearFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(user.getWindowToken(), 0);
            refresh();
        }); layout.addView(save);
        var next = new Button(this); next.setText("Layar kedua");
        next.setOnClickListener(v -> startActivityForResult(new Intent(this, SampleSecondActivity.class), 41));
        layout.addView(next);
        var camera = new Button(this); camera.setText("Kamera Camera2");
        camera.setOnClickListener(v -> startActivity(new Intent(this, SampleCameraActivity.class)));
        layout.addView(camera);
        var result = new TextView(this); result.setText("Hasil: belum ada"); result.setTag("result"); layout.addView(result);
        if (SampleNetworkSession.configured(this)) {
            var online = new TextView(this); online.setText("Sesi server: memuat"); layout.addView(online);
            var login = new Button(this); login.setText("Masuk server uji"); layout.addView(login);
            login.setOnClickListener(v -> {
                String name = user.getText().toString();
                user.clearFocus();
                getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(user.getWindowToken(), 0);
                network(online, () -> SampleNetworkSession.login(this, name));
            });
            network(online, () -> SampleNetworkSession.current(this));
        }
        setContentView(layout);
        refresh();
    }
    private interface NetworkAction { String run() throws Exception; }
    private void network(TextView output, NetworkAction action) {
        new Thread(() -> {
            String message;
            try { message = "Sesi server: " + action.run(); }
            catch (Exception error) { message = "Sesi server gagal: " + error.getMessage(); }
            final String result = message;
            runOnUiThread(() -> { if (!isDestroyed()) output.setText(result); });
        }, "sample-network").start();
    }
    private void refresh() {
        status.setText("Akun contoh: " + getSharedPreferences("ui-account", MODE_PRIVATE).getString("user", "belum masuk"));
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 41 && resultCode == RESULT_OK && data != null) {
            TextView result = getWindow().getDecorView().findViewWithTag("result");
            result.setText("Hasil: " + data.getStringExtra("result"));
        }
    }
}
