package com.dave.livetvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide cache of the channel lineup + guide. Loads off the main thread. */
public final class ChannelStore {

    public interface Listener { void onChannelsChanged(); }

    private static final ChannelStore INSTANCE = new ChannelStore();
    public static ChannelStore get() { return INSTANCE; }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private volatile List<Channel> all = Collections.emptyList();
    private volatile String status = "Looking for your HDHomeRun…";
    private volatile long loadedAt;
    private volatile boolean loading;
    /** When something failed: the time of the next quick retry (0 = nothing to retry). */
    private volatile long retryAt;
    private volatile int failures;
    /** Retry delays after a failure; the last one repeats until the source is back. */
    private static final long[] RETRY_MS = {30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L};
    /** Last channels that loaded from each source, kept through short outages. */
    private volatile List<Channel> lastHdhr = Collections.emptyList(), lastTunarr = Collections.emptyList();

    public List<Channel> all() { return all; }
    public String status() { return status; }
    public boolean isLoaded() { return !all.isEmpty(); }

    /**
     * Favorites if the user wants them and any exist; otherwise everything.
     * Tunarr channels have no favorites (they're hand-picked already), so they always stay.
     */
    public List<Channel> visible(Prefs prefs) {
        List<Channel> src = all;
        if (!prefs.favoritesOnly()) return src;
        List<Channel> fav = new ArrayList<>();
        boolean anyFavorite = false;
        for (Channel c : src) {
            if (c.favorite) anyFavorite = true;
            if (c.favorite || c.tunarr) fav.add(c);
        }
        return anyFavorite ? fav : src;
    }

    public Channel byNumber(String number) {
        if (number == null) return null;
        for (Channel c : all) if (c.number.equals(number)) return c;
        return null;
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /**
     * Refresh if never loaded, older than maxAgeMs, or a source failed and its quick retry is due
     * (30 s, 1 min, 2 min, then every 5 min until it answers again).
     */
    public void refreshIfStale(Context ctx, long maxAgeMs) {
        long now = System.currentTimeMillis();
        if (now - loadedAt > maxAgeMs || (retryAt > 0 && now >= retryAt)) refresh(ctx);
    }

    public void refresh(Context ctx) {
        if (loading) return;
        loading = true;
        final Prefs prefs = new Prefs(ctx);
        final String ip = prefs.tunerIp();
        final String tunarrUrl = prefs.tunarrUrl();
        io.execute(() -> {
            try {
                // Antenna tuner and Tunarr load independently, so either one still works if the other is down.
                List<Channel> merged = new ArrayList<>();
                String hdhrStatus;
                Exception hdhrError = null;
                try {
                    HdhrClient.Result r;
                    try {
                        r = HdhrClient.load(ip);
                    } catch (Exception manualFailed) {
                        // The saved address may be stale (the router gave the tuner a new one): look for it automatically.
                        if (ip.isEmpty()) throw manualFailed;
                        AppLog.w("Channels", "Saved HDHomeRun address " + ip + " didn't answer ("
                                + manualFailed.getClass().getSimpleName() + "); searching the network");
                        r = HdhrClient.load("");
                    }
                    lastHdhr = r.channels;
                    merged.addAll(r.channels);
                    hdhrStatus = r.tunerSummary;
                } catch (Exception e) {
                    hdhrError = e;
                    AppLog.e("Channels", "HDHomeRun load failed; keeping " + lastHdhr.size() + " cached channels", e);
                    merged.addAll(lastHdhr); // keep the last good lineup through an outage
                    hdhrStatus = "Can't reach HDHomeRun (" + e.getClass().getSimpleName()
                            + "). Retrying automatically, or set the tuner IP in Settings.";
                }
                String tunarrStatus = "";
                boolean tunarrFailed = false;
                if (!tunarrUrl.isEmpty()) {
                    List<Channel> tunarr;
                    try {
                        tunarr = TunarrClient.load(tunarrUrl);
                        lastTunarr = tunarr;
                    } catch (Exception e) {
                        tunarrFailed = true;
                        AppLog.e("Channels", "Tunarr load failed (" + tunarrUrl + "); keeping " + lastTunarr.size() + " cached channels", e);
                        tunarr = lastTunarr;
                    }
                    java.util.Set<String> taken = new java.util.HashSet<>();
                    for (Channel c : merged) taken.add(c.number);
                    int n = 0;
                    for (Channel c : tunarr) {
                        if (!taken.add(c.number)) continue; // same number as an antenna channel
                        merged.add(c);
                        n++;
                    }
                    tunarrStatus = tunarrFailed ? " · can't reach Tunarr (retrying)" : " · " + n + " Tunarr";
                } else {
                    lastTunarr = Collections.emptyList();
                }
                if (merged.isEmpty()) throw hdhrError != null ? hdhrError : new Exception("No channels found");
                java.util.Collections.sort(merged, java.util.Comparator.comparingDouble(Channel::sortKey));
                all = Collections.unmodifiableList(merged);
                status = hdhrStatus + tunarrStatus;
                loadedAt = System.currentTimeMillis();
                scheduleRetry(hdhrError != null || tunarrFailed);
                AppLog.i("Channels", "Loaded " + merged.size() + " channels · " + status
                        + (retryAt > 0 ? " · next retry in " + (retryAt - loadedAt) / 1000 + " s" : ""));
            } catch (Exception e) {
                AppLog.e("Channels", "No channels from any source", e);
                status = "Can't reach HDHomeRun (" + e.getClass().getSimpleName()
                        + "). Retrying automatically, or set the tuner IP in Settings.";
                loadedAt = System.currentTimeMillis();
                scheduleRetry(true);
            } finally {
                loading = false;
                main.post(() -> {
                    for (Listener l : new ArrayList<>(listeners)) l.onChannelsChanged();
                });
            }
        });
    }

    /** After a failure, retry soon and back off gradually; after a success, wait for the normal refresh. */
    private void scheduleRetry(boolean failed) {
        if (!failed) { failures = 0; retryAt = 0; return; }
        long delay = RETRY_MS[Math.min(failures, RETRY_MS.length - 1)];
        failures++;
        retryAt = System.currentTimeMillis() + delay;
    }
}
