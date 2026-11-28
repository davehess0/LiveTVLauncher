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
 * Jellyfin and Emby media servers (Jellyfin began as a copy of Emby, so they share one API; Emby
 * puts it under /emby and reads its own header names).
 *
 *  - Sign-in: username + password, or on Jellyfin, Quick Connect (a code shown on the TV that you
 *    approve in the Jellyfin app on your phone).
 *  - Rows: Continue Watching (resume + next up) and Recently Added Movies, as PlexClient.Items.
 *  - Playback: direct stream of the file, with progress reported so resume points stay in sync.
 */
public final class JellyfinClient {
    public static final String JELLYFIN = "jellyfin", EMBY = "emby";

    public final String kind;
    private final String base;     // e.g. http://192.168.1.20:8096 (no trailing slash, no /emby)
    private final String deviceId;
    private String token = "";
    private String userId = "";

    public JellyfinClient(String kind, String base, String deviceId) {
        this.kind = kind;
        this.base = PlexClient.strip(base);
        this.deviceId = deviceId;
    }

    public JellyfinClient withUser(String token, String userId) {
        this.token = token == null ? "" : token;
        this.userId = userId == null ? "" : userId;
        return this;
    }

    public String name() { return EMBY.equals(kind) ? "Emby" : "Jellyfin"; }

    /** API root: Emby serves its API under /emby. */
    private String api() { return EMBY.equals(kind) ? base + "/emby" : base; }

    // ---- server and sign-in ------------------------------------------------------------------

    /** {serverName, productName, version}; checks the address answers (no sign-in needed). */
    public String[] info() throws Exception {
        JSONObject o = new JSONObject(request("GET", api() + "/System/Info/Public", null));
        return new String[]{o.optString("ServerName", name()), o.optString("ProductName", ""), o.optString("Version", "")};
    }

    /** Signs in; returns {token, userId, userName}. */
    public String[] signIn(String user, String password) throws Exception {
        JSONObject body = new JSONObject().put("Username", user).put("Pw", password == null ? "" : password);
        return session(new JSONObject(request("POST", api() + "/Users/AuthenticateByName", body.toString())));
    }

    public static final class QuickCode { public String secret, code; }

    /** Jellyfin Quick Connect: is it turned on for this server? */
    public boolean quickConnectEnabled() {
        if (!JELLYFIN.equals(kind)) return false;
        try { return "true".equalsIgnoreCase(request("GET", api() + "/QuickConnect/Enabled", null).trim()); }
        catch (Exception e) { return false; }
    }

    public QuickCode quickConnectStart() throws Exception {
        JSONObject o = new JSONObject(request("POST", api() + "/QuickConnect/Initiate", ""));
        QuickCode q = new QuickCode();
        q.secret = o.getString("Secret");
        q.code = o.getString("Code");
        return q;
    }

    /** Once the code is approved on another device: {token, userId, userName}; until then null. */
    public String[] quickConnectCheck(String secret) throws Exception {
        JSONObject st = new JSONObject(request("GET", api() + "/QuickConnect/Connect?Secret=" + enc(secret), null));
        if (!st.optBoolean("Authenticated", false)) return null;
        JSONObject body = new JSONObject().put("Secret", secret);
        return session(new JSONObject(request("POST", api() + "/Users/AuthenticateWithQuickConnect", body.toString())));
    }

    private String[] session(JSONObject o) throws Exception {
        JSONObject user = o.getJSONObject("User");
        token = o.getString("AccessToken");
        userId = user.getString("Id");
        return new String[]{token, userId, user.optString("Name", "")};
    }

    // ---- rows ----------------------------------------------------------------------------------

    public PlexClient.Library load() throws Exception {
        PlexClient.Library lib = new PlexClient.Library();
        try { lib.serverName = info()[0]; } catch (Exception ignored) { lib.serverName = name(); }
        String fields = "&Fields=MediaSources,ProductionYear&EnableImageTypes=Primary,Backdrop,Thumb";

        JSONArray resume = new JSONObject(request("GET", api() + "/Users/" + userId
                + "/Items/Resume?Limit=20&MediaTypes=Video" + fields, null)).optJSONArray("Items");
        for (int i = 0; resume != null && i < resume.length(); i++) lib.continueWatching.add(item(resume.getJSONObject(i)));
        // Next episodes of shows you're watching come after anything half-watched.
        try {
            JSONArray next = new JSONObject(request("GET", api() + "/Shows/NextUp?UserId=" + userId
                    + "&Limit=20" + fields, null)).optJSONArray("Items");
            for (int i = 0; next != null && i < next.length() && lib.continueWatching.size() < 30; i++) {
                PlexClient.Item it = item(next.getJSONObject(i));
                boolean dup = false;
                for (PlexClient.Item x : lib.continueWatching) if (x.ratingKey.equals(it.ratingKey)) dup = true;
                if (!dup) lib.continueWatching.add(it);
            }
        } catch (Exception ignored) {
            // Next Up is a nicety; resume still works without it.
        }

        JSONArray latest = new JSONArray(request("GET", api() + "/Users/" + userId
                + "/Items/Latest?IncludeItemTypes=Movie&Limit=20" + fields, null));
        for (int i = 0; i < latest.length(); i++) lib.recentMovies.add(item(latest.getJSONObject(i)));
        return lib;
    }

