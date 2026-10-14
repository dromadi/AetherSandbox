package com.aether.sample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class SampleSecondActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);
        var text = new TextView(this);
        text.setText("Layar kedua akun: " + getSharedPreferences("ui-account", MODE_PRIVATE).getString("user", "belum masuk"));
        layout.addView(text);
        var done = new Button(this); done.setText("Kembali dengan hasil");
        done.setOnClickListener(v -> { setResult(RESULT_OK, new Intent().putExtra("result", "ok")); finish(); });
        layout.addView(done); setContentView(layout);
    }
}
