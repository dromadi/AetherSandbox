package com.aether.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class SampleActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var text = new TextView(this);
        text.setText(getString(R.string.sample_label));
        setContentView(text);
    }
}
