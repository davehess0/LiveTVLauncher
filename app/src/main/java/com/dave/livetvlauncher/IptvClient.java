package com.dave.livetvlauncher;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * IPTV channels from an M3U / M3U8 playlist (any provider, or an Xtream-style get.php link),
 * with an optional XMLTV guide (plain or .gz). Only the playlist and guide addresses are contacted.
 *
 * Numbers come from the playlist's tvg-chno when it has one and the number is free; otherwise
 * channels are numbered from 1000 up, in playlist order.
 */
final class IptvClient {
    private IptvClient() { }

    static final int FIRST_NUMBER = 1000;
    /** More than this is almost certainly a whole provider catalogue: hide groups in Settings to trim it. */
    static final int MAX_CHANNELS = 3000;

    static final class Result {
        final List<Channel> channels = new ArrayList<>();
        /** Every group-title in the playlist, in playlist order (for the group picker). */
        final Set<String> groups = new LinkedHashSet<>();
        int hiddenByGroup, droppedOverLimit;
        String guideUrl = ""; // the guide actually used (setting, or the playlist's url-tvg)
        boolean guideFailed;
    }

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    /** Guide parsed last time, reused for a while (XMLTV files can be tens of MB). */
    private static String cachedGuideKey;
    private static long cachedGuideAt;
    private static Map<String, List<Channel.Airing>> cachedGuide;
    private static final long GUIDE_MAX_AGE_MS = 3 * 3600_000L;

