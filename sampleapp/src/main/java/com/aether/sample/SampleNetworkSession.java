package com.aether.sample;

import android.content.Context;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Real HTTP session with explicitly persisted per-app tokens, no global CookieHandler. */
final class SampleNetworkSession {
    static boolean configured(Context context) {
        return new File(context.getFilesDir(), "server-url.txt").isFile();
    }
    static String login(Context context, String user) throws Exception {
        String token = request(context, "/login?user=" + URLEncoder.encode(user, "UTF-8"), null);
        if (!context.getSharedPreferences("network-session", Context.MODE_PRIVATE).edit()
            .putString("token", token).commit()) throw new java.io.IOException("Cannot persist session");
        return current(context);
    }
    static String current(Context context) throws Exception {
        String token = context.getSharedPreferences("network-session", Context.MODE_PRIVATE).getString("token", null);
        if (token == null) return "belum masuk";
        return request(context, "/me", token);
    }
    private static String request(Context context, String path, String token) throws Exception {
        String base = new String(Files.readAllBytes(new File(context.getFilesDir(), "server-url.txt").toPath()), StandardCharsets.UTF_8).trim();
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            try (var input = connection.getInputStream()) {
                var bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
