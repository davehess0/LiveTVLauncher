package com.dave.livetvlauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Talks to HDHomeRun tuners directly.
 *
 *  1. Find tuners: a manual IP from Settings, else a local-network broadcast (UDP 65001),
 *     else HDHomeRun's cloud list (ipv4-api.hdhomerun.com/discover), else http://hdhomerun.local.
 *  2. discover.json on each tuner gives DeviceAuth + LineupURL.
 *  3. lineup.json gives channels and their direct stream URLs.
 *  4. api.hdhomerun.com/api/guide?DeviceAuth=... gives now/next guide data.
 *
 * No other hosts are contacted.
 */
public final class HdhrClient {

    public static final class Result {
        public final List<Channel> channels = new ArrayList<>();
        public String tunerSummary = "";
        public boolean guideLoaded;
    }

    public static Result load(String manualIp) throws Exception {
        Set<String> discoverUrls = new LinkedHashSet<>();

        if (manualIp != null && !manualIp.trim().isEmpty()) {
            String ip = manualIp.trim();
            if (!ip.startsWith("http")) ip = "http://" + ip;
            discoverUrls.add(stripSlash(ip) + "/discover.json");
        } else {
            // 1. Ask the local network directly (what HDHomeRun's own apps do). Works without internet.
            for (String ip : broadcastDiscover()) discoverUrls.add("http://" + ip + "/discover.json");
            // 2. HDHomeRun's cloud list of tuners seen from this home's internet address. It now lives at
            //    ipv4-api (the https address redirects to http, which Android won't follow on its own).
            if (discoverUrls.isEmpty()) {
                for (String api : new String[]{"http://ipv4-api.hdhomerun.com/discover", "https://api.hdhomerun.com/discover"}) {
                    try {
                        JSONArray devices = new JSONArray(get(api));
                        for (int i = 0; i < devices.length(); i++) {
                            JSONObject d = devices.getJSONObject(i);
                            // Tuners have a LineupURL; DVR storage engines don't.
                            if (d.has("LineupURL") && d.has("DiscoverURL")) discoverUrls.add(d.getString("DiscoverURL"));
                        }
                        break;
                    } catch (Exception ignored) {
                        // Offline, blocked, or moved: try the next one.
                    }
                }
            }
            // 3. Last resort: the tuner's mDNS name.
            if (discoverUrls.isEmpty()) discoverUrls.add("http://hdhomerun.local/discover.json");
        }

        Result result = new Result();
        StringBuilder deviceAuth = new StringBuilder();
        Map<String, Channel> byNumber = new HashMap<>();
        int tuners = 0;
        Exception lastError = null;

        for (String url : discoverUrls) {
            try {
                JSONObject d = new JSONObject(get(url));
                tuners++;
                deviceAuth.append(d.optString("DeviceAuth", ""));
                String base = d.optString("BaseURL", url.replace("/discover.json", ""));
                String lineupUrl = d.optString("LineupURL", stripSlash(base) + "/lineup.json");
                JSONArray lineup = new JSONArray(get(lineupUrl));
                for (int i = 0; i < lineup.length(); i++) {
                    JSONObject c = lineup.getJSONObject(i);
                    String num = c.optString("GuideNumber", "");
                    if (num.isEmpty() || byNumber.containsKey(num)) continue;
                    if (c.optInt("DRM", 0) == 1) continue; // copy-protected cable channels can't play here
                    Channel ch = new Channel();
                    ch.number = num;
                    ch.name = c.optString("GuideName", num);
                    ch.streamUrl = c.optString("URL", "");
                    ch.hd = c.optInt("HD", 0) == 1;
                    ch.favorite = c.optInt("Favorite", 0) == 1;
                    byNumber.put(num, ch);
                }
            } catch (Exception e) {
                lastError = e;
            }
        }

        if (byNumber.isEmpty()) {
            if (lastError != null) throw lastError;
            throw new Exception("No HDHomeRun channels found");
        }

        if (deviceAuth.length() > 0) {
            try {
                String guideUrl = "https://api.hdhomerun.com/api/guide?Duration=8&DeviceAuth="
                        + URLEncoder.encode(deviceAuth.toString(), "UTF-8");
                JSONArray guide = new JSONArray(get(guideUrl));
                for (int i = 0; i < guide.length(); i++) {
                    JSONObject g = guide.getJSONObject(i);
                    Channel ch = byNumber.get(g.optString("GuideNumber", ""));
                    if (ch == null) continue;
                    String img = g.optString("ImageURL", "");
                    if (!img.isEmpty()) ch.logoUrl = img;
                    JSONArray airings = g.optJSONArray("Guide");
                    if (airings == null) continue;
                    for (int j = 0; j < airings.length(); j++) {
                        JSONObject a = airings.getJSONObject(j);
                        Channel.Airing air = new Channel.Airing();
                        air.start = a.optLong("StartTime");
                        air.end = a.optLong("EndTime");
                        air.title = a.optString("Title", "");
                        air.episodeTitle = a.optString("EpisodeTitle", "");
                        air.episodeNumber = a.optString("EpisodeNumber", "");
                        air.synopsis = a.optString("Synopsis", "");
                        String aimg = a.optString("ImageURL", "");
                        air.imageUrl = aimg.isEmpty() ? null : aimg;
                        ch.airings.add(air);
                    }
                }
                result.guideLoaded = true;
            } catch (Exception ignored) {
                // Channels still work without guide data.
            }
        }

        result.channels.addAll(byNumber.values());
        Collections.sort(result.channels, Comparator.comparingDouble(Channel::sortKey));
        result.tunerSummary = tuners + (tuners == 1 ? " tuner · " : " tuners · ")
                + result.channels.size() + " channels"
                + (result.guideLoaded ? "" : " · no guide data");
        return result;
    }

    /**
     * HDHomeRun local discovery: broadcast a "who's there?" packet on UDP 65001 and collect the
     * addresses of tuners that answer within about 1.5 s. Returns an empty list on any error.
     */
    static List<String> broadcastDiscover() {
        List<String> found = new ArrayList<>();
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            // Request (type 0x0002) asking for any device type and any device ID, then CRC32 (little-endian).
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(20);
            b.putShort((short) 0x0002).putShort((short) 12)
                    .put((byte) 0x01).put((byte) 4).putInt(0xFFFFFFFF)
                    .put((byte) 0x02).put((byte) 4).putInt(0xFFFFFFFF);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(b.array(), 0, 16);
            long v = crc.getValue();
            b.put((byte) v).put((byte) (v >> 8)).put((byte) (v >> 16)).put((byte) (v >> 24));
            byte[] pkt = b.array();
            s.send(new java.net.DatagramPacket(pkt, pkt.length,
                    java.net.InetAddress.getByName("255.255.255.255"), 65001));
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException again) {
                    continue;
                }
                // Reply type 0x0003 = discover response.
                if (p.getLength() >= 4 && buf[0] == 0x00 && buf[1] == 0x03) {
                    String ip = p.getAddress().getHostAddress();
                    if (!found.contains(ip)) found.add(ip);
                }
            }
        } catch (Exception ignored) {
            // No network or broadcast not allowed: the caller falls back to the cloud lookup.
        }
        return found;
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "LiveTVHome/1.0");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " from " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
