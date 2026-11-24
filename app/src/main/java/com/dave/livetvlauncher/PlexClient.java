package com.dave.livetvlauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Plex Media Server + plex.tv calls.
 *
 *  - Sign-in: plex.tv "link" PIN (you type a 4-letter code at plex.tv/link on your phone),
 *    so you never type a token with the TV remote.
 *  - Server: picked from your plex.tv resources, using its LAN address.
 *  - Rows: Continue Watching (/library/onDeck) and Recently Added Movies.
 */
public final class PlexClient {
    public static final String PRODUCT = "Live TV Home";

    public static final class Item {
        public String ratingKey = "";
        public String title = "";
        public String subtitle = "";   // year, or "S2 · E5 · Show name"
        public String posterUrl;       // already sized + tokenized
        public String artUrl;          // wide background art, for the home-screen backdrop
        public String partKey;         // /library/parts/123/456/file.mkv — direct play
        public long viewOffsetMs;
        public long durationMs;

        public float progress() {
            return durationMs > 0 ? Math.min(1f, viewOffsetMs / (float) durationMs) : 0f;
        }
    }

    public static final class Library {
        public final List<Item> continueWatching = new ArrayList<>();
        public final List<Item> recentMovies = new ArrayList<>();
        public String machineId = "";
        public String serverName = "";
    }

    public static final class Pin { public long id; public String code; }

    private final String clientId;

    public PlexClient(String clientId) { this.clientId = clientId; }

    // ---- sign-in ---------------------------------------------------------------------------

    public Pin createPin() throws Exception {
        JSONObject o = new JSONObject(request("POST", "https://plex.tv/api/v2/pins?strong=false", null));
        Pin p = new Pin();
        p.id = o.getLong("id");
        p.code = o.getString("code");
        return p;
    }

    /** Returns the auth token once the code has been entered at plex.tv/link, else null. */
    public String checkPin(long id) throws Exception {
        JSONObject o = new JSONObject(request("GET", "https://plex.tv/api/v2/pins/" + id, null));
        String t = o.optString("authToken", "");
        return (t.isEmpty() || "null".equals(t)) ? null : t;
    }

    /** Finds your own server and returns {baseUrl, serverToken}, preferring its LAN address. */
    public String[] findServer(String userToken) throws Exception {
        JSONArray res = new JSONArray(request("GET",
                "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=0", userToken));
        String[] fallback = null;
        for (int i = 0; i < res.length(); i++) {
            JSONObject r = res.getJSONObject(i);
            if (!r.optString("provides", "").contains("server")) continue;
            String token = r.optString("accessToken", userToken);
            JSONArray conns = r.optJSONArray("connections");
            if (conns == null) continue;
            for (int j = 0; j < conns.length(); j++) {
                JSONObject c = conns.getJSONObject(j);
                String url = "http://" + c.optString("address") + ":" + c.optInt("port", 32400);
                if (c.optBoolean("local", false) && r.optBoolean("owned", false)) return new String[]{url, token};
                if (fallback == null && c.optBoolean("local", false)) fallback = new String[]{url, token};
            }
        }
        if (fallback != null) return fallback;
        throw new Exception("No Plex server found on your home network");
    }

    // ---- library ---------------------------------------------------------------------------

    public Library load(String base, String token) throws Exception {
        base = strip(base);
        Library lib = new Library();
        JSONObject id = mc(request("GET", base + "/identity", token));
        lib.machineId = id.optString("machineIdentifier", "");

        JSONObject root = mc(request("GET", base + "/", token));
        lib.serverName = root.optString("friendlyName", "Plex");

        JSONArray deck = mc(request("GET", base + "/library/onDeck?X-Plex-Container-Size=20", token))
                .optJSONArray("Metadata");
        if (deck != null) for (int i = 0; i < deck.length(); i++) {
            lib.continueWatching.add(item(deck.getJSONObject(i), base, token));
        }

        JSONArray sections = mc(request("GET", base + "/library/sections", token)).optJSONArray("Directory");
        if (sections != null) for (int s = 0; s < sections.length(); s++) {
            JSONObject sec = sections.getJSONObject(s);
            if (!"movie".equals(sec.optString("type"))) continue;
            JSONArray rec = mc(request("GET", base + "/library/sections/" + sec.optString("key")
                    + "/recentlyAdded?X-Plex-Container-Size=20", token)).optJSONArray("Metadata");
            if (rec == null) continue;
            for (int i = 0; i < rec.length() && lib.recentMovies.size() < 30; i++) {
                lib.recentMovies.add(item(rec.getJSONObject(i), base, token));
            }
        }
        return lib;
    }

