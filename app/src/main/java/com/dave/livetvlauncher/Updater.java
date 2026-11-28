package com.dave.livetvlauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * In-app updates. Two ways in:
 *  - Settings → System → Check for updates: asks GitHub for the newest release of {@link #REPO} and
 *    downloads its APK. GitHub is contacted only when that button is pressed.
 *  - Set up from your phone → Install an update: the phone uploads an APK file to the TV.
 * Either way the APK is checked (same app, newer version, same signing key) and handed to Android's
 * installer, which asks for confirmation on the TV. The update applies to every profile.
 */
final class Updater {
    /** GitHub repository whose Releases hold the APKs (owner/name). */
    static final String REPO = "davehess0/LiveTVLauncher";

    private Updater() { }

    /** The newest release on GitHub. */
    static final class Release {
        String version = "", notes = "", apkUrl = "", apkName = "";
        /** API address of the APK (works for private repos, with the token); "" = use apkUrl. */
        String apkApiUrl = "", token = "";
        long apkSize;
    }

    interface Progress { void update(long done, long total); }

    /**
     * Reason this profile can't install updates, or null if it can. Only Android's own install
     * restrictions count: a restricted profile (e.g. on the Shield) is usually allowed to install, and
     * an update installed from any profile replaces the app for every profile.
     */
    static String blockedReason(Context ctx) {
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        if (um != null && (um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
                || um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES))) {
            return "This profile isn't allowed to install apps. Switch to the main profile, open Live TV Home there "
                    + "(from its app list) and check for updates in its Settings. The update then applies to every profile.";
        }
        return null;
    }

    /** True once Android allows this app to install APKs ("Install unknown apps" / "Unknown sources"). */
    static boolean installAllowed(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens the screen where "install unknown apps" is turned on for this app (Android TV has its own). */
    static void openInstallPermission(Context ctx) {
        Intent[] tries = {
                new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + ctx.getPackageName())),
                new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
                new Intent(android.provider.Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // Not on this device: try the next one.
            }
        }
    }

    static String currentVersion(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    /**
     * The token for a private repo: only one set on this device. (Never built into the APK: anyone can
     * read a token out of a published APK.)
     */
    static String token(Context ctx) {
        return new Prefs(ctx).githubToken();
    }

    /** "set on this device" or "" (none). */
    static String tokenSource(Context ctx) {
        return new Prefs(ctx).githubToken().isEmpty() ? "" : "set on this device";
    }

    /** Asks GitHub for the latest release. Network call: run off the main thread. */
    static Release latest(Context ctx) throws Exception {
        String token = token(ctx);
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "LiveTVHome-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        String body;
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new Exception("GitHub turned down the token (expired or mistyped). Set a new one from your phone.");
            if (code == 404) throw new NotFound(!token.isEmpty());
            if (code != 200) throw new Exception("GitHub answered HTTP " + code);
            try (InputStream in = c.getInputStream()) { body = readAll(in); }
        } finally {
            c.disconnect();
        }
        JSONObject o = new JSONObject(body);
        Release r = new Release();
        r.token = token;
        r.version = o.optString("tag_name", "").replaceFirst("^[vV]", "");
        r.notes = o.optString("body", "").trim();
        JSONArray assets = o.optJSONArray("assets");
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name", "");
            if (!name.toLowerCase(java.util.Locale.US).endsWith(".apk")) continue;
            // Prefer the APK built for this device's processor; otherwise the first APK.
            boolean better = r.apkUrl.isEmpty() || (!abi.isEmpty() && name.contains(abi) && !r.apkName.contains(abi));
            if (better) {
                r.apkUrl = a.optString("browser_download_url", "");
                r.apkApiUrl = a.optString("url", "");
                r.apkName = name;
                r.apkSize = a.optLong("size", 0);
            }
        }
        return r;
    }

    /** GitHub returns 404 both for "no releases yet" and for a private repository. */
    static final class NotFound extends Exception {
        final boolean withToken;
        NotFound(boolean withToken) {
            super("No releases visible at github.com/" + REPO);
            this.withToken = withToken;
        }
    }

    /** True if version {@code a} (e.g. "1.10") is newer than {@code b} (e.g. "1.9"). */
    static boolean newer(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] parts(String v) {
        String[] s = (v == null ? "" : v).replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) try { out[i] = Integer.parseInt(s[i]); } catch (NumberFormatException e) { out[i] = 0; }
        return out;
    }

    /** Where a downloaded or uploaded APK waits until it's installed. */
    static File apkFile(Context ctx) {
        return new File(ctx.getCacheDir(), "update.apk");
    }

    /** Downloads the release's APK. Returns null if cancelled. Network call: run off the main thread. */
    static File download(Context ctx, Release r, Progress progress, java.util.concurrent.atomic.AtomicBoolean cancelled) throws Exception {
        File out = apkFile(ctx);
        HttpURLConnection c = openDownload(r);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Download failed: HTTP " + code);
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize;
            if (total > 0 && ctx.getCacheDir().getUsableSpace() < total + 50L * 1024 * 1024)
                throw new Exception("Not enough free space on this device for the update.");
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                long done = 0, lastReport = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (cancelled.get()) { o.close(); out.delete(); return null; }
                    o.write(buf, 0, n);
                    done += n;
                    if (progress != null && done - lastReport > 256 * 1024) { progress.update(done, total); lastReport = done; }
                }
                if (total > 0 && done != total) throw new Exception("The download was cut off. Try again.");
            }
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        return out;
    }

    /**
     * Opens the APK download. With a token it goes through the API (the only way for a private repo),
     * which redirects to a short-lived signed address on GitHub's file host; that redirect is followed
     * by hand so the token is never sent anywhere but api.github.com.
     */
    private static HttpURLConnection openDownload(Release r) throws Exception {
        boolean api = !r.token.isEmpty() && !r.apkApiUrl.isEmpty();
        String url = api ? r.apkApiUrl : r.apkUrl;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "LiveTVHome-updater");
            if (hop == 0 && api) {
                c.setRequestProperty("Authorization", "Bearer " + r.token);
                c.setRequestProperty("Accept", "application/octet-stream");
            }
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                url = new URL(new URL(url), loc).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new Exception("Too many redirects downloading the update");
    }

    /**
     * Checks the APK is this app, a newer version and signed with the same key. Returns a message
     * explaining the problem, or null if it's fine to install.
     */
    static String check(Context ctx, File apk) {
        PackageManager pm = ctx.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo got = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (got == null) return "That file isn't an Android app (APK), or it's damaged.";
        if (!ctx.getPackageName().equals(got.packageName)) return "That APK is a different app (" + got.packageName + "), not Live TV Home.";
        PackageInfo mine;
        try { mine = pm.getPackageInfo(ctx.getPackageName(), flags); } catch (Exception e) { return null; }
        if (got.getLongVersionCode() < mine.getLongVersionCode())
            return "That APK is an older version (" + got.versionName + ") than the one installed (" + mine.versionName + ").";
        byte[][] a = certs(got), b = certs(mine);
        // If Android couldn't read the archive's certificates, let the installer decide.
        if (a != null && b != null && !sameCerts(a, b))
            return "That APK was signed with a different key than the installed app, so Android won't install it over this one. "
                    + "Build the update on the same computer as the installed copy (each computer's debug key differs), "
                    + "or uninstall this app first (its settings would be lost).";
        return null;
    }

    @SuppressWarnings("deprecation")
    private static byte[][] certs(PackageInfo p) {
        android.content.pm.Signature[] s = null;
        if (Build.VERSION.SDK_INT >= 28) {
            if (p.signingInfo != null) s = p.signingInfo.getApkContentsSigners();
        } else {
            s = p.signatures;
        }
        if (s == null || s.length == 0) return null;
        byte[][] out = new byte[s.length][];
        for (int i = 0; i < s.length; i++) out[i] = s[i].toByteArray();
        return out;
    }

    private static boolean sameCerts(byte[][] a, byte[][] b) {
        if (a.length != b.length) return false;
        for (byte[] x : a) {
            boolean found = false;
            for (byte[] y : b) if (Arrays.equals(x, y)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    /**
     * Hands the APK to Android's installer. Android then asks for confirmation on screen; the result
     * arrives in {@link UpdateReceiver}. On success Android restarts the app on the new version.
     */
    static void install(Context ctx, File apk) throws Exception {
        Context app = ctx.getApplicationContext();
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                s.fsync(o);
            }
            Intent done = new Intent(app, UpdateReceiver.class);
            int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(app, id, done, f).getIntentSender());
        } catch (Exception e) {
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        } finally {
            apk.delete(); // the session holds its own copy
        }
        AppLog.i("Update", "Sent " + apk.getName() + " to the installer");
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
