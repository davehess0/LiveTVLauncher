package com.dave.livetvlauncher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small on-device log for troubleshooting.
 *
 * One file per day (live-tv-2026-11-22.log); only the last {@link #KEEP_DAYS} days are kept, and a
 * day's file stops growing at {@link #MAX_BYTES}. Files live in the app's external files folder, so
 * they can be read without root:
 *   adb pull /sdcard/Android/data/com.dave.livetvlauncher/files/logs
 * or viewed on the TV in Settings → Log. Secrets (Plex token, HDHomeRun DeviceAuth, URL passwords)
 * are masked before anything is written.
 */
final class AppLog {
    private AppLog() { }

    static final int KEEP_DAYS = 3;
    static final long MAX_BYTES = 1024 * 1024; // 1 MB a day
    private static final String TAG = "LiveTVHome";

    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "applog");
        t.setDaemon(true);
        return t;
    });
    private static volatile File dir;
    private static String openDay = "";
    private static boolean capped;

    /** Call once at startup (App.onCreate). Also installs a crash logger. */
    static void init(Context ctx) {
        if (dir != null) return;
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "logs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        dir = d;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            writeNow("CRASH", "App", "Uncaught on " + t.getName() + ": " + stack(e)); // synchronous: the process is about to die
            if (prev != null) prev.uncaughtException(t, e);
        });
        String version;
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        i("App", "Started · version " + version + " · Android " + android.os.Build.VERSION.RELEASE
                + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
    }

    static void i(String tag, String msg) { log("INFO", tag, msg, null); }
    static void w(String tag, String msg) { log("WARN", tag, msg, null); }
    static void e(String tag, String msg, Throwable t) { log("ERROR", tag, msg, t); }

    private static void log(String level, String tag, String msg, Throwable t) {
        String text = mask(msg + (t != null ? " · " + t.getClass().getSimpleName() + ": " + t.getMessage() : ""));
        if ("ERROR".equals(level)) Log.e(TAG, tag + ": " + text); else Log.i(TAG, tag + ": " + text);
        long now = System.currentTimeMillis();
        io.execute(() -> append(now, level, tag, text));
    }

    private static void writeNow(String level, String tag, String msg) {
        append(System.currentTimeMillis(), level, tag, mask(msg));
    }

    private static synchronized void append(long when, String level, String tag, String text) {
        File d = dir;
        if (d == null) return;
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(when));
        if (!day.equals(openDay)) { openDay = day; capped = false; prune(d); }
        File f = new File(d, "live-tv-" + day + ".log");
        if (capped) return;
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(when))
                + " " + level + " " + tag + ": " + text + "\n";
        try (FileWriter w = new FileWriter(f, true)) {
            if (f.length() + line.length() > MAX_BYTES) {
                w.write(line.substring(0, 13) + "WARN Log: today's log is full (" + MAX_BYTES / 1024
                        + " KB); nothing more is written until tomorrow.\n");
                capped = true;
                return;
            }
            w.write(line);
        } catch (IOException ignored) {
            // Storage full or unavailable: logging must never break the app.
        }
    }

    /** Delete log files older than KEEP_DAYS (file names sort by date). */
    private static void prune(File d) {
        File[] fs = d.listFiles((x, name) -> name.startsWith("live-tv-") && name.endsWith(".log"));
        if (fs == null || fs.length <= KEEP_DAYS) return;
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int k = 0; k < fs.length - KEEP_DAYS; k++) //noinspection ResultOfMethodCallIgnored
            fs[k].delete();
    }

    /** Today's log, newest last (for the Settings viewer). */
    static String today() {
        File d = dir;
        if (d == null) return "";
        File f = new File(d, "live-tv-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".log");
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static String folder() { return dir != null ? dir.getAbsolutePath() : ""; }

    /** Masks tokens and passwords so logs are safe to share. */
    static String mask(String s) {
        if (s == null) return "";
        return s.replaceAll("\\b(github_pat_|gh[pousr]_)[A-Za-z0-9_]+", "$1•••")
                .replaceAll("(?i)(X-Plex-Token=)[^&\\s\"]+", "$1•••")
                .replaceAll("(?i)(DeviceAuth=)[^&\\s\"]+", "$1•••")
                .replaceAll("(?i)(token[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9_\\-]{6,}", "$1•••")
                .replaceAll("(?i)([?&](?:username|password|user|pass|pwd|token|key|api_key|apikey)=)[^&\\s\"]+", "$1•••")
                .replaceAll("(?i)(https?://[^/\\s]+/(?:live|movie|series)/)[^/\\s]+/[^/\\s]+/", "$1•••/•••/")
                .replaceAll("(rtsps?://|https?://)([^/@\\s:]+):([^/@\\s]+)@", "$1$2:•••@");
    }

    static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) + "…" : s;
    }
}
