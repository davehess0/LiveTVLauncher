package com.dave.livetvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
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
        if (code >= 51 && code <= 57) return "🌦️";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }
}
