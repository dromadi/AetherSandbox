package com.aether.sample;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Ordinary startup provider, deliberately independent of the Aether API. */
public final class SampleInitProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            throw new IllegalStateException("Provider must initialize on main thread");
        if (!(getContext().getApplicationContext() instanceof SampleApplication))
            throw new IllegalStateException("Provider must see guest Application");
        try {
            Files.write(new File(getContext().getFilesDir(), "provider.txt").toPath(),
                ("provider:" + getContext().getPackageName()).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