    private PlexClient.Item item(JSONObject m) throws Exception {
        PlexClient.Item it = new PlexClient.Item();
        it.source = kind;
        it.ratingKey = m.optString("Id");
        boolean episode = "Episode".equals(m.optString("Type"));
        if (episode) {
            it.title = m.optString("SeriesName", m.optString("Name"));
            it.subtitle = "S" + m.optInt("ParentIndexNumber") + " · E" + m.optInt("IndexNumber") + " · " + m.optString("Name");
        } else {
            it.title = m.optString("Name");
            it.subtitle = m.has("ProductionYear") ? String.valueOf(m.optInt("ProductionYear")) : "";
        }
        it.durationMs = m.optLong("RunTimeTicks", 0) / 10_000L;
        JSONObject ud = m.optJSONObject("UserData");
        if (ud != null) it.viewOffsetMs = ud.optLong("PlaybackPositionTicks", 0) / 10_000L;

        // Poster: the show's for an episode, else the item's own.
        String posterId = episode && m.has("SeriesId") && m.has("SeriesPrimaryImageTag") ? m.optString("SeriesId") : it.ratingKey;
        JSONObject tags = m.optJSONObject("ImageTags");
        if (posterId.equals(m.optString("SeriesId")) || (tags != null && tags.has("Primary")))
            it.posterUrl = api() + "/Items/" + posterId + "/Images/Primary?maxHeight=360&quality=90&api_key=" + enc(token);
        JSONArray backdrops = m.optJSONArray("BackdropImageTags");
        String artId = backdrops != null && backdrops.length() > 0 ? it.ratingKey
                : m.optJSONArray("ParentBackdropImageTags") != null ? m.optString("ParentBackdropItemId", "") : "";
        if (!artId.isEmpty()) it.artUrl = api() + "/Items/" + artId + "/Images/Backdrop?maxWidth=1920&quality=80&api_key=" + enc(token);

        JSONArray sources = m.optJSONArray("MediaSources");
        it.mediaSourceId = sources != null && sources.length() > 0 ? sources.getJSONObject(0).optString("Id", it.ratingKey) : it.ratingKey;
        it.partKey = streamUrl(it.ratingKey, it.mediaSourceId);
        return it;
    }

    /** The file itself (direct play: no transcoding, so HDR / Dolby Vision / Atmos stay intact). */
    public String streamUrl(String itemId, String mediaSourceId) throws Exception {
        return api() + "/Videos/" + itemId + "/stream?static=true&mediaSourceId=" + enc(mediaSourceId)
                + "&deviceId=" + enc(deviceId) + "&api_key=" + enc(token);
    }

    // ---- subtitles -------------------------------------------------------------------------------

    /** Subtitle files kept next to the video on the server (tracks inside the file need nothing extra). */
    /** The video's frame rate from the last subtitleFiles() lookup (0 = not known). */
    public volatile float lastFrameRate;
    /** Its Dolby Vision profile (0 = not Dolby Vision / not known). */
    public volatile int lastDvProfile;
    /** Its size, codec ("hevc", "h264", ...) and whether it's HDR / Dolby Vision (0 / "" = not known). */
    public volatile int lastWidth, lastHeight;
    public volatile String lastCodec = "";
    public volatile boolean lastHdr;

    /**
     * The server's converted (transcoded) version, as HLS: H.264 sized to fit maxW x maxH. H.264 is SDR, so
     * an HDR source is tone-mapped when the server has tone mapping turned on. Audio: AC3 / E-AC3 / AAC.
     */
    public String transcodeUrl(String itemId, String mediaSourceId, int maxW, int maxH, String playSession) throws Exception {
        return api() + "/Videos/" + itemId + "/master.m3u8?MediaSourceId=" + enc(mediaSourceId)
                + "&DeviceId=" + enc(deviceId) + "&PlaySessionId=" + enc(playSession)
                + "&VideoCodec=h264&AudioCodec=eac3,ac3,aac&MaxWidth=" + maxW + "&MaxHeight=" + maxH
                + "&VideoBitrate=40000000&AudioBitrate=640000&TranscodingMaxAudioChannels=6"
                + "&SegmentContainer=ts&BreakOnNonKeyFrames=true&api_key=" + enc(token);
    }

