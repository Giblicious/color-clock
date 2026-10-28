package com.giblicious.colorclock;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Installs new versions from the latest GitHub release. The release carries an APK named
 * {@code color-clock-<versionCode>.apk}. As device owner the install is silent; otherwise Android
 * asks once per update. Android itself rejects APKs not signed with this app's release key.
 */
final class Updater {
    private static final String TAG = "ColorClockUpdater";
    private static final String LATEST_RELEASE = "https://api.github.com/repos/Giblicious/color-clock/releases/latest";
    private static final Pattern APK_NAME = Pattern.compile("^color-clock-(\\d+)\\.apk$");
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private Updater() {}

    interface Listener { void onStatus(String message); }

    static void checkInBackground(Context context, Listener listener) {
        if (!running.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                listener.onStatus(check(app));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                listener.onStatus("Couldn't check for updates");
            } finally {
                running.set(false);
            }
        }, "color-clock-updater").start();
    }

    private static String check(Context context) throws IOException {
        JSONObject release = Http.getJson(LATEST_RELEASE, 15_000);
        JSONArray assets = release.optJSONArray("assets");
        JSONObject apk = null;
        int versionCode = 0;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            Matcher match = APK_NAME.matcher(asset.optString("name"));
            if (match.matches()) {
                apk = asset;
                versionCode = Integer.parseInt(match.group(1));
            }
        }
        if (apk == null) throw new IOException("latest release has no APK");
        if (versionCode <= BuildConfig.VERSION_CODE) return "Up to date";

        File file = new File(context.getCacheDir(), "update.apk");
        String sha256 = download(apk.optString("browser_download_url"), file);
        // GitHub publishes a digest for each asset; refuse a download that doesn't match it.
        String expected = apk.optString("digest", "");
        if (expected.startsWith("sha256:") && !expected.substring(7).equalsIgnoreCase(sha256)) {
            file.delete();
            throw new IOException("downloaded APK does not match the published digest");
        }
        install(context, file);
        return "Installing " + release.optString("tag_name") + "…";
    }

    private static String download(String url, File target) throws IOException {
        HttpURLConnection connection = Http.open(url, "GET", 60_000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode() + " downloading APK");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] chunk = new byte[64 * 1024];
                for (int read; (read = in.read(chunk)) != -1; ) {
                    digest.update(chunk, 0, read);
                    out.write(chunk, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            connection.disconnect();
        }
    }

    private static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("color-clock.apk", 0, apk.length())) {
                byte[] chunk = new byte[64 * 1024];
                for (int read; (read = in.read(chunk)) != -1; ) out.write(chunk, 0, read);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(context, sessionId, new Intent(context, InstallReceiver.class), flags);
            session.commit(result.getIntentSender());
        }
    }
}
