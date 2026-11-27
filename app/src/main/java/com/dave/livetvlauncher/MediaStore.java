package com.dave.livetvlauncher;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cached Jellyfin and Emby rows, and the home screen's combined rows: Continue Watching and
 * Recently Added Movies from Plex, Jellyfin and Emby together.
 */
public final class MediaStore {
    private static final MediaStore INSTANCE = new MediaStore();
    public static MediaStore get() { return INSTANCE; }

    public static final String[] KINDS = {JellyfinClient.JELLYFIN, JellyfinClient.EMBY};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ChannelStore.Listener> listeners = new ArrayList<>();
    private final Map<String, PlexClient.Library> libs = new HashMap<>();
    private final Map<String, String> status = new HashMap<>();
    private volatile long loadedAt;
    private volatile boolean loading;

    public void addListener(ChannelStore.Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(ChannelStore.Listener l) { listeners.remove(l); }

    /** "Stable Demo", "Can't reach Jellyfin server", "" when not set up. */
    public synchronized String status(String kind) { String s = status.get(kind); return s == null ? "" : s; }
    /** Signed in and the last load worked. */
    public synchronized boolean working(String kind) { return libs.get(kind) != null; }

    public void refreshIfStale(Context ctx, long maxAgeMs) {
        if (System.currentTimeMillis() - loadedAt > maxAgeMs) refresh(ctx);
    }

    public void refresh(Context ctx) {
        Prefs p = new Prefs(ctx);
        if (loading) return;
        loading = true;
        final String deviceId = p.clientId();
        final List<String[]> todo = new ArrayList<>(); // {kind, url, token, userId}
        for (String k : KINDS) {
            if (p.mediaConnected(k)) todo.add(new String[]{k, p.mediaUrl(k), p.mediaToken(k), p.mediaUserId(k)});
            else synchronized (this) { libs.remove(k); status.remove(k); }
        }
        io.execute(() -> {
            for (String[] t : todo) {
                JellyfinClient jc = new JellyfinClient(t[0], t[1], deviceId).withUser(t[2], t[3]);
                try {
                    PlexClient.Library lib = jc.load();
                    synchronized (this) { libs.put(t[0], lib); status.put(t[0], lib.serverName); }
                } catch (Exception e) {
                    AppLog.e(jc.name(), "Library refresh failed", e);
                    synchronized (this) { libs.remove(t[0]); status.put(t[0], "Can't reach " + jc.name() + " server"); }
                }
            }
            loadedAt = System.currentTimeMillis();
            loading = false;
            main.post(() -> { for (ChannelStore.Listener l : new ArrayList<>(listeners)) l.onChannelsChanged(); });
        });
    }

    /** Plex, Jellyfin and Emby rows together for the home screen (null when none is set up). */
    public PlexClient.Library combined() {
        List<PlexClient.Library> all = new ArrayList<>();
        PlexClient.Library plex = PlexStore.get().library();
        if (plex != null) all.add(plex);
        synchronized (this) {
            for (String k : KINDS) if (libs.get(k) != null) all.add(libs.get(k));
        }
        if (all.isEmpty()) return null;
        if (all.size() == 1) return all.get(0);
        PlexClient.Library out = new PlexClient.Library();
        if (plex != null) { out.machineId = plex.machineId; out.serverName = plex.serverName; }
        for (PlexClient.Library l : all) {
            out.continueWatching.addAll(l.continueWatching);
            out.recentMovies.addAll(l.recentMovies);
        }
        return out;
    }

    /** Play a Jellyfin / Emby item in the built-in player. */
    public static void open(Activity a, PlexClient.Item it, boolean fromStart) {
        Prefs p = new Prefs(a);
        AppLog.i(sourceName(it.source), "Play in built-in player: " + it.title + (fromStart ? " (from the beginning)" : ""));
        android.content.Intent i = PlayerActivity.mediaIntent(a, it, p.mediaUrl(it.source), p.mediaToken(it.source), p.mediaUserId(it.source));
        if (fromStart) i.putExtra("startMs", 0L);
        a.startActivity(i);
    }

    public static String sourceName(String kind) {
        return JellyfinClient.EMBY.equals(kind) ? "Emby" : JellyfinClient.JELLYFIN.equals(kind) ? "Jellyfin" : "Plex";
    }
}
