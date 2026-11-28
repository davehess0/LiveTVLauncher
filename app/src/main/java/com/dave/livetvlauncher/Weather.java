package com.dave.livetvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for the clock, from Open-Meteo (free, no account or key).
 *
 * Off until a location is set in Settings. Only the saved latitude/longitude are sent, to
 * api.open-meteo.com. What's typed in Settings is looked up once: US ZIP codes at api.zippopotam.us,
 * place names at geocoding-api.open-meteo.com.
 */
final class Weather {
    private static final Weather INSTANCE = new Weather();
    static Weather get() { return INSTANCE; }

    private static final long MAX_AGE_MS = 30 * 60_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String text = "";
    private volatile String forKey = "";
    private volatile long loadedAt;
    private volatile boolean loading;

    /** e.g. "☀ 72°", or "" when off / not loaded yet. */
    String text(Prefs prefs) {
        return prefs.weatherLat().isEmpty() || !key(prefs).equals(forKey) ? "" : text;
    }

    /** Fetch if never loaded, older than 30 min, or the location changed; then run onDone on the main thread. */
    void refreshIfStale(Context ctx, Runnable onDone) {
        Prefs prefs = new Prefs(ctx);
        String lat = prefs.weatherLat(), lon = prefs.weatherLon();
        if (lat.isEmpty() || loading) return;
        String key = key(prefs);
        if (key.equals(forKey) && System.currentTimeMillis() - loadedAt < MAX_AGE_MS) return;
        boolean f = prefs.weatherFahrenheit();
        loading = true;
        io.execute(() -> {
            try {
                JSONObject cur = new JSONObject(HdhrClient.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day"
                        + (f ? "&temperature_unit=fahrenheit" : ""))).getJSONObject("current");
                long temp = Math.round(cur.getDouble("temperature_2m"));
                text = icon(cur.optInt("weather_code", -1), cur.optInt("is_day", 1) == 1) + " " + temp + "°";
                forKey = key;
                loadedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("Weather", "Update failed (" + e.getClass().getSimpleName() + "); retrying in about 5 min");
                loadedAt = System.currentTimeMillis() - MAX_AGE_MS + 5 * 60_000L; // try again in ~5 min
            } finally {
                loading = false;
                if (onDone != null) main.post(onDone);
            }
        });
    }

    private static String key(Prefs p) { return p.weatherLat() + "," + p.weatherLon() + "," + p.weatherFahrenheit(); }

    /** A place found from a ZIP code or city name. */
    static final class Place {
        String name, lat, lon;
        boolean fahrenheit;
    }

    /** Look up a ZIP code or city name. Network call: run off the main thread. */
    static Place geocode(String query) throws Exception {
        // US ZIP codes: Open-Meteo's search only knows some of them, so use Zippopotam.us (free, no key).
        java.util.regex.Matcher zip = java.util.regex.Pattern.compile("^\\s*(\\d{5})(-\\d{4})?\\s*$").matcher(query);
        if (zip.matches()) {
            try {
                JSONObject z = new JSONObject(HdhrClient.get("https://api.zippopotam.us/us/" + zip.group(1)));
                JSONObject pl = z.getJSONArray("places").getJSONObject(0);
                Place p = new Place();
                p.name = pl.optString("place name", zip.group(1)) + ", " + pl.optString("state abbreviation", "");
                p.lat = String.format(Locale.US, "%.3f", Double.parseDouble(pl.getString("latitude")));
                p.lon = String.format(Locale.US, "%.3f", Double.parseDouble(pl.getString("longitude")));
                p.fahrenheit = true;
                return p;
            } catch (Exception ignored) {
                // Unknown ZIP or service down: fall through to the name search.
            }
        }
        JSONObject r = new JSONObject(HdhrClient.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                + URLEncoder.encode(query.trim(), "UTF-8")));
        JSONArray results = r.optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject g = results.getJSONObject(0);
        Place p = new Place();
        String region = g.optString("admin1", "");
        p.name = g.optString("name", query) + (region.isEmpty() ? "" : ", " + region);
        p.lat = String.format(Locale.US, "%.3f", g.getDouble("latitude"));
        p.lon = String.format(Locale.US, "%.3f", g.getDouble("longitude"));
        String cc = g.optString("country_code", "");
        // Fahrenheit where it's the everyday unit.
        p.fahrenheit = cc.equals("US") || cc.equals("LR") || cc.equals("FM") || cc.equals("MH") || cc.equals("PW") || cc.equals("KY") || cc.equals("BS") || cc.equals("BZ");
        return p;
    }

    // ---- Weather alerts (US: National Weather Service) ----

    /** One active watch / warning / advisory for the saved location. */
    static final class Alert {
        String id = "", event = "", headline = "", area = "", description = "", instruction = "", severity = "", sender = "";
        long endsMs;

        /** Watches, warnings and moderate advisories pop up; minor advisories only show in the weather screen. */
        boolean popsUp() {
            return severity.equals("Extreme") || severity.equals("Severe") || severity.equals("Moderate");
        }

        int color() {
            switch (severity) {
                case "Extreme": case "Severe": return 0xFFD32F2F;
                case "Moderate": return 0xFFEF6C00;
                default: return 0xFFF9A825;
            }
        }

        String until(boolean h24) {
            if (endsMs <= 0) return "";
            return "until " + new java.text.SimpleDateFormat(h24 ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault()).format(new java.util.Date(endsMs));
        }
    }

    private static final long ALERTS_MAX_AGE_MS = 5 * 60_000L;
    private volatile List<Alert> alerts = java.util.Collections.emptyList();
    private volatile String alertsKey = "";
    private volatile long alertsAt;
    private volatile boolean alertsLoading;

    /** Active alerts for the saved location, most serious first (empty if weather is off). */
    List<Alert> alerts(Prefs p) {
        if (p.weatherLat().isEmpty() || !p.weatherAlerts() || !alertsKey.equals(p.weatherLat() + "," + p.weatherLon())) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        List<Alert> out = new java.util.ArrayList<>();
        for (Alert a : alerts) if (a.endsMs <= 0 || a.endsMs > now) out.add(a);
        return out;
    }

    /** Alerts that should pop up and haven't been dismissed. */
    List<Alert> undismissed(Prefs p) {
        List<Alert> out = new java.util.ArrayList<>();
        java.util.Set<String> gone = p.dismissedAlerts();
        for (Alert a : alerts(p)) if (a.popsUp() && !gone.contains(a.id)) out.add(a);
        return out;
    }

    /** Check the National Weather Service for alerts every 5 minutes; onDone runs on the main thread. */
    void refreshAlertsIfStale(Context ctx, Runnable onDone) {
        Prefs p = new Prefs(ctx);
        if (p.weatherLat().isEmpty() || !p.weatherAlerts() || alertsLoading) return;
        String key = p.weatherLat() + "," + p.weatherLon();
        if (key.equals(alertsKey) && System.currentTimeMillis() - alertsAt < ALERTS_MAX_AGE_MS) return;
        alertsLoading = true;
        io.execute(() -> {
            try {
                alerts = fetchAlerts(p.weatherLat(), p.weatherLon());
                alertsKey = key;
                if (!alerts.isEmpty()) {
                    StringBuilder s = new StringBuilder();
                    for (Alert a : alerts) s.append(s.length() > 0 ? ", " : "").append(a.event);
                    AppLog.i("Weather", "Active alerts: " + s);
                }
            } catch (Exception e) {
                AppLog.w("Weather", "Alerts check failed (" + e.getClass().getSimpleName() + ")");
            } finally {
                alertsAt = System.currentTimeMillis(); // also after a failure: try again in 5 min
                alertsLoading = false;
                if (onDone != null) main.post(onDone);
            }
        });
    }

    private static List<Alert> fetchAlerts(String lat, String lon) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://api.weather.gov/alerts/active?point=" + lat + "," + lon).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "LiveTVHome/1.0 (Android TV launcher)"); // the NWS asks for one
        c.setRequestProperty("Accept", "application/geo+json");
        try {
            int code = c.getResponseCode();
            if (code == 400 || code == 404) return java.util.Collections.emptyList(); // outside the US
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            String body;
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                body = out.toString("UTF-8");
            }
            JSONArray features = new JSONObject(body).optJSONArray("features");
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            List<Alert> list = new java.util.ArrayList<>();
            for (int i = 0; features != null && i < features.length(); i++) {
                JSONObject pr = features.getJSONObject(i).getJSONObject("properties");
                if (!"Actual".equals(pr.optString("status"))) continue; // skip tests / exercises
                if ("Cancel".equals(pr.optString("messageType"))) continue;
                Alert a = new Alert();
                a.id = pr.optString("id");
                a.event = pr.optString("event");
                a.headline = pr.optString("headline");
                a.area = pr.optString("areaDesc");
                a.description = pr.optString("description").trim();
                a.instruction = pr.isNull("instruction") ? "" : pr.optString("instruction").trim();
                a.severity = pr.optString("severity");
                a.sender = pr.optString("senderName");
                String ends = pr.isNull("ends") ? pr.optString("expires") : pr.optString("ends");
                try { a.endsMs = iso.parse(ends).getTime(); } catch (Exception ignored) { }
                list.add(a);
            }
            java.util.List<String> order = java.util.Arrays.asList("Extreme", "Severe", "Moderate", "Minor", "Unknown");
            list.sort((x, y) -> Integer.compare(order.indexOf(x.severity) < 0 ? 9 : order.indexOf(x.severity),
                    order.indexOf(y.severity) < 0 ? 9 : order.indexOf(y.severity)));
            return list;
        } finally {
            c.disconnect();
        }
    }

    /** One day of the forecast. */
    static final class Day {
        java.util.Date date;
        int code, high, low, rainChance;
    }

    /** 10-day forecast for the saved location. Network call: run off the main thread. */
    static java.util.List<Day> forecast(Prefs p) throws Exception {
        JSONObject d = new JSONObject(HdhrClient.get("https://api.open-meteo.com/v1/forecast?latitude=" + p.weatherLat()
                + "&longitude=" + p.weatherLon()
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=10&timezone=auto" + (p.weatherFahrenheit() ? "&temperature_unit=fahrenheit" : "")))
                .getJSONObject("daily");
        JSONArray t = d.getJSONArray("time"), c = d.getJSONArray("weather_code"),
                hi = d.getJSONArray("temperature_2m_max"), lo = d.getJSONArray("temperature_2m_min"),
                rain = d.optJSONArray("precipitation_probability_max");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        java.util.List<Day> out = new java.util.ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            Day day = new Day();
            day.date = f.parse(t.getString(i));
            day.code = c.optInt(i, -1);
            day.high = (int) Math.round(hi.optDouble(i, 0));
            day.low = (int) Math.round(lo.optDouble(i, 0));
            day.rainChance = rain != null ? rain.optInt(i, 0) : 0;
            out.add(day);
        }
        return out;
    }

    /** One hour of the hourly forecast. */
    static final class Hour {
        long time; // unix seconds, start of the hour
        int code, temp, rainChance;
        boolean day;
    }

    /** The next 24 hours, starting with the current hour. Network call: run off the main thread. */
    static java.util.List<Hour> hourly(Prefs p) throws Exception {
        JSONObject h = new JSONObject(HdhrClient.get("https://api.open-meteo.com/v1/forecast?latitude=" + p.weatherLat()
                + "&longitude=" + p.weatherLon()
                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                + "&forecast_hours=24&timezone=auto&timeformat=unixtime" + (p.weatherFahrenheit() ? "&temperature_unit=fahrenheit" : "")))
                .getJSONObject("hourly");
        JSONArray t = h.getJSONArray("time"), temp = h.getJSONArray("temperature_2m"), code = h.getJSONArray("weather_code");
        JSONArray rain = h.optJSONArray("precipitation_probability"), day = h.optJSONArray("is_day");
        java.util.List<Hour> out = new java.util.ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            Hour x = new Hour();
            x.time = t.getLong(i);
            x.temp = (int) Math.round(temp.optDouble(i, 0));
            x.code = code.optInt(i, -1);
            x.rainChance = rain != null ? rain.optInt(i, 0) : 0;
            x.day = day == null || day.optInt(i, 1) == 1;
            out.add(x);
        }
        return out;
    }

    /** Plain-words description of a WMO weather code. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 65) return "Rain";
        if (code == 66 || code == 67) return "Freezing rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }

    /** WMO weather code → emoji. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1 || code == 2) return day ? "🌤️" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return day ? "🌦️" : "🌧️";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }
}
