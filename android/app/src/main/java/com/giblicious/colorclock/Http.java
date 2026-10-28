package com.giblicious.colorclock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

final class Http {
    private Http() {}

    static JSONObject getJson(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = open(url, "GET", readTimeoutMs);
        try {
            return parse(connection);
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject sendJson(String url, String method, String body) throws IOException {
        HttpURLConnection connection = open(url, method, 15_000);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("content-type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return parse(connection);
        } finally {
            connection.disconnect();
        }
    }

    static HttpURLConnection open(String url, String method, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        return connection;
    }

    private static JSONObject parse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " from " + connection.getURL());
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int read; (read = in.read(chunk)) != -1; ) buffer.write(chunk, 0, read);
            return new JSONObject(buffer.toString("UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Invalid JSON from " + connection.getURL(), e);
        }
    }
}
