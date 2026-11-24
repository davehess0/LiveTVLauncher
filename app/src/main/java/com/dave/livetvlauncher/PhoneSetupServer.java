package com.dave.livetvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Set up from your phone": a small web page on the home network for typing addresses on a phone
 * instead of with the remote. Runs only while its QR code is on screen in Settings.
 *
 * Every request must carry the random key from the QR code (?k=…), so other devices on the network
 * can't change anything. Plain HTTP on the local network only; nothing leaves the house.
 */
final class PhoneSetupServer {
    static final int PORT = 8765;

    private final Context app;
    private final Prefs prefs;
    private final Runnable onChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String key;
    private ServerSocket server;
    private volatile boolean running;
    /** Tuners from the last "Find HDHomeRun" on the phone page. */
    private volatile List<HdhrClient.Found> lastFound = java.util.Collections.emptyList();
    /** Servers from the last "Find Tunarr" on the phone page. */
    private volatile List<TunarrClient.Found> lastTunarr = java.util.Collections.emptyList();

    PhoneSetupServer(Context ctx, Runnable onChanged) {
        app = ctx.getApplicationContext();
        prefs = new Prefs(app);
        this.onChanged = onChanged;
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        key = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    /** Starts listening (port 8765, or any free port if that's taken). Returns the page URL, or null if offline. */
    String start() throws IOException {
        String ip = lanAddress();
        if (ip == null) return null;
        try {
            server = new ServerSocket(PORT);
        } catch (IOException busy) {
            server = new ServerSocket(0);
        }
        running = true;
        Thread t = new Thread(this::acceptLoop, "phone-setup");
        t.setDaemon(true);
        t.start();
        String url = "http://" + ip + ":" + server.getLocalPort() + "/?k=" + key;
        AppLog.i("PhoneSetup", "Web settings page open on " + ip + ":" + server.getLocalPort());
        return url;
    }

    void stop() {
        running = false;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        AppLog.i("PhoneSetup", "Web settings page closed");
    }

    /** This device's IPv4 address on the home network (Wi-Fi or Ethernet), or null. */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "phone-setup-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    // ---- HTTP ----

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String request = in.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String method = parts[0], target = parts.length > 1 ? parts[1] : "/";
            int length = 0;
            for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
                if (h.toLowerCase(java.util.Locale.US).startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
            }
            char[] body = new char[Math.min(length, 64 * 1024)];
            int got = 0;
            while (got < body.length) { int r = in.read(body, got, body.length - got); if (r < 0) break; got += r; }

            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Map<String, String> q = parseForm(target.contains("?") ? target.substring(target.indexOf('?') + 1) : "");
            Map<String, String> form = parseForm(new String(body, 0, got));
            OutputStream out = sock.getOutputStream();

            if (path.equals("/favicon.ico")) { send(out, 404, "text/plain", "none"); return; }
            if (!key.equals(q.get("k")) && !key.equals(form.get("k"))) {
                send(out, 403, "text/html", page("Scan the code again",
                        "<p>This link has expired. Open <b>Settings → Set up from your phone</b> on the TV and scan the new QR code.</p>"));
                return;
            }
            String notice = "";
            if (method.equals("POST")) notice = apply(path, form);
            send(out, 200, "text/html", settingsPage(notice));
        } catch (Exception e) {
            AppLog.w("PhoneSetup", "Request failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Not Found";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, "UTF-8");
                String v = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    // ---- Saving ----

    /** Applies a form post; returns a short message for the top of the page. */
    private String apply(String path, Map<String, String> f) {
        switch (path) {
            case "/save": {
                String tuner = clean(f.get("tuner")), tunarr = clean(f.get("tunarr")), plex = clean(f.get("plex"));
                if (!tunarr.isEmpty() && !tunarr.matches("(?i)^https?://.*")) tunarr = "http://" + tunarr;
                if (!plex.isEmpty() && !plex.matches("(?i)^https?://.*")) plex = "http://" + plex;
                if (!plex.isEmpty() && !plex.matches(".*:\\d+/?$")) plex = plex.replaceAll("/+$", "") + ":32400";
                String iptv = clean(f.get("iptv")), iptvGuide = clean(f.get("iptvguide"));
                boolean sourcesChanged = !tuner.equals(prefs.tunerIp()) || !tunarr.equals(prefs.tunarrUrl()) || !plex.equals(prefs.plexUrl())
                        || !iptv.equals(prefs.iptvUrl()) || !iptvGuide.equals(prefs.iptvGuideUrl());
                if (!iptv.equals(prefs.iptvUrl())) prefs.setIptvHiddenGroups(new java.util.HashSet<>());
                prefs.setIptvUrl(iptv);
                prefs.setIptvGuideUrl(iptvGuide);
                if (sourcesChanged) IptvClient.forgetGuide();
                prefs.setTunerIp(tuner);
                prefs.setTunarrUrl(tunarr);
                prefs.setPlexUrl(plex);
                String weatherMsg = "";
                String place = clean(f.get("weather"));
                if (place.isEmpty()) {
                    if (!prefs.weatherLat().isEmpty()) prefs.setWeather("", "", "", prefs.weatherFahrenheit());
                } else if (!place.equals(prefs.weatherPlace())) {
                    try {
                        Weather.Place p = Weather.geocode(place);
                        if (p != null) prefs.setWeather(p.name, p.lat, p.lon, p.fahrenheit);
                        else weatherMsg = " Couldn't find \"" + esc(place) + "\" for weather.";
                    } catch (Exception e) {
                        weatherMsg = " Couldn't look up the weather location right now.";
                    }
                }
                AppLog.i("PhoneSetup", "Saved from phone: HDHomeRun=" + (tuner.isEmpty() ? "automatic" : tuner)
                        + " · Tunarr=" + (tunarr.isEmpty() ? "off" : tunarr) + " · Plex server=" + (plex.isEmpty() ? "automatic" : plex)
                        + " · IPTV=" + (iptv.isEmpty() ? "off" : iptv)
                        + " · weather=" + (prefs.weatherPlace().isEmpty() ? "off" : prefs.weatherPlace()));
                changed(sourcesChanged);
                return "Saved." + weatherMsg + (sourcesChanged ? " Channels are reloading on the TV." : "");
            }
            case "/find": {
                lastFound = HdhrClient.findTuners(prefs.tunerIp());
                return lastFound.isEmpty() ? "No HDHomeRun found. Check it's on and on the same network, or type its address below."
                        : "Found " + lastFound.size() + (lastFound.size() == 1 ? " tuner" : " tuners") + ". Tap Use to pick one.";
            }
            case "/findtunarr": {
                lastTunarr = TunarrClient.find(prefs.tunerIp(), prefs.tunarrUrl());
                return lastTunarr.isEmpty() ? "No Tunarr server found. Check it's running and on the same network, or type its address below."
                        : "Found " + lastTunarr.size() + (lastTunarr.size() == 1 ? " Tunarr server" : " Tunarr servers") + ". Tap Use to pick one.";
            }
            case "/usetunarr": {
                String base = clean(f.get("base"));
                for (TunarrClient.Found t : lastTunarr) {
                    if (t.base.equals(base)) {
                        prefs.setTunarrUrl(base);
                        AppLog.i("PhoneSetup", "Tunarr chosen from phone: " + t.label() + " at " + base);
                        changed(true);
                        return "Using " + esc(t.label()) + " at " + esc(base) + ". Channels are reloading on the TV.";
                    }
                }
                return "Search again, then tap Use.";
            }
            case "/usetuner": {
                String ip = clean(f.get("ip"));
                for (HdhrClient.Found t : lastFound) {
                    if (t.ip.equals(ip)) {
                        prefs.setTunerIp(ip);
                        AppLog.i("PhoneSetup", "HDHomeRun chosen from phone: " + t.label() + " at " + ip);
                        changed(true);
                        return "Using " + esc(t.label()) + " at " + esc(ip) + ". Channels are reloading on the TV.";
                    }
                }
                return "Search again, then tap Use.";
            }
            case "/camera/add": {
                String name = clean(f.get("name")), url = clean(f.get("url"));
                if (url.isEmpty()) return "Enter the camera's stream address.";
                if (!url.matches("(?i)^(rtsps?|https?)://.+")) url = "rtsp://" + url;
                List<String[]> cams = prefs.cameras();
                if (name.isEmpty()) name = "Camera " + (cams.size() + 1);
                cams.add(new String[]{name, url});
                prefs.setCameras(cams);
                AppLog.i("Cameras", "Added from phone: " + name + " · " + url);
                changed(false);
                int s = url.indexOf("://") + 3, at = url.lastIndexOf('@');
                boolean atInPass = at > s && url.substring(s, at).contains("@");
                return "Added " + esc(name) + "." + (atInPass ? " Note: a password containing \"@\" won't work with the player." : "");
            }
            case "/camera/remove": {
                List<String[]> cams = prefs.cameras();
                try {
                    int i = Integer.parseInt(f.get("i"));
                    String n = cams.remove(i)[0];
                    prefs.setCameras(cams);
                    AppLog.i("Cameras", "Removed from phone: " + n);
                    changed(false);
                    return "Removed " + esc(n) + ".";
                } catch (Exception e) {
                    return "That camera was already removed.";
                }
            }
            default:
                return "";
        }
    }

    private void changed(boolean reloadChannels) {
        main.post(() -> {
            if (reloadChannels) ChannelStore.get().refresh(app);
            if (onChanged != null) onChanged.run();
        });
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    // ---- Page ----

    private String settingsPage(String notice) {
        StringBuilder b = new StringBuilder();
        if (!notice.isEmpty()) b.append("<div class=note>").append(notice).append("</div>");
        String k = esc(key);
        // HDHomeRun search: lists tuners found on the network with a "Use" button each.
        b.append("<h2>HDHomeRun</h2>");
        for (HdhrClient.Found t : lastFound) {
            boolean using = t.ip.equals(prefs.tunerIp());
            b.append("<form method=post action='/usetuner' class=cam><input type=hidden name=k value='").append(k)
             .append("'><input type=hidden name=ip value='").append(esc(t.ip)).append("'><div><b>").append(esc(t.label()))
             .append("</b><br><span class=dim>").append(esc(t.detail())).append("</span></div>")
             .append(using ? "<span class=dim>In use</span>" : "<button class=small>Use</button>").append("</form>");
        }
        b.append("<form method=post action='/find'><input type=hidden name=k value='").append(k)
         .append("'><button class=secondary>Find HDHomeRun on the network</button></form>");

        // Tunarr search: servers found on the network with a "Use" button each.
        b.append("<h2>Tunarr</h2>");
        String tunarrNow = prefs.tunarrUrl().isEmpty() ? "" : TunarrClient.normalize(prefs.tunarrUrl());
        for (TunarrClient.Found t : lastTunarr) {
            boolean using = t.base.equals(tunarrNow);
            b.append("<form method=post action='/usetunarr' class=cam><input type=hidden name=k value='").append(k)
             .append("'><input type=hidden name=base value='").append(esc(t.base)).append("'><div><b>").append(esc(t.label()))
             .append("</b><br><span class=dim>").append(esc(t.base)).append("</span></div>")
             .append(using ? "<span class=dim>In use</span>" : "<button class=small>Use</button>").append("</form>");
        }
        b.append("<form method=post action='/findtunarr'><input type=hidden name=k value='").append(k)
         .append("'><button class=secondary>Find Tunarr on the network</button></form>");

        b.append("<h2>Addresses</h2>");
        b.append("<form method=post action='/save'><input type=hidden name=k value='").append(k).append("'>")
         .append(field("HDHomeRun address", "tuner", prefs.tunerIp(), "e.g. 192.168.1.50 — blank = find it automatically"))
         .append(field("Tunarr server", "tunarr", prefs.tunarrUrl(), "e.g. http://192.168.1.50:8000 — blank = off"))
         .append(field("IPTV playlist (M3U)", "iptv", prefs.iptvUrl(), "e.g. http://provider.example/get.php?username=…&type=m3u_plus — blank = off"))
         .append(field("IPTV guide (XMLTV)", "iptvguide", prefs.iptvGuideUrl(), "blank = use the playlist's own guide, if any"))
         .append(field("Plex server address", "plex", prefs.plexUrl(), "e.g. http://192.168.1.50:32400 — blank = automatic after linking"))
         .append(field("Weather (ZIP code or city)", "weather", prefs.weatherPlace(), "blank = off"))
         .append("<button>Save</button></form>");

        b.append("<h2>Cameras</h2>");
        List<String[]> cams = prefs.cameras();
        if (cams.isEmpty()) b.append("<p class=dim>No cameras yet.</p>");
        for (int i = 0; i < cams.size(); i++) {
            b.append("<form method=post action='/camera/remove' class=cam><input type=hidden name=k value='").append(k)
             .append("'><input type=hidden name=i value=").append(i).append("><div><b>").append(esc(cams.get(i)[0]))
             .append("</b><br><span class=dim>").append(esc(AppLog.mask(cams.get(i)[1]))).append("</span></div>")
             .append("<button class=small>Remove</button></form>");
        }
        b.append("<form method=post action='/camera/add'><input type=hidden name=k value='").append(k).append("'>")
         .append(field("Camera name", "name", "", "e.g. Front door"))
         .append(field("Stream address (RTSP)", "url", "", "rtsp://user:password@192.168.1.60/live0"))
         .append("<p class=dim>In the Eufy app: camera → Settings → Storage (or NAS) → RTSP stream.</p>")
         .append("<button>Add camera</button></form>");
        b.append("<p class=dim>This page works only while the QR code is showing on the TV.</p>");
        return page("Live TV Home settings", b.toString());
    }

    private static String field(String label, String name, String value, String hint) {
        return "<label>" + esc(label) + "<input name=" + name + " value=\"" + esc(value) + "\" placeholder=\"" + esc(hint)
                + "\" autocapitalize=off autocorrect=off spellcheck=false inputmode=url></label>";
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(title) + "</title><style>"
                + "body{font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0a0a0c;color:#f2f2f7;margin:0;padding:20px 16px 40px;max-width:560px;margin:auto}"
                + "h1{font-size:22px;margin:4px 0 18px}h2{font-size:18px;margin:28px 0 10px}"
                + "label{display:block;font-size:14px;color:#aeaeb2;margin:14px 0 0}"
                + "input{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:13px 12px;font-size:16px;border-radius:10px;border:1px solid #3a3a3c;background:#1c1c1e;color:#fff}"
                + "button{margin-top:18px;width:100%;padding:14px;font-size:16px;font-weight:600;border:0;border-radius:12px;background:#0a84ff;color:#fff}"
                + "button.small{width:auto;margin:0;padding:9px 14px;background:#3a3a3c;font-size:14px}"
                + "button.secondary{background:#2c2c2e;margin-top:8px}"
                + ".cam{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px;background:#1c1c1e;border-radius:12px;margin:8px 0;word-break:break-all}"
                + ".dim{color:#8e8e93;font-size:13px}.note{background:#1f3a26;color:#d1f7d9;padding:12px;border-radius:10px;margin-bottom:8px}"
                + "</style></head><body><h1>" + esc(title) + "</h1>" + body + "</body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ---- QR code ----

    /** QR code bitmap for the URL (white on black modules inverted for a dark screen: black on white). */
    static android.graphics.Bitmap qr(String text, int sizePx) {
        try {
            Map<com.google.zxing.EncodeHintType, Object> hints = new HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 2);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
