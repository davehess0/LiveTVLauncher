package com.dave.livetvlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cached Plex rows (Continue Watching, Recently Added Movies) + how to open an item. */
public final class PlexStore {
    private static final PlexStore INSTANCE = new PlexStore();
    public static PlexStore get() { return INSTANCE; }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ChannelStore.Listener> listeners = new ArrayList<>();
    private volatile PlexClient.Library lib;
    private volatile String status = "";
    private volatile long loadedAt;
    private volatile boolean loading;

    public PlexClient.Library library() { return lib; }
    public String status() { return status; }

    public void addListener(ChannelStore.Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(ChannelStore.Listener l) { listeners.remove(l); }

    public void refreshIfStale(Context ctx, long maxAgeMs) {
        if (System.currentTimeMillis() - loadedAt > maxAgeMs) refresh(ctx);
    }

    public void refresh(Context ctx) {
        Prefs p = new Prefs(ctx);
        if (p.plexUrl().isEmpty() || p.plexToken().isEmpty()) { lib = null; status = "Plex not connected"; return; }
        if (loading) return;
        loading = true;
        final String base = p.plexUrl(), token = p.plexToken(), cid = p.clientId();
        io.execute(() -> {
            try {
                lib = new PlexClient(cid).load(base, token);
                status = lib.serverName;
                loadedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.e("Plex", "Library refresh failed", e);
                status = "Can't reach Plex server";
                loadedAt = System.currentTimeMillis() - 4 * 60_000L;
            } finally {
                loading = false;
                main.post(() -> { for (ChannelStore.Listener l : new ArrayList<>(listeners)) l.onChannelsChanged(); });
            }
        });
    }

    /** Open a movie/episode: in the Plex app (deep link), or in the built-in player. */
    public static void open(Activity a, PlexClient.Item it) {
        Prefs p = new Prefs(a);
        if (Prefs.PLEX_BUILTIN.equals(p.plexPlayer()) && it.partKey != null) {
            AppLog.i("Plex", "Play in built-in player: " + it.title);
            a.startActivity(PlayerActivity.plexIntent(a, it, p.plexUrl(), p.plexToken()));
            return;
        }
        AppLog.i("Plex", "Open in Plex app: " + it.title);
        openInPlexApp(a, it.ratingKey, it.viewOffsetMs);
    }

    /**
     * Open an item in the Plex app and get it playing.
     *
     * 1. Deep link with autoPlay (and the older START_PLAYBACK extra, for versions that still read it).
     *    Recent Plex releases (2026.18+) often open but ignore the "play" part.
     * 2. So a few seconds later, also ask the Plex app to play it through Plex's own remote-control
     *    ("Companion") API on this device, the way Plex remote apps do. That needs the Plex app's
     *    "Advertise as player" / remote control setting on; if it isn't, the app is simply left open.
     */
    public static void openInPlexApp(Activity a, String ratingKey, long viewOffsetMs) {
        PlexClient.Library lib = INSTANCE.lib;
        if (lib != null && !lib.machineId.isEmpty() && ratingKey != null) {
            String uri = "plex://server://" + lib.machineId + "/com.plexapp.plugins.library/library/metadata/"
                    + ratingKey + "?viewOffset=" + viewOffsetMs + "&autoPlay=1";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(Apps.PLEX)
                    .putExtra("android.intent.extra.START_PLAYBACK", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(i);
                companionPlay(a.getApplicationContext(), lib.machineId, ratingKey, viewOffsetMs);
                return;
            } catch (ActivityNotFoundException ignored) {
                // fall through
            }
        }
        Toast.makeText(a, "Opening Plex — this Plex version doesn't accept direct links. "
                + "Settings → Open Plex movies in → Built-in player avoids this.", Toast.LENGTH_LONG).show();
        Apps.launch(a, Apps.PLEX);
    }

    /**
     * Plex Companion: once the Plex app is up, send it "playMedia" on its local control port (32500).
     * Tries for about 12 seconds (the app needs a moment to start), then gives up quietly.
     */
    private static void companionPlay(Context ctx, String serverId, String ratingKey, long offsetMs) {
        Prefs p = new Prefs(ctx);
        String server = p.plexUrl(), token = p.plexToken();
        if (server.isEmpty() || token.isEmpty()) return;
        Uri su = Uri.parse(server);
        String clientId = p.clientId();
        new Thread(() -> {
            long until = System.currentTimeMillis() + 12_000;
            int cmd = 1;
            String lastProblem = "no answer";
            try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
            while (System.currentTimeMillis() < until) {
                try {
                    // Who is the player? (its identifier is needed as the command's target)
                    String res = HdhrClient.get("http://127.0.0.1:32500/resources");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("machineIdentifier=\"([^\"]+)\"").matcher(res);
                    String target = m.find() ? m.group(1) : "";
                    String url = "http://127.0.0.1:32500/player/playback/playMedia"
                            + "?key=" + Uri.encode("/library/metadata/" + ratingKey)
                            + "&offset=" + Math.max(0, offsetMs)
                            + "&machineIdentifier=" + Uri.encode(serverId)
                            + "&address=" + Uri.encode(su.getHost())
                            + "&port=" + (su.getPort() > 0 ? su.getPort() : 32400)
                            + "&protocol=" + (su.getScheme() != null ? su.getScheme() : "http")
                            + "&token=" + Uri.encode(token)
                            + "&commandID=" + (cmd++);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("X-Plex-Client-Identifier", clientId);
                    if (!target.isEmpty()) c.setRequestProperty("X-Plex-Target-Client-Identifier", target);
                    c.setRequestProperty("X-Plex-Token", token);
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code == 200) {
                        AppLog.i("Plex", "Asked the Plex app to play item " + ratingKey + " (remote control)");
                        return;
                    }
                    lastProblem = "HTTP " + code;
                } catch (Exception e) {
                    lastProblem = e.getClass().getSimpleName();
                }
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            }
            AppLog.w("Plex", "Plex app didn't accept a remote play request (" + lastProblem + "). "
                    + "Turn on remote control in the Plex app (Settings → Advanced → Advertise as player), "
                    + "or use the built-in player.");
        }, "plex-companion").start();
    }
}
