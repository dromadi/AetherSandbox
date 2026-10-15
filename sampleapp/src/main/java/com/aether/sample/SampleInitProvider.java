package com.aether.sample;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Ordinary startup provider and persistent CRUD endpoint; no Aether API. */
public final class SampleInitProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) throw new IllegalStateException("Provider must initialize on main thread");
        if (!(getContext().getApplicationContext() instanceof SampleApplication)) throw new IllegalStateException("Provider must see guest Application");
        try {
            Files.write(new File(getContext().getFilesDir(), "provider.txt").toPath(),
                ("provider:" + getContext().getPackageName()).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private android.content.SharedPreferences prefs() { return getContext().getSharedPreferences("provider-data", 0); }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"value"});
        cursor.addRow(new Object[]{prefs().getString("value", "empty")}); return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.aether.sample"; }
    @Override public Uri insert(Uri uri, ContentValues values) { update(uri, values, null, null); return uri; }
    @Override public int delete(Uri uri, String selection, String[] args) { if (!prefs().edit().remove("value").commit()) throw new IllegalStateException("Commit failed"); return 1; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        if (!prefs().edit().putString("value", values.getAsString("value")).commit()) throw new IllegalStateException("Commit failed"); return 1;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle(); result.putString("value", prefs().getString("value", "empty")); return result;
    }
}
