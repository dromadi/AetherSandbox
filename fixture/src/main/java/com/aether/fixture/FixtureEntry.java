package com.aether.fixture;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import com.aether.sandbox.api.GuestEntry;
import java.util.Map;

public final class FixtureEntry implements GuestEntry {
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
        return layout;
    }
}
