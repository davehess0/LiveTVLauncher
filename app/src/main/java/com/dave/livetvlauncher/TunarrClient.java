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

    // ---- Finding Tunarr on the network ----

    static final class Found {
        String base;    // e.g. http://192.168.1.50:8000
        String version; // e.g. 1.3.8
        String label() { return version.isEmpty() ? "Tunarr" : "Tunarr " + version; }
    }

    /** Tunarr's default web port; it can be changed, but the address scan only tries this one. */
    static final int DEFAULT_PORT = 8000;

    /**
     * Tunarr servers on the home network. Tries the saved address, then asks over SSDP (Tunarr
     * announces itself as a media server); if nothing answers, tries port 8000 on every address of
     * this device's network and of the HDHomeRun's network. Network work: call off the main thread
     * (about 2 s, up to ~14 s when it has to scan).
     */
    static List<Found> find(String tunerIp, String savedUrl) {
        java.util.LinkedHashSet<String> bases = new java.util.LinkedHashSet<>();
        if (savedUrl != null && !savedUrl.trim().isEmpty()) bases.add(normalize(savedUrl));
        bases.addAll(ssdpSearch());
        List<Found> out = new ArrayList<>();
        for (String b : bases) {
            Found f = probe(b, 3000);
            if (f != null) out.add(f);
        }
        boolean scanned = false;
        if (out.isEmpty()) {
            scanned = true;
            out.addAll(scan(tunerIp));
        }
        AppLog.i("Channels", "Tunarr search found " + out.size() + (out.size() == 1 ? " server" : " servers")
                + (scanned ? " (address scan)" : "") + (out.isEmpty() ? "" : ": " + bases(out)));
        return out;
    }

    private static String bases(List<Found> fs) {
        StringBuilder b = new StringBuilder();
        for (Found f : fs) b.append(b.length() > 0 ? ", " : "").append(f.base);
        return b.toString();
    }

    static String normalize(String address) {
        String b = address.trim();
        if (!b.matches("(?i)^https?://.*")) b = "http://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** Tunarr answers /api/version with {"tunarr":"1.3.8", ...}. */
    private static Found probe(String base, int timeoutMs) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(base + "/api/version").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            try {
                if (c.getResponseCode() != 200) return null;
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while (bo.size() < 8192 && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                if (!j.has("tunarr")) return null;
                Found f = new Found();
                f.base = base;
                f.version = j.optString("tunarr", "");
                return f;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** SSDP M-SEARCH: http://<answering address>:<port> for each media server that replied. */
    private static List<String> ssdpSearch() {
        List<String> out = new ArrayList<>();
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setSoTimeout(300);
            byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                    + "MX: 1\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").getBytes("US-ASCII");
            java.net.InetAddress group = java.net.InetAddress.getByName("239.255.255.250");
            s.send(new java.net.DatagramPacket(msg, msg.length, group, 1900));
            s.send(new java.net.DatagramPacket(msg, msg.length, group, 1900));
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 1800;
            while (System.currentTimeMillis() < end) {
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException again) {
                    continue;
                }
                String reply = new String(buf, 0, p.getLength(), "US-ASCII");
                for (String line : reply.split("\r\n")) {
                    if (!line.regionMatches(true, 0, "LOCATION:", 0, 9)) continue;
                    try {
                        java.net.URL loc = new java.net.URL(line.substring(9).trim());
                        // Use the address the reply came from: servers also advertise other interfaces
                        // of theirs (e.g. a virtual network) that this device can't reach.
                        String base = "http://" + p.getAddress().getHostAddress() + ":" + (loc.getPort() > 0 ? loc.getPort() : 80);
                        if (!out.contains(base)) out.add(base);
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) {
            // No network / multicast blocked: the address scan still runs.
        }
        return out;
    }

    /** Tries port 8000 on every address of this device's /24 networks and the HDHomeRun's. */
    private static List<Found> scan(String tunerIp) {
        java.util.LinkedHashSet<String> prefixes = new java.util.LinkedHashSet<>();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) prefixes.add(prefix(a.getHostAddress()));
                }
            }
        } catch (Exception ignored) { }
        String t = tunerIp == null ? "" : tunerIp.trim().replaceAll("^https?://", "").replaceAll("[:/].*$", "");
        if (t.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) prefixes.add(prefix(t));
        List<Found> out = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(48);
        for (String pre : prefixes) {
            for (int i = 1; i < 255; i++) {
                final String base = "http://" + pre + i + ":" + DEFAULT_PORT;
                pool.execute(() -> {
                    Found f = probe(base, 600);
                    if (f != null) out.add(f);
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(12, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        pool.shutdownNow();
        synchronized (out) { return new ArrayList<>(out); }
    }

    private static String prefix(String ip) {
        return ip.substring(0, ip.lastIndexOf('.') + 1);
    }
}
