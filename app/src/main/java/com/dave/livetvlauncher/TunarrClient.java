package com.dave.livetvlauncher;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Channels from a Tunarr server (https://github.com/chrisbenincasa/tunarr).
 *
 * Tunarr pretends to be an HDHomeRun, so its lineup.json lists channels and stream URLs the same
 * way. Its guide isn't on api.hdhomerun.com, though; it comes from Tunarr's own XMLTV file.
 * Only the Tunarr server itself is contacted.
 */
final class TunarrClient {
    private TunarrClient() { }

    /** The server's custom-channel logos; the generic Tunarr logo is left out so the number badge shows instead. */
    private static final String GENERIC_LOGO = "/images/tunarr.png";

    static List<Channel> load(String address) throws Exception {
        String base = address.trim();
        if (!base.startsWith("http")) base = "http://" + base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        Map<String, Channel> byNumber = new HashMap<>();
        List<Channel> channels = new ArrayList<>();
        JSONArray lineup = new JSONArray(HdhrClient.get(base + "/lineup.json"));
        for (int i = 0; i < lineup.length(); i++) {
            JSONObject c = lineup.getJSONObject(i);
            String num = c.optString("GuideNumber", "");
            if (num.isEmpty() || byNumber.containsKey(num)) continue;
            Channel ch = new Channel();
            ch.number = num;
            ch.name = c.optString("GuideName", num);
            ch.streamUrl = c.optString("URL", "");
            ch.tunarr = true;
            byNumber.put(num, ch);
            channels.add(ch);
        }

        try {
            readGuide(HdhrClient.get(base + "/api/xmltv.xml"), byNumber, base);
        } catch (Exception ignored) {
            // Channels still play without guide data.
        }
        return channels;
    }

    /** Fills in logos and airings (from an hour ago to 8 hours ahead) from Tunarr's XMLTV guide. */
    private static void readGuide(String xml, Map<String, Channel> byNumber, String base) throws Exception {
        long now = System.currentTimeMillis() / 1000L, from = now - 3600, to = now + 8 * 3600;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        Map<String, Channel> byId = new HashMap<>();

        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        String channelId = null, icon = null;
        List<String> displayNames = new ArrayList<>();
        Channel progCh = null;
        Channel.Airing air = null;

        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                switch (p.getName()) {
                    case "channel":
                        channelId = p.getAttributeValue(null, "id");
                        icon = null;
                        displayNames.clear();
                        break;
                    case "display-name":
                        if (channelId != null) displayNames.add(p.nextText().trim());
                        break;
                    case "icon":
                        if (channelId != null) icon = p.getAttributeValue(null, "src");
                        else if (air != null && air.imageUrl == null) air.imageUrl = p.getAttributeValue(null, "src");
                        break;
                    case "programme": {
                        progCh = byId.get(p.getAttributeValue(null, "channel"));
                        air = null;
                        if (progCh == null) break;
                        long start = parse(fmt, p.getAttributeValue(null, "start"));
                        long end = parse(fmt, p.getAttributeValue(null, "stop"));
                        if (start <= 0 || end <= start || end < from || start > to) break;
                        air = new Channel.Airing();
                        air.start = start;
                        air.end = end;
                        air.title = "";
                        air.episodeTitle = "";
                        air.episodeNumber = "";
                        air.synopsis = "";
                        break;
                    }
                    case "title":
                        if (air != null) air.title = p.nextText().trim();
                        break;
                    case "sub-title":
                        if (air != null) air.episodeTitle = p.nextText().trim();
                        break;
                    case "desc":
                        if (air != null) air.synopsis = p.nextText().trim();
                        break;
                    case "episode-num":
                        if (air != null && "onscreen".equals(p.getAttributeValue(null, "system")))
                            air.episodeNumber = p.nextText().trim();
                        break;
                    case "image":
                        if (air != null && air.imageUrl == null) {
                            String u = p.nextText().trim();
                            if (!u.isEmpty()) air.imageUrl = u;
                        }
                        break;
                    default:
                        break;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("channel".equals(p.getName()) && channelId != null) {
                    // Tunarr lists "<number> <name>", "<number>", "<name>"; match on the bare number.
                    for (String dn : displayNames) {
                        Channel ch = byNumber.get(dn);
                        if (ch == null) continue;
                        byId.put(channelId, ch);
                        if (icon != null && !icon.isEmpty() && !icon.endsWith(GENERIC_LOGO)) ch.logoUrl = onServer(icon, base);
                        break;
                    }
                    channelId = null;
                } else if ("programme".equals(p.getName())) {
                    if (air != null && progCh != null) {
                        if (air.imageUrl != null) air.imageUrl = onServer(air.imageUrl, base);
                        progCh.airings.add(air);
                    }
                    air = null;
                    progCh = null;
                }
            }
        }
        for (Channel ch : byNumber.values()) ch.airings.sort((a, b) -> Long.compare(a.start, b.start));
    }

    /**
     * Tunarr writes uploaded logos as http://localhost:8000/..., which on the Shield would mean the
     * Shield itself. Point localhost / 127.0.0.1 (and relative paths) at the Tunarr server instead.
     */
    static String onServer(String url, String base) {
        String u = url.trim();
        if (u.startsWith("/")) return base + u;
        if (!u.startsWith("http")) return base + "/" + u;
        try {
            java.net.URL parsed = new java.net.URL(u);
            String host = parsed.getHost();
            if (host.equals("localhost") || host.startsWith("127.") || host.equals("0.0.0.0") || host.equals("[::1]")) {
                String rest = parsed.getFile() + (parsed.getRef() != null ? "#" + parsed.getRef() : "");
                return base + rest;
            }
        } catch (Exception ignored) {
            // Not a URL we can read: leave it as it is.
        }
        return u;
    }

    private static long parse(SimpleDateFormat fmt, String s) {
        try {
            return s == null ? 0 : fmt.parse(s.trim()).getTime() / 1000L;
        } catch (Exception e) {
            return 0;
        }
    }
}
