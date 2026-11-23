package com.dave.livetvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** All launcher settings. Stored per Android user, so the restricted profile has its own copy. */
public final class Prefs {
    public static final String LAYOUT_GUIDE = "guide";
    public static final String LAYOUT_WALL = "wall";
    public static final String LAYOUT_SIMPLE = "simple";

    public static final String PLAYER_BUILTIN = "builtin";
    public static final String PLAYER_EXTERNAL = "external";
    public static final String PLAYER_HDHR_APP = "hdhr";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    public String layout() { return sp.getString("layout", LAYOUT_GUIDE); }
    public void setLayout(String v) { sp.edit().putString("layout", v).apply(); }

    public String player() { return sp.getString("player", PLAYER_BUILTIN); }
    public void setPlayer(String v) { sp.edit().putString("player", v).apply(); }

    public boolean favoritesOnly() { return sp.getBoolean("favoritesOnly", false); }
    public void setFavoritesOnly(boolean v) { sp.edit().putBoolean("favoritesOnly", v).apply(); }

    public String tunerIp() { return sp.getString("tunerIp", ""); }
    public void setTunerIp(String v) { sp.edit().putString("tunerIp", v == null ? "" : v.trim()).apply(); }

    /** Muted live video of the selected channel in the Guide's preview panel (uses a tuner while shown). */
    public boolean livePreview() { return sp.getBoolean("livePreview", true); }
    public void setLivePreview(boolean v) { sp.edit().putBoolean("livePreview", v).apply(); }

    /** When the app starts (or the device boots), go straight to the last channel watched. */
    public boolean startOnLastChannel() { return sp.getBoolean("startOnLastChannel", true); }
    public void setStartOnLastChannel(boolean v) { sp.edit().putBoolean("startOnLastChannel", v).apply(); }

    /** Security cameras: [{"name":"Driveway","url":"rtsp://..."}] (stored only on this device). */
    public java.util.List<String[]> cameras() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp.getString("cameras", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("name", "Camera " + (i + 1)), o.optString("url", "")});
            }
        } catch (Exception ignored) { }
        return out;
    }
    public void setCameras(java.util.List<String[]> cams) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (String[] c : cams) a.put(new org.json.JSONObject().put("name", c[0]).put("url", c[1]));
        } catch (Exception ignored) { }
        sp.edit().putString("cameras", a.toString()).apply();
    }

    /** Weather location (from Settings); blank latitude = weather off. */
    public String weatherPlace() { return sp.getString("weatherPlace", ""); }
    public String weatherLat() { return sp.getString("weatherLat", ""); }
    public String weatherLon() { return sp.getString("weatherLon", ""); }
    public boolean weatherFahrenheit() { return sp.getBoolean("weatherF", true); }
    public void setWeather(String place, String lat, String lon, boolean fahrenheit) {
        sp.edit().putString("weatherPlace", place == null ? "" : place)
                .putString("weatherLat", lat == null ? "" : lat)
                .putString("weatherLon", lon == null ? "" : lon)
                .putBoolean("weatherF", fahrenheit).apply();
    }

    /** Tunarr server, e.g. http://192.168.1.50:8000; blank = not used. */
    public String tunarrUrl() { return sp.getString("tunarrUrl", ""); }
    public void setTunarrUrl(String v) { sp.edit().putString("tunarrUrl", v == null ? "" : v.trim()).apply(); }

    public String lastChannel() { return sp.getString("lastChannel", null); }
    public void setLastChannel(String v) { sp.edit().putString("lastChannel", v).apply(); }

    public boolean showAppRow() { return sp.getBoolean("showAppRow", true); }
    public void setShowAppRow(boolean v) { sp.edit().putBoolean("showAppRow", v).apply(); }

    public boolean clock24h() { return sp.getBoolean("clock24h", false); }
    public void setClock24h(boolean v) { sp.edit().putBoolean("clock24h", v).apply(); }

    // ---- Plex ----
    public static final String PLEX_APP = "app";
    public static final String PLEX_BUILTIN = "builtin";

    public String plexUrl() { return sp.getString("plexUrl", ""); }
    public void setPlexUrl(String v) { sp.edit().putString("plexUrl", v == null ? "" : v.trim()).apply(); }
    public String plexToken() { return sp.getString("plexToken", ""); }
    public void setPlexToken(String v) { sp.edit().putString("plexToken", v == null ? "" : v.trim()).apply(); }
    // Built-in by default: the Plex app ignores "play this" links and just opens to its home screen.
    public String plexPlayer() { return sp.getString("plexPlayer", PLEX_BUILTIN); }
    public void setPlexPlayer(String v) { sp.edit().putString("plexPlayer", v).apply(); }

    /** Stable per-install ID Plex uses to recognise this device. */
    public String clientId() {
        String id = sp.getString("clientId", null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            sp.edit().putString("clientId", id).apply();
        }
        return id;
    }

    // ---- Streaming row ----
    /** Apps shown in the Streaming row, in order. Defaults to YouTube, Prime Video, Disney+, Plex. */
    public static final String[] DEFAULT_PINS = {
            "com.google.android.youtube.tv",
            "com.teamsmart.videomanager.tv",      // SmartTube, if you use it
            "com.amazon.amazonvideo.livingroom",  // Prime Video (Android TV)
            "com.disney.disneyplus",
            "com.plexapp.android",
    };

    public java.util.List<String> pinnedApps() {
        String s = sp.getString("pins", null);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) { java.util.Collections.addAll(out, DEFAULT_PINS); return out; }
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    public void setPinnedApps(java.util.List<String> pkgs) {
        sp.edit().putString("pins", android.text.TextUtils.join(",", pkgs)).apply();
    }

    /** Pin or unpin; returns true if now pinned. */
    public boolean togglePin(String pkg) {
        java.util.List<String> l = pinnedApps();
        boolean pinned;
        if (l.remove(pkg)) pinned = false; else { l.add(pkg); pinned = true; }
        setPinnedApps(l);
        return pinned;
    }

    /** Optional PIN that guards the launcher's own Settings screen. Empty = no PIN. */
    public String pin() { return sp.getString("pin", ""); }
    public void setPin(String v) { sp.edit().putString("pin", v == null ? "" : v).apply(); }
}