    /** Tell the server to stop converting. */
    public void stopTranscode(String playSession) {
        try {
            request("DELETE", api() + "/Videos/ActiveEncodings?DeviceId=" + enc(deviceId) + "&PlaySessionId=" + enc(playSession), null);
        } catch (Exception ignored) { }
    }

    public List<PlexClient.SubFile> subtitleFiles(String itemId) throws Exception {
        List<PlexClient.SubFile> out = new ArrayList<>();
        lastFrameRate = 0;
        lastDvProfile = 0;
        lastWidth = lastHeight = 0;
        lastCodec = "";
        lastHdr = false;
        JSONObject m = new JSONObject(request("GET", api() + "/Users/" + userId + "/Items/" + itemId, null));
        JSONArray sources = m.optJSONArray("MediaSources");
        if (sources == null || sources.length() == 0) return out;
        JSONObject ms = sources.getJSONObject(0);
        JSONArray streams = ms.optJSONArray("MediaStreams");
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject st = streams.getJSONObject(i);
            if ("Video".equals(st.optString("Type")) && lastFrameRate == 0) {
                lastFrameRate = (float) st.optDouble("RealFrameRate", st.optDouble("AverageFrameRate", 0));
                lastDvProfile = st.optInt("DvProfile", 0);
                lastWidth = st.optInt("Width", 0);
                lastHeight = st.optInt("Height", 0);
                lastCodec = st.optString("Codec", "").toLowerCase(java.util.Locale.US);
                lastHdr = !"SDR".equalsIgnoreCase(st.optString("VideoRange", "SDR"));
            }
            if (!"Subtitle".equals(st.optString("Type")) || !st.optBoolean("IsExternal", false)) continue;
            String codec = st.optString("Codec", "").toLowerCase(java.util.Locale.US);
            String ext, mime;
            if (codec.equals("srt") || codec.equals("subrip")) { ext = "srt"; mime = "application/x-subrip"; }
            else if (codec.equals("ass") || codec.equals("ssa")) { ext = codec; mime = "text/x-ssa"; }
            else if (codec.equals("vtt") || codec.equals("webvtt")) { ext = "vtt"; mime = "text/vtt"; }
            else continue; // picture-based subtitles can't be added from a separate file
            PlexClient.SubFile f = new PlexClient.SubFile();
            f.url = api() + "/Videos/" + itemId + "/" + ms.optString("Id", itemId) + "/Subtitles/" + st.optInt("Index")
                    + "/0/Stream." + ext + "?api_key=" + enc(token);
            f.mime = mime;
            f.language = st.optString("Language", "");
            f.label = st.optString("DisplayTitle", f.language);
            out.add(f);
        }
        return out;
    }

    // ---- progress reporting (built-in player) ----------------------------------------------------

    /** state: playing (first report), progress, paused, stopped. Errors are ignored. */
    public void report(String state, String itemId, String mediaSourceId, long positionMs) {
        try {
            JSONObject b = new JSONObject().put("ItemId", itemId).put("MediaSourceId", mediaSourceId)
                    .put("PositionTicks", positionMs * 10_000L).put("PlayMethod", "DirectPlay").put("CanSeek", true)
                    .put("IsPaused", "paused".equals(state));
            String path = "stopped".equals(state) ? "/Sessions/Playing/Stopped"
                    : "playing".equals(state) ? "/Sessions/Playing" : "/Sessions/Playing/Progress";
            request("POST", api() + path, b.toString());
        } catch (Exception ignored) { }
    }

    // ---- http --------------------------------------------------------------------------------------

    private String authHeader() {
        return "MediaBrowser Client=\"" + PlexClient.PRODUCT + "\", Device=\"Android TV\", DeviceId=\"" + deviceId
                + "\", Version=\"1.0\"" + (token.isEmpty() ? "" : ", Token=\"" + token + "\"");
    }

    private String request(String method, String url, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        // Jellyfin reads Authorization; Emby reads X-Emby-Authorization / X-Emby-Token.
        c.setRequestProperty("Authorization", authHeader());
        c.setRequestProperty("X-Emby-Authorization", authHeader());
        if (!token.isEmpty()) c.setRequestProperty("X-Emby-Token", token);
        try {
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(jsonBody.getBytes("UTF-8")); }
            }
            int code = c.getResponseCode();
            if (code == 401) throw new Exception(name() + ": wrong username or password, or signed out");
            if (code < 200 || code >= 300) throw new Exception(name() + " HTTP " + code);
            if (code == 204) return "";
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

    private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
}
