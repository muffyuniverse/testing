package dev.pechka.selfdebug;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Checks a JSON manifest over HTTPS, downloads a newer APK and hands it to the system installer.
 * The manifest is small and public; the APK is a GitHub release asset.
 */
public final class Updater {

    public static final String DEFAULT_MANIFEST =
            "https://raw.githubusercontent.com/muffyuniverse/testing/main/app/update/version.json";

    private static final String PREFS = "selfdebug";
    private static final String KEY_URL = "manifest_url";
    private static final String APK_NAME = "selfdebug-update.apk";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private static volatile Consumer<String> sink;
    private static volatile String lastStatus = "idle";
    private static volatile long lastCheck = 0L;
    private static volatile boolean visible = false;

    public static void setVisible(boolean v) {
        visible = v;
    }

    private Updater() {
    }

    public static void setStatusSink(Consumer<String> s) {
        sink = s;
    }

    public static String lastStatus() {
        return lastStatus;
    }

    public static long lastCheck() {
        return lastCheck;
    }

    private static void status(String s) {
        lastStatus = s;
        lastCheck = System.currentTimeMillis();
        Log.i("SelfDebug", s);
        Consumer<String> c = sink;
        if (c != null) {
            c.accept(s);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String manifestUrl(Context ctx) {
        return prefs(ctx).getString(KEY_URL, DEFAULT_MANIFEST);
    }

    public static void setManifestUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_URL, url).apply();
    }

    /** Fire-and-forget check; safe from the UI thread. */
    public static void check(Context ctx, boolean autoInstall) {
        POOL.execute(() -> {
            try {
                checkBlocking(ctx, autoInstall);
            } catch (Throwable t) {
                status("check failed: " + t);
            }
        });
    }

    /** Fetches the manifest and compares version codes. Never throws. */
    public static JSONObject checkBlocking(Context ctx, boolean autoInstall) {
        JSONObject out = new JSONObject();
        try {
            String url = manifestUrl(ctx);
            status("checking " + url);
            JSONObject m = new JSONObject(httpGet(url));
            int remoteCode = m.optInt("versionCode", 0);
            String remoteName = m.optString("versionName", "?");
            out.put("manifest", m);
            out.put("localVersionCode", BuildConfig.VERSION_CODE);
            out.put("remoteVersionCode", remoteCode);
            out.put("remoteVersionName", remoteName);
            if (remoteCode > BuildConfig.VERSION_CODE) {
                status("update available: " + remoteName + " (" + remoteCode + ")");
                out.put("decision", "update_available");
                if (autoInstall) {
                    out.put("install", downloadAndInstallBlocking(ctx));
                }
            } else {
                status("up to date (" + BuildConfig.VERSION_NAME + ")");
                out.put("decision", "up_to_date");
            }
        } catch (Throwable t) {
            status("check failed: " + t);
            try {
                out.put("error", String.valueOf(t));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Downloads the APK from the manifest, verifies sha256 when present, opens the installer. */
    public static JSONObject downloadAndInstallBlocking(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            JSONObject m = new JSONObject(httpGet(manifestUrl(ctx)));
            String apkUrl = m.optString("apkUrl", "");
            String wantSha = m.optString("sha256", "");
            if (apkUrl.isEmpty()) {
                out.put("error", "manifest has no apkUrl");
                return out;
            }
            File dir = new File(ctx.getCacheDir(), "updates");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create " + dir);
            }
            File apk = new File(dir, APK_NAME);
            status("downloading " + apkUrl);
            String sha = download(apkUrl, apk);
            out.put("path", apk.getAbsolutePath());
            out.put("bytes", apk.length());
            out.put("sha256", sha);
            if (!wantSha.isEmpty() && !wantSha.equalsIgnoreCase(sha)) {
                apk.delete();
                status("sha256 mismatch; discarded");
                out.put("error", "sha256 mismatch");
                return out;
            }
            out.put("installer", launchInstaller(ctx, apk));
            return out;
        } catch (Throwable t) {
            status("download failed: " + t);
            try {
                out.put("error", String.valueOf(t));
            } catch (Exception ignored) {
            }
            return out;
        }
    }

    /** Opens the installer for an APK downloaded earlier. */
    public static JSONObject installDownloaded(Context ctx) {
        JSONObject out = new JSONObject();
        File apk = new File(new File(ctx.getCacheDir(), "updates"), APK_NAME);
        if (!apk.isFile()) {
            try {
                out.put("error", "nothing downloaded yet");
            } catch (Exception ignored) {
            }
            return out;
        }
        try {
            out.put("installer", launchInstaller(ctx, apk));
        } catch (Throwable t) {
            try {
                out.put("error", String.valueOf(t));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String launchInstaller(Context ctx, File apk) {
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            status("install permission needed; opening settings");
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(s);
            return "permission_requested";
        }
        Intent i = installIntent(ctx, apk);
        if (visible) {
            try {
                ctx.startActivity(i);
                status("installer opened");
                return "opened";
            } catch (Throwable t) {
                status("installer start failed: " + t);
            }
        }
        notifyInstaller(ctx, i);
        return "notified";
    }

    private static Intent installIntent(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** Android blocks background activity starts, so a background update lands as a notification. */
    private static void notifyInstaller(Context ctx, Intent install) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                status("no notification service");
                return;
            }
            NotificationChannel ch = new NotificationChannel("updates", "Updates",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            PendingIntent pi = PendingIntent.getActivity(ctx, 1, install,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(ctx, "updates")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Update ready")
                    .setContentText("Tap to install the new Self Debug build")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1, n);
            status("update notification posted");
        } catch (Throwable t) {
            status("notification failed: " + t);
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "SelfDebug");
        c.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 1024 * 1024) {
                    throw new IllegalStateException("manifest too large");
                }
            }
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String download(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SelfDebug");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream fo = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                fo.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format(Locale.US, "%02x", x));
        }
        return sb.toString();
    }
}
