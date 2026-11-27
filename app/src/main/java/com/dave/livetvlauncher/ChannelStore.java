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
    private volatile List<Channel> lastHdhr = Collections.emptyList(), lastTunarr = Collections.emptyList(),
            lastIptv = Collections.emptyList();
    /** Groups in the IPTV playlist (for the group picker in Settings). */
    private volatile List<String> iptvGroups = Collections.emptyList();
    public List<String> iptvGroups() { return iptvGroups; }

    public List<Channel> all() { return all; }
    public String status() { return status; }
    public boolean isLoaded() { return !all.isEmpty(); }

    /**
     * Favorites if the user wants them and any exist; otherwise everything.
     * Only HDHomeRun favorites count here; Tunarr and IPTV channels (hand-picked already) always stay.
     */
    public List<Channel> visible(Prefs prefs) {
        List<Channel> src = all;
        if (!prefs.favoritesOnly()) return src;
        List<Channel> fav = new ArrayList<>();
        boolean anyFavorite = false;
        for (Channel c : src) {
            if (c.favorite && !c.iptv) anyFavorite = true;
            if (c.favorite || c.tunarr || c.iptv) fav.add(c);
        }
        return anyFavorite ? fav : src;
    }

    public Channel byNumber(String number) {
        if (number == null) return null;
        for (Channel c : all) if (c.number.equals(number)) return c;
        return null;
    }

    private volatile int favVersion;
    /** Goes up each time a favorite is added or removed. */
    public int favVersion() { return favVersion; }

    /**
     * Star or unstar an IPTV channel. The lists on screen don't move (so you can keep going down the
     * list starring channels); they show the new order the next time they're built. True if now a favorite.
     */
    public boolean toggleFavorite(Context ctx, Channel ch) {
        Prefs p = new Prefs(ctx);
        java.util.Set<String> favs = p.iptvFavorites();
        boolean on = !favs.contains(ch.favKey());
        if (on) favs.add(ch.favKey()); else favs.remove(ch.favKey());
        p.setIptvFavorites(favs);
        for (Channel c : all) if (c.iptv && c.favKey().equals(ch.favKey())) c.favorite = on;
        ch.favorite = on;
        List<Channel> sorted = new ArrayList<>(all);
        java.util.Collections.sort(sorted, java.util.Comparator.comparingDouble(Channel::sortKey));
        all = Collections.unmodifiableList(sorted);
        favVersion++;
        AppLog.i("Channels", "IPTV " + ch.number + " " + (on ? "added to" : "removed from") + " favorites");
        return on;
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
        final String iptvUrl = prefs.iptvUrl(), iptvGuide = prefs.iptvGuideUrl();
        final java.util.Set<String> hiddenGroups = prefs.iptvHiddenGroups();
        final java.util.Set<String> iptvFavs = prefs.iptvFavorites();
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
                        List<Channel> moved = findMovedTunarr(prefs, ip, tunarrUrl);
                        if (moved != null) {
                            tunarr = moved;
                            lastTunarr = moved;
                        } else {
                            tunarrFailed = true;
                            AppLog.e("Channels", "Tunarr load failed (" + tunarrUrl + "); keeping " + lastTunarr.size() + " cached channels", e);
                            tunarr = lastTunarr;
                        }
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
                String iptvStatus = "";
                boolean iptvFailed = false;
                if (!iptvUrl.isEmpty()) {
                    java.util.Set<String> taken = new java.util.HashSet<>();
                    for (Channel c : merged) taken.add(c.number);
                    try {
                        IptvClient.Result r = IptvClient.load(iptvUrl, iptvGuide, hiddenGroups, taken);
                        lastIptv = r.channels;
                        iptvGroups = Collections.unmodifiableList(new ArrayList<>(r.groups));
                        merged.addAll(r.channels);
                        iptvStatus = " · " + r.channels.size() + " IPTV" + (r.guideFailed ? " (guide failed)" : "");
                        if (r.droppedOverLimit > 0) AppLog.w("Channels", "IPTV: kept the first " + IptvClient.MAX_CHANNELS
                                + " channels, left out " + r.droppedOverLimit + " (hide groups in Settings to choose)");
                        AppLog.i("Channels", "IPTV: " + r.channels.size() + " channels, " + r.groups.size() + " groups, "
                                + r.hiddenByGroup + " hidden by group · guide " + (r.guideUrl.isEmpty() ? "none" : AppLog.mask(r.guideUrl)));
                    } catch (Exception e) {
                        iptvFailed = true;
                        AppLog.e("Channels", "IPTV playlist failed (" + AppLog.mask(iptvUrl) + "); keeping " + lastIptv.size() + " cached channels", e);
                        for (Channel c : lastIptv) if (taken.add(c.number)) merged.add(c);
                        iptvStatus = " · can't load IPTV playlist (retrying)";
                    }
                } else {
                    lastIptv = Collections.emptyList();
                    iptvGroups = Collections.emptyList();
                }
                if (merged.isEmpty()) throw hdhrError != null ? hdhrError : new Exception("No channels found");
                for (Channel c : merged) if (c.iptv) c.favorite = iptvFavs.contains(c.favKey());
                java.util.Collections.sort(merged, java.util.Comparator.comparingDouble(Channel::sortKey));
                all = Collections.unmodifiableList(merged);
                status = hdhrStatus + tunarrStatus + iptvStatus;
                loadedAt = System.currentTimeMillis();
                scheduleRetry(hdhrError != null || tunarrFailed || iptvFailed);
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

    private volatile long lastTunarrSearch;

    /**
     * The saved Tunarr address stopped answering: the server may have a new address from the router.
     * Search (at most every 10 minutes) and, if exactly one Tunarr is found elsewhere, switch to it.
     */
    private List<Channel> findMovedTunarr(Prefs prefs, String tunerIp, String savedUrl) {
        long now = System.currentTimeMillis();
        if (now - lastTunarrSearch < 10 * 60_000L) return null;
        lastTunarrSearch = now;
        try {
            List<TunarrClient.Found> found = TunarrClient.find(tunerIp, null);
            String saved = TunarrClient.normalize(savedUrl);
            if (found.size() != 1 || found.get(0).base.equals(saved)) return null;
            List<Channel> chs = TunarrClient.load(found.get(0).base);
            prefs.setTunarrUrl(found.get(0).base);
            AppLog.w("Channels", "Tunarr moved from " + saved + " to " + found.get(0).base + "; switched to it");
            return chs;
        } catch (Exception e) {
            return null;
        }
    }

    /** After a failure, retry soon and back off gradually; after a success, wait for the normal refresh. */
    private void scheduleRetry(boolean failed) {
        if (!failed) { failures = 0; retryAt = 0; return; }
        long delay = RETRY_MS[Math.min(failures, RETRY_MS.length - 1)];
        failures++;
        retryAt = System.currentTimeMillis() + delay;
    }
}
