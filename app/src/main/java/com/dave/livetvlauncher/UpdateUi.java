package com.dave.livetvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The update dialogs, shared by Settings → Check for updates and the automatic update the home
 * screen runs when the app opens (see {@link #checkQuietly}). The work itself is in {@link Updater}.
 */
final class UpdateUi {
    private UpdateUi() { }

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** The automatic check runs at most this often while the launcher stays open. */
    static final long AUTO_EVERY_MS = 6 * 3600_000L;
    private static long lastAutoCheck;
    private static boolean autoRunning;
    /** A newer release found while the home screen wasn't showing; installed when it's back. */
    private static Updater.Release pending;

    private static boolean gone(Activity a) {
        return a.isFinishing() || a.isDestroyed();
    }

    // ---- Settings → Check for updates: says what it found, including "up to date" and errors ----

    static void checkNow(Activity a) {
        String blocked = Updater.blockedReason(a);
        if (blocked != null) {
            new AlertDialog.Builder(a).setTitle("Updates").setMessage(blocked).setPositiveButton("OK", null).show();
            return;
        }
        AlertDialog checking = new AlertDialog.Builder(a)
                .setTitle("Checking for updates…")
                .setMessage("Asking GitHub for the newest version.")
                .setNegativeButton("Cancel", null)
                .show();
        NET.execute(() -> {
            Updater.Release r = null;
            String error = null;
            try {
                r = Updater.latest(a);
            } catch (Updater.NotFound e) {
                error = e.withToken
                        ? "GitHub shows no releases at github.com/" + Updater.REPO + " for this token. Check the repo has a "
                        + "release, and that the token has read access to its Contents."
                        : "No releases are visible at github.com/" + Updater.REPO + ". If the repository is private, add a "
                        + "GitHub token from your phone (Set up from your phone → Install an update), or send the APK from there.";
            } catch (Exception e) {
                error = "Couldn't reach GitHub (" + e.getMessage() + "). Check the internet connection and try again.";
            }
            final Updater.Release rel = r;
            final String err = error;
            UI.post(() -> {
                if (gone(a) || !checking.isShowing()) return; // cancelled
                checking.dismiss();
                String mine = Updater.currentVersion(a);
                if (err != null) {
                    AppLog.w("Update", "Check failed: " + err);
                    new AlertDialog.Builder(a).setTitle("Couldn't check for updates").setMessage(err)
                            .setPositiveButton("OK", null).show();
                } else if (!Updater.newer(rel.version, mine)) {
                    new AlertDialog.Builder(a).setTitle("You're up to date")
                            .setMessage("Live TV Home " + mine + " is the newest version.").setPositiveButton("OK", null).show();
                } else if (rel.apkUrl.isEmpty()) {
                    new AlertDialog.Builder(a).setTitle("Version " + rel.version + " is out")
                            .setMessage("But its GitHub release has no APK attached yet. Try again later.")
                            .setPositiveButton("OK", null).show();
                } else {
                    offer(a, rel);
                }
            });
        });
    }

    // ---- When the app opens: updates itself, without asking ----

    /**
     * Looks for a newer release in the background (at most every {@link #AUTO_EVERY_MS}) and, if
     * there is one, downloads and installs it straight away. Says nothing when up to date, offline, or
     * in a profile that can't install apps. (On Android 11, e.g. the Shield, Android still shows its
     * own install confirmation; newer Android installs it silently.)
     */
    static void checkQuietly(Activity a) {
        if (pending != null) {
            if (gone(a) || !a.hasWindowFocus()) return;
            Updater.Release r = pending;
            pending = null;
            autoInstall(a, r);
            return;
        }
        long now = System.currentTimeMillis();
        if (autoRunning || now - lastAutoCheck < AUTO_EVERY_MS) return;
        if (Updater.blockedReason(a) != null) return; // e.g. a restricted profile: updates come from the main one
        lastAutoCheck = now;
        autoRunning = true;
        android.content.Context app = a.getApplicationContext();
        NET.execute(() -> {
            Updater.Release r = null;
            try {
                r = Updater.latest(app);
            } catch (Exception e) {
                AppLog.i("Update", "Automatic check skipped (" + e.getClass().getSimpleName() + ")");
            }
            final Updater.Release rel = r;
            UI.post(() -> {
                autoRunning = false;
                if (rel == null || rel.apkUrl.isEmpty() || !Updater.newer(rel.version, Updater.currentVersion(app))) return;
                AppLog.i("Update", "Version " + rel.version + " is available · installing it");
                if (gone(a) || !a.hasWindowFocus()) pending = rel; // e.g. playing a channel: install on the home screen
                else autoInstall(a, rel);
            });
        });
    }

    // ---- Shared ----

    private static void autoInstall(Activity a, Updater.Release rel) {
        if (gone(a)) { pending = rel; return; }
        install(a, rel);
    }

    private static void offer(Activity a, Updater.Release rel) {
        if (gone(a)) { pending = rel; return; }
        String mine = Updater.currentVersion(a);
        // Release notes are GitHub markdown: drop the heading marks, bold stars and code ticks for the TV.
        String notes = rel.notes.replaceAll("(?m)^#+\\s*", "").replace("**", "").replace("`", "").trim();
        if (notes.length() > 1500) notes = notes.substring(0, 1500) + "…";
        new AlertDialog.Builder(a)
                .setTitle("Version " + rel.version + " is available")
                .setMessage("You have " + mine + "." + (notes.isEmpty() ? "" : "\n\n" + notes)
                        + "\n\nDownload: " + (rel.apkSize > 0 ? (rel.apkSize / (1024 * 1024)) + " MB" : rel.apkName))
                .setPositiveButton("Install", (d, w) -> install(a, rel))
                .setNegativeButton("Later", null)
                .show();
    }

    /** Android must allow this app to install apps once; explain and open that screen if it doesn't yet. */
    private static boolean ensureInstallAllowed(Activity a) {
        if (Updater.installAllowed(a)) return true;
        new AlertDialog.Builder(a)
                .setTitle("Allow updates")
                .setMessage("Android needs your OK, once, for Live TV Home to install its own updates.\n\n"
                        + "On the next screen turn on Live TV Home (on the Shield: Device Preferences → Security & restrictions → "
                        + "Unknown sources). Then come back and choose Settings → System → Check for updates.")
                .setPositiveButton("Open settings", (d, w) -> Updater.openInstallPermission(a))
                .setNegativeButton("Cancel", null)
                .show();
        return false;
    }

    static void install(Activity a, Updater.Release rel) {
        if (!ensureInstallAllowed(a)) return;
        AtomicBoolean cancelled = new AtomicBoolean();
        TextView msg = Ui.text(a, 18, Ui.TEXT, false);
        msg.setSingleLine(false);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
        msg.setText("Starting…");
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Downloading version " + rel.version)
                .setView(msg)
                .setNegativeButton("Cancel", (d, w) -> cancelled.set(true))
                .setOnCancelListener(d -> cancelled.set(true))
                .show();
        AppLog.i("Update", "Downloading " + rel.version + " (" + rel.apkName + ")");
        android.content.Context app = a.getApplicationContext();
        NET.execute(() -> {
            try {
                java.io.File apk = Updater.download(app, rel, (done, total) -> UI.post(() -> msg.setText(total > 0
                        ? (done * 100 / total) + "%  ·  " + (done >> 20) + " of " + (total >> 20) + " MB"
                        : (done >> 20) + " MB")), cancelled);
                if (apk == null) return; // cancelled
                String problem = Updater.check(app, apk);
                if (problem != null) {
                    apk.delete();
                    throw new Exception(problem);
                }
                UI.post(() -> msg.setText("Installing… Confirm on the next screen. The launcher restarts on the new version."));
                Updater.install(app, apk);
                UI.postDelayed(() -> { if (dlg.isShowing()) dlg.dismiss(); }, 4000);
            } catch (Exception e) {
                AppLog.w("Update", "Install failed: " + e.getMessage());
                UI.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    if (gone(a)) return;
                    new AlertDialog.Builder(a).setTitle("Update didn't install").setMessage(e.getMessage())
                            .setPositiveButton("OK", null).show();
                });
            }
        });
    }
}