    static Result load(String playlistUrl, String guideSetting, Set<String> hiddenGroups, Set<String> takenNumbers) throws Exception {
        Result r = new Result();
        String headerGuide = "";
        List<String[]> entries = new ArrayList<>(); // {name, url, tvgId, tvgName, logo, group, chno}
        HttpURLConnection c = open(playlistUrl);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream(c), StandardCharsets.UTF_8))) {
            String line, extinf = null, extgrp = null;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (first) { line = line.replace("﻿", ""); first = false; }
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTM3U")) {
                    Map<String, String> a = attrs(line);
                    headerGuide = firstNonEmpty(a.get("url-tvg"), a.get("x-tvg-url"), "");
                    int comma = headerGuide.indexOf(','); // some list several guides; use the first
                    if (comma > 0) headerGuide = headerGuide.substring(0, comma).trim();
                } else if (line.startsWith("#EXTINF")) {
                    extinf = line;
                    extgrp = null;
                } else if (line.startsWith("#EXTGRP:")) {
                    extgrp = line.substring(8).trim();
                } else if (line.startsWith("#")) {
                    // #EXTVLCOPT and other options: not used
                } else if (extinf != null) {
                    Map<String, String> a = attrs(extinf);
                    String group = firstNonEmpty(a.get("group-title"), extgrp, "");
                    String title = titleOf(extinf);
                    String name = firstNonEmpty(title, a.get("tvg-name"), "Channel");
                    entries.add(new String[]{name, line, firstNonEmpty(a.get("tvg-id"), ""), firstNonEmpty(a.get("tvg-name"), ""),
                            firstNonEmpty(a.get("tvg-logo"), ""), group, firstNonEmpty(a.get("tvg-chno"), a.get("channel-number"), "")});
                    extinf = null;
                }
            }
        } finally {
            c.disconnect();
        }

        Set<String> taken = new java.util.HashSet<>(takenNumbers);
        int next = FIRST_NUMBER;
        for (String[] e : entries) {
            if (!e[5].isEmpty()) r.groups.add(e[5]);
            if (hiddenGroups.contains(e[5])) { r.hiddenByGroup++; continue; }
            if (r.channels.size() >= MAX_CHANNELS) { r.droppedOverLimit++; continue; }
            Channel ch = new Channel();
            ch.iptv = true;
            ch.name = e[0];
            ch.streamUrl = e[1];
            ch.logoUrl = e[4].isEmpty() ? null : e[4];
            ch.group = e[5];
            ch.guideId = e[2];
            ch.guideName = e[3].isEmpty() ? e[0] : e[3];
            String num = e[6].matches("\\d+(\\.\\d+)?") ? e[6] : "";
            if (num.isEmpty() || taken.contains(num)) {
                while (taken.contains(String.valueOf(next))) next++;
                num = String.valueOf(next++);
            }
            taken.add(num);
            ch.number = num;
            r.channels.add(ch);
        }

        r.guideUrl = !guideSetting.isEmpty() ? guideSetting : !headerGuide.isEmpty() ? headerGuide : xtreamGuide(playlistUrl);
        if (!r.guideUrl.isEmpty() && !r.channels.isEmpty()) {
            try {
                applyGuide(r.guideUrl, r.channels);
            } catch (Exception e) {
                r.guideFailed = true;
                AppLog.e("IPTV", "Guide failed (" + AppLog.mask(r.guideUrl) + "); channels still play", e);
            }
        }
        return r;
    }

    /** Clears the guide cache, e.g. after the groups or addresses change. */
    static synchronized void forgetGuide() { cachedGuide = null; cachedGuideKey = null; }

    private static synchronized void applyGuide(String url, List<Channel> channels) throws Exception {
        // Each key can match several playlist channels (e.g. "US: BBC America" and "BBC America HD").
        Map<String, List<Channel>> byKey = new HashMap<>();
        for (Channel ch : channels) {
            if (!ch.guideId.isEmpty()) add(byKey, key(ch.guideId), ch);
        }
        for (Channel ch : channels) {
            add(byKey, "n:" + key(ch.guideName), ch);
            add(byKey, "n:" + key(ch.name), ch);
            // Loose match: without country prefixes, (D) / [HD] tags and quality words.
            add(byKey, "l:" + loose(ch.guideName), ch);
            add(byKey, "l:" + loose(ch.name), ch);
        }
        String cacheKey = url + "|" + channels.size();
        if (cachedGuide == null || !cacheKey.equals(cachedGuideKey) || System.currentTimeMillis() - cachedGuideAt > GUIDE_MAX_AGE_MS) {
            long t0 = System.currentTimeMillis();
            cachedGuide = readGuide(url, byKey.keySet());
            cachedGuideKey = cacheKey;
            cachedGuideAt = System.currentTimeMillis();
            int n = 0;
            for (List<Channel.Airing> l : cachedGuide.values()) n += l.size();
            AppLog.i("IPTV", "Guide: " + n + " airings for " + cachedGuide.size() + " channels in "
                    + (cachedGuideAt - t0) / 1000 + " s");
        }
        long now = System.currentTimeMillis() / 1000L;
        int matched = 0;
        for (Map.Entry<String, List<Channel.Airing>> e : cachedGuide.entrySet()) {
            List<Channel> chs = byKey.get(e.getKey());
            if (chs == null) continue;
            for (Channel ch : chs) {
                if (!ch.airings.isEmpty()) continue;
                for (Channel.Airing a : e.getValue()) if (a.end > now - 3600) ch.airings.add(a);
                if (!ch.airings.isEmpty()) matched++;
            }
        }
        AppLog.i("IPTV", "Guide matched " + matched + " of " + channels.size() + " channels");
    }

    private static void add(Map<String, List<Channel>> m, String k, Channel ch) {
        if (k.endsWith(":") || k.isEmpty()) return;
        List<Channel> l = m.get(k);
        if (l == null) m.put(k, l = new ArrayList<>());
        if (!l.contains(ch)) l.add(ch);
    }

    /** A forgiving key for names: "US: BBC America (D) HD" and "BBC America" both give "bbcamerica". */
    static String loose(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US)
                .replaceFirst("^\\s*[a-z]{2,3}\\s*[:|\\-]\\s*", "")       // country prefix
                .replaceAll("[\\(\\[][^\\)\\]]*[\\)\\]]", " ")              // (D), [HD], (East)…
                .replaceAll("\\b(fhd|uhd|hd|sd|4k|hevc|h265|backup|vip)\\b", " ")
                .replaceFirst("\\.[a-z]{2,3}$", "");                           // XMLTV ids like bbcamerica.us
        return t.replaceAll("[^a-z0-9]", "");
    }

    /**
     * Streams the XMLTV file and keeps airings from an hour ago to 14 hours ahead, keyed like
     * {@code wanted} (a channel id, or "n:" + display name).
     */
    private static Map<String, List<Channel.Airing>> readGuide(String url, Set<String> wanted) throws Exception {
        long now = System.currentTimeMillis() / 1000L, from = now - 3600, to = now + 14 * 3600;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        Map<String, String> idToKey = new HashMap<>();
        Map<String, List<Channel.Airing>> out = new HashMap<>();

        HttpURLConnection c = open(url);
        try (InputStream in = stream(c)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            String channelId = null, logo = null;
            List<String> names = new ArrayList<>();
            List<Channel.Airing> target = null;
            Channel.Airing air = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    switch (p.getName()) {
                        case "channel":
                            channelId = p.getAttributeValue(null, "id");
                            logo = null;
                            names.clear();
                            break;
                        case "display-name":
                            if (channelId != null) names.add(p.nextText().trim());
                            break;
                        case "programme": {
                            air = null;
                            String k = idToKey.get(p.getAttributeValue(null, "channel"));
                            if (k == null) break;
                            long start = parse(fmt, p.getAttributeValue(null, "start"));
                            long end = parse(fmt, p.getAttributeValue(null, "stop"));
                            if (start <= 0 || end <= start || end < from || start > to) break;
                            target = out.get(k);
                            if (target == null) out.put(k, target = new ArrayList<>());
                            air = new Channel.Airing();
                            air.start = start;
                            air.end = end;
                            break;
                        }
                        case "title":
                            if (air != null && air.title.isEmpty()) air.title = p.nextText().trim();
                            break;
                        case "sub-title":
                            if (air != null) air.episodeTitle = p.nextText().trim();
                            break;
                        case "desc":
                            if (air != null && air.synopsis.isEmpty()) air.synopsis = p.nextText().trim();
                            break;
                        case "episode-num":
                            if (air != null && "onscreen".equals(p.getAttributeValue(null, "system")))
                                air.episodeNumber = p.nextText().trim();
                            break;
                        case "icon":
                            if (air != null && air.imageUrl == null) air.imageUrl = p.getAttributeValue(null, "src");
                            break;
                        default:
                            break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("channel".equals(p.getName()) && channelId != null) {
                        // Match the guide's channel to a playlist channel: by id first, then by display name.
                        String k = wanted.contains(key(channelId)) ? key(channelId) : null;
                        for (int i = 0; k == null && i < names.size(); i++) {
                            String nk = "n:" + key(names.get(i));
                            if (wanted.contains(nk)) k = nk;
                        }
                        // Then the forgiving match, on the guide's names and on its channel id.
                        for (int i = 0; k == null && i < names.size(); i++) {
                            String lk = "l:" + loose(names.get(i));
                            if (wanted.contains(lk)) k = lk;
                        }
                        if (k == null && wanted.contains("l:" + loose(channelId))) k = "l:" + loose(channelId);
                        if (k != null) idToKey.put(channelId, k);
                        channelId = null;
                    } else if ("programme".equals(p.getName())) {
                        if (air != null && target != null) target.add(air);
                        air = null;
                        target = null;
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        for (List<Channel.Airing> l : out.values()) l.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    // ---- helpers ----

    /** Opens a URL, following redirects across http / https (Java won't on its own). */
    static HttpURLConnection open(String address) throws Exception {
        String url = address.trim();
        if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "LiveTVHome/1.0");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new Exception("HTTP " + code + " from " + AppLog.mask(url));
            }
            return c;
        }
        throw new Exception("Too many redirects from " + AppLog.mask(address));
    }

    /** The response body, un-gzipped when it's a .gz file or gzip-encoded. */
    private static InputStream stream(HttpURLConnection c) throws Exception {
        BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 65536);
        in.mark(2);
        int b1 = in.read(), b2 = in.read();
        in.reset();
        return b1 == 0x1f && b2 == 0x8b ? new BufferedInputStream(new GZIPInputStream(in, 65536), 65536) : in;
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> m = new HashMap<>();
        Matcher mt = ATTR.matcher(line);
        while (mt.find()) m.put(mt.group(1).toLowerCase(Locale.US), mt.group(2).trim());
        return m;
    }

    /** The channel name: everything after the first comma that isn't inside quotes. */
    private static String titleOf(String extinf) {
        boolean quoted = false;
        for (int i = 0; i < extinf.length(); i++) {
            char ch = extinf.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return extinf.substring(i + 1).trim();
        }
        return "";
    }

    /**
     * Xtream-style providers serve the playlist at .../get.php?username=U&password=P and the guide
     * for it at .../xmltv.php?username=U&password=P. Returns that guide link, or "" for other links.
     */
    static String xtreamGuide(String playlistUrl) {
        try {
            String u = playlistUrl.trim();
            if (!u.matches("(?i)^[a-z]+://.*")) u = "http://" + u;
            android.net.Uri p = android.net.Uri.parse(u);
            String path = p.getPath() == null ? "" : p.getPath();
            if (!path.toLowerCase(Locale.US).endsWith("/get.php")) return "";
            String user = p.getQueryParameter("username"), pass = p.getQueryParameter("password");
            if (user == null || pass == null) return "";
            return p.buildUpon().path(path.substring(0, path.length() - "get.php".length()) + "xmltv.php")
                    .clearQuery().appendQueryParameter("username", user).appendQueryParameter("password", pass)
                    .build().toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static String firstNonEmpty(String... s) {
        for (String v : s) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static long parse(SimpleDateFormat fmt, String s) {
        try {
            return s == null ? 0 : fmt.parse(s.trim()).getTime() / 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Stream URLs the timeshift recorder can't take (adaptive playlists, RTMP, RTSP): played directly. */
    static boolean isDirect(String url) {
        String u = url.toLowerCase(Locale.US);
        return u.contains(".m3u8") || u.contains(".mpd") || u.contains("type=m3u8") || u.contains("/hls/")
                || u.startsWith("rtmp") || u.startsWith("rtsp") || u.startsWith("udp") || u.startsWith("rtp");
    }
}