    private Item item(JSONObject m, String base, String token) throws Exception {
        Item it = new Item();
        it.ratingKey = m.optString("ratingKey");
        boolean episode = "episode".equals(m.optString("type"));
        if (episode) {
            it.title = m.optString("grandparentTitle", m.optString("title"));
            it.subtitle = "S" + m.optInt("parentIndex") + " · E" + m.optInt("index") + " · " + m.optString("title");
        } else {
            it.title = m.optString("title");
            it.subtitle = m.has("year") ? String.valueOf(m.optInt("year")) : "";
        }
        it.viewOffsetMs = m.optLong("viewOffset", 0);
        it.durationMs = m.optLong("duration", 0);
        String thumb = episode ? m.optString("grandparentThumb", m.optString("thumb", "")) : m.optString("thumb", "");
        if (!thumb.isEmpty()) {
            it.posterUrl = base + "/photo/:/transcode?width=240&height=360&minSize=1&upscale=1&url="
                    + URLEncoder.encode(thumb, "UTF-8") + "&X-Plex-Token=" + URLEncoder.encode(token, "UTF-8");
        }
        String art = episode ? m.optString("grandparentArt", m.optString("art", "")) : m.optString("art", "");
        if (!art.isEmpty()) {
            it.artUrl = base + "/photo/:/transcode?width=1920&height=1080&minSize=1&upscale=1&url="
                    + URLEncoder.encode(art, "UTF-8") + "&X-Plex-Token=" + URLEncoder.encode(token, "UTF-8");
        }
        JSONArray media = m.optJSONArray("Media");
        if (media != null && media.length() > 0) {
            JSONArray parts = media.getJSONObject(0).optJSONArray("Part");
            if (parts != null && parts.length() > 0) it.partKey = parts.getJSONObject(0).optString("key", null);
        }
        return it;
    }

    // ---- progress reporting (built-in player) ----------------------------------------------

    /** Tells Plex where you are, so Continue Watching stays right. state: playing|paused|stopped. */
    public void timeline(String base, String token, String ratingKey, String state, long timeMs, long durationMs) {
        try {
            request("GET", strip(base) + "/:/timeline?ratingKey=" + ratingKey
                    + "&key=%2Flibrary%2Fmetadata%2F" + ratingKey
                    + "&state=" + state + "&time=" + timeMs + "&duration=" + durationMs, token);
        } catch (Exception ignored) { }
    }

    /** Marks the item watched. */
    public void scrobble(String base, String token, String ratingKey) {
        try {
            request("GET", strip(base) + "/:/scrobble?identifier=com.plexapp.plugins.library&key=" + ratingKey, token);
        } catch (Exception ignored) { }
    }

    // ---- http ------------------------------------------------------------------------------

    /** A subtitle file stored next to a movie on the Plex server (e.g. Movie.en.srt). */
    public static final class SubFile {
        public String url, mime, language, label;
    }

    /** The external subtitle files Plex knows for an item (embedded tracks play from the file itself). */
    public List<SubFile> subtitleFiles(String base, String token, String ratingKey) throws Exception {
        List<SubFile> out = new ArrayList<>();
        JSONArray md = mc(request("GET", strip(base) + "/library/metadata/" + ratingKey, token)).optJSONArray("Metadata");
        if (md == null || md.length() == 0) return out;
        JSONArray media = md.getJSONObject(0).optJSONArray("Media");
        if (media == null || media.length() == 0) return out;
        JSONArray parts = media.getJSONObject(0).optJSONArray("Part");
        if (parts == null || parts.length() == 0) return out;
        JSONArray streams = parts.getJSONObject(0).optJSONArray("Stream");
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject st = streams.getJSONObject(i);
            String key = st.optString("key", "");
            if (st.optInt("streamType") != 3 || key.isEmpty()) continue; // 3 = subtitles; a key = separate file
            String codec = st.optString("codec", "").toLowerCase(java.util.Locale.US);
            String mime = codec.equals("srt") || codec.equals("subrip") ? "application/x-subrip"
                    : codec.equals("ass") || codec.equals("ssa") ? "text/x-ssa"
                    : codec.equals("vtt") || codec.equals("webvtt") ? "text/vtt" : null;
            if (mime == null) continue; // picture-based files can't be sideloaded
            SubFile f = new SubFile();
            f.url = strip(base) + key + (key.contains("?") ? "&" : "?") + "X-Plex-Token=" + URLEncoder.encode(token, "UTF-8");
            f.mime = mime;
            f.language = st.optString("languageCode", st.optString("language", ""));
            f.label = st.optString("displayTitle", f.language);
            out.add(f);
        }
        return out;
    }

    private static JSONObject mc(String body) throws Exception {
        return new JSONObject(body).getJSONObject("MediaContainer");
    }

    private String request(String method, String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Product", PRODUCT);
        c.setRequestProperty("X-Plex-Version", "1.0");
        c.setRequestProperty("X-Plex-Client-Identifier", clientId);
        c.setRequestProperty("X-Plex-Device", "NVIDIA SHIELD");
        c.setRequestProperty("X-Plex-Platform", "Android");
        if (token != null) c.setRequestProperty("X-Plex-Token", token);
        try {
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { o.write(new byte[0]); }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("Plex HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String strip(String s) {
        s = s.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
