package com.dave.livetvlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather, opened from the temperature next to the clock: a 10-day forecast and an animated radar
 * loop (last ~2 hours) around the saved location. Left / Right (or tap) switch tabs; Back closes.
 *
 * Data: forecast from Open-Meteo; radar from RainViewer; map tiles from Esri (dark gray canvas).
 * The radar and map are only downloaded while the Radar tab is open.
 */
public final class WeatherActivity extends Activity {
    private static final int RADAR_ZOOM = 7;     // RainViewer's free tiles go to zoom 7: a regional view
    private static final int COLS = 5, ROWS = 3;  // tiles across × down (256 px each)
    private static final long FRAME_MS = 500, HOLD_LAST_MS = 1800;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private Prefs prefs;
    private TextView tabForecast, tabRadar, radarTime;
    private LinearLayout forecastView;
    private FrameLayout radarView;
    private ImageView radarImage;
    private ProgressBar radarSpinner;
    private boolean radarLoaded;
    private volatile boolean closed;
    private final List<Bitmap> frames = new ArrayList<>();
    private final List<Long> frameTimes = new ArrayList<>();
    private int frame;
    private final Runnable animate = new Runnable() {
        @Override public void run() {
            if (frames.isEmpty()) return;
            frame = (frame + 1) % frames.size();
            radarImage.setImageBitmap(frames.get(frame));
            radarTime.setText(timeLabel(frameTimes.get(frame), frame == frames.size() - 1));
            ui.postDelayed(this, frame == frames.size() - 1 ? HOLD_LAST_MS : FRAME_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 40);
        root.setPadding(p, Ui.dp(this, 28), p, Ui.dp(this, 24));

        // Header: place + current conditions.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 26, Ui.TEXT, true);
        title.setText(prefs.weatherPlace().isEmpty() ? "Weather" : prefs.weatherPlace());
        head.addView(title, Ui.lpWeight(0, -2, 1));
        TextView now = Ui.text(this, 26, Ui.TEXT, true);
        now.setText(Weather.get().text(prefs));
        head.addView(now);
        root.addView(head);

        // Active weather alerts (any severity): one coloured strip each; select one for the full text.
        for (Weather.Alert a : Weather.get().alerts(prefs)) {
            boolean light = a.severity.equals("Moderate") || a.severity.equals("Minor");
            TextView s = Ui.text(this, 16, light ? 0xFF000000 : Ui.TEXT, true);
            s.setText("⚠  " + a.event + "   " + a.until(prefs.clock24h()) + "   ·   OK for details");
            s.setBackground(Ui.rounded(a.color(), Ui.dp(this, 12), 0, 0));
            s.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            s.setFocusable(true);
            s.setClickable(true);
            s.setOnClickListener(v -> {
                TextView t = Ui.text(this, 15, Ui.TEXT, false);
                t.setSingleLine(false);
                t.setText(a.headline + "\n\n" + a.description + (a.instruction.isEmpty() ? "" : "\n\nWHAT TO DO\n" + a.instruction)
                        + "\n\nAreas: " + a.area);
                int pp = Ui.dp(this, 24);
                t.setPadding(pp, Ui.dp(this, 8), pp, pp);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.setFocusable(true);
                sv.addView(t);
                android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                        .setTitle("⚠  " + a.event).setView(sv).setPositiveButton("Close", null).show();
                sv.post(sv::requestFocus);
                if (d.getWindow() != null) d.getWindow().setLayout(Ui.dp(this, 900), Ui.dp(this, 520));
            });
            root.addView(s, Ui.margins(Ui.lp(-1, -2), 0, Ui.dp(this, 12), 0, 0));
        }

        // Tabs.
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
        tabForecast = tab("10-day forecast", v -> show(false));
        tabRadar = tab("Radar", v -> show(true));
        tabs.addView(tabForecast, Ui.margins(Ui.lp(-2, -2), 0, 0, Ui.dp(this, 12), 0));
        tabs.addView(tabRadar);
        root.addView(tabs);

        FrameLayout body = new FrameLayout(this);
        forecastView = new LinearLayout(this);
        forecastView.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(forecastView, new FrameLayout.LayoutParams(-1, -1));
        radarView = buildRadarView();
        radarView.setVisibility(View.GONE);
        body.addView(radarView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, Ui.lpWeight(-1, 0, 1));

        TextView credit = Ui.text(this, 12, Ui.DIM, false);
        credit.setText("Forecast: Open-Meteo · Radar: RainViewer · Map: Esri, HERE, Garmin, © OpenStreetMap contributors   ·   ◀ ▶ switch tabs · Back to close");
        credit.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(credit);
        setContentView(root);

        show(false);
        tabForecast.requestFocus();
        loadForecast();
    }

    @Override protected void onDestroy() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow();
        super.onDestroy();
    }

    private TextView tab(String label, View.OnClickListener click) {
        TextView t = Ui.text(this, 17, Ui.TEXT, true);
        t.setText(label);
        t.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false); // the white pill already shows which tab is on
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, has) -> { if (has) click.onClick(v); });
        return t;
    }

    private void show(boolean radar) {
        styleTab(tabForecast, !radar);
        styleTab(tabRadar, radar);
        forecastView.setVisibility(radar ? View.GONE : View.VISIBLE);
        radarView.setVisibility(radar ? View.VISIBLE : View.GONE);
        if (radar && !radarLoaded) { radarLoaded = true; loadRadar(); }
        if (radar && !frames.isEmpty()) { ui.removeCallbacks(animate); ui.post(animate); }
        if (!radar) ui.removeCallbacks(animate);
    }

    private void styleTab(TextView t, boolean on) {
        t.setBackground(Ui.rounded(on ? Ui.TEXT : 0x33FFFFFF, Ui.dp(this, 22), 0, 0));
        t.setTextColor(on ? 0xFF000000 : Ui.TEXT);
    }

    /**
     * Left / Right switch tabs. Caught here, before the views: in touch mode (opened by a tap)
     * Android would otherwise swallow the first arrow press just to leave touch mode.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                boolean radar = k == KeyEvent.KEYCODE_DPAD_RIGHT;
                show(radar);
                (radar ? tabRadar : tabForecast).requestFocus();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- 10-day forecast ----

    private void loadForecast() {
        TextView loading = Ui.text(this, 18, Ui.SUB, false);
        loading.setText("Loading the forecast…");
        forecastView.addView(loading);
        net.execute(() -> {
            List<Weather.Day> days = null;
            try { days = Weather.forecast(prefs); } catch (Exception e) {
                AppLog.w("Weather", "Forecast failed (" + e.getClass().getSimpleName() + ")");
            }
            final List<Weather.Day> d = days;
            ui.post(() -> { if (!closed) fillForecast(d); });
        });
    }

    private void fillForecast(List<Weather.Day> days) {
        forecastView.removeAllViews();
        if (days == null || days.isEmpty()) {
            TextView t = Ui.text(this, 18, Ui.SUB, false);
            t.setText("Couldn't load the forecast. Check the internet connection and the Weather location in Settings.");
            forecastView.addView(t);
            return;
        }
        SimpleDateFormat dow = new SimpleDateFormat("EEE", Locale.getDefault()), md = new SimpleDateFormat("MMM d", Locale.getDefault());
        for (int i = 0; i < days.size(); i++) {
            Weather.Day day = days.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18));
            card.setBackground(Ui.rounded(i == 0 ? 0x33FFFFFF : 0x1AFFFFFF, Ui.dp(this, 18), 0, 0));
            TextView name = Ui.text(this, 17, Ui.TEXT, true);
            name.setText(i == 0 ? "Today" : dow.format(day.date));
            card.addView(name);
            TextView date = Ui.text(this, 12, Ui.SUB, false);
            date.setText(md.format(day.date));
            card.addView(date);
            TextView icon = Ui.text(this, 38, Ui.TEXT, false);
            icon.setText(Weather.icon(day.code, true));
            icon.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
            card.addView(icon);
            TextView hi = Ui.text(this, 22, Ui.TEXT, true);
            hi.setText(day.high + "°");
            card.addView(hi);
            TextView lo = Ui.text(this, 17, Ui.SUB, false);
            lo.setText(day.low + "°");
            card.addView(lo);
            TextView what = Ui.text(this, 12, Ui.SUB, false);
            what.setText(Weather.describe(day.code));
            what.setGravity(Gravity.CENTER);
            what.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(what);
            TextView rain = Ui.text(this, 13, 0xFF64B5F6, true);
            rain.setText(day.rainChance >= 10 ? "💧 " + day.rainChance + "%" : " ");
            rain.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(rain);
            forecastView.addView(card, Ui.margins(Ui.lpWeight(0, -2, 1), 0, 0, i == days.size() - 1 ? 0 : Ui.dp(this, 10), 0));
        }
    }

    // ---- Radar ----

    private FrameLayout buildRadarView() {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.rounded(0xFF101014, Ui.dp(this, 18), 0, 0));
        f.setClipToOutline(true);
        radarImage = new ImageView(this);
        radarImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(radarImage, new FrameLayout.LayoutParams(-1, -1));
        radarSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        radarSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        f.addView(radarSpinner, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
        radarTime = Ui.text(this, 16, Ui.TEXT, true);
        radarTime.setShadowLayer(6, 0, 1, 0xFF000000);
        radarTime.setBackground(Ui.rounded(0x99000000, Ui.dp(this, 10), 0, 0));
        radarTime.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tl.setMargins(0, Ui.dp(this, 14), Ui.dp(this, 14), 0);
        radarTime.setVisibility(View.GONE); // shown once there is a time to show
        f.addView(radarTime, tl);
        return f;
    }

    /** Builds one picture per radar frame: dark map + radar overlay + a dot for home. */
    private void loadRadar() {
        if (prefs.weatherLat().isEmpty()) return;
        double lat = Double.parseDouble(prefs.weatherLat()), lon = Double.parseDouble(prefs.weatherLon());
        net.execute(() -> {
            try {
                JSONObject maps = new JSONObject(HdhrClient.get("https://api.rainviewer.com/public/weather-maps.json"));
                String host = maps.getString("host");
                JSONArray past = maps.getJSONObject("radar").getJSONArray("past");

                // Which tiles cover a COLS × ROWS window centred on home.
                double n = Math.pow(2, RADAR_ZOOM);
                double fx = (lon + 180) / 360 * n;
                double latR = Math.toRadians(lat);
                double fy = (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n;
                // One extra tile on each side, shifted so home lands exactly in the middle of the picture.
                final int TC = COLS + 2, TR = ROWS + 2;
                int x0 = (int) Math.floor(fx) - COLS / 2 - 1, y0 = (int) Math.floor(fy) - ROWS / 2 - 1;
                int w = COLS * 256, h = ROWS * 256;
                final float dx = (float) (w / 2.0 - (fx - x0) * 256), dy = (float) (h / 2.0 - (fy - y0) * 256);
                float homeX = w / 2f, homeY = h / 2f;

                // Base map once.
                Bitmap base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas bc = new Canvas(base);
                bc.drawColor(0xFF101014);
                // Esri's dark gray canvas: the map under the radar, its city labels on top of it.
                String esri = "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/";
                Bitmap labels = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas lc = new Canvas(labels);
                List<java.util.concurrent.Future<Bitmap>> tiles = new ArrayList<>(), labelTiles = new ArrayList<>();
                for (int r = 0; r < TR; r++) for (int c = 0; c < TC; c++) {
                    final int tx = x0 + c, ty = y0 + r;
                    tiles.add(net.submit(() -> fetch(esri + "World_Dark_Gray_Base/MapServer/tile/" + RADAR_ZOOM + "/" + ty + "/" + tx)));
                    labelTiles.add(net.submit(() -> fetch(esri + "World_Dark_Gray_Reference/MapServer/tile/" + RADAR_ZOOM + "/" + ty + "/" + tx)));
                }
                for (int i = 0; i < tiles.size(); i++) {
                    Bitmap t = tiles.get(i).get();
                    if (t != null) bc.drawBitmap(t, (i % TC) * 256 + dx, (i / TC) * 256 + dy, null);
                    Bitmap l = labelTiles.get(i).get();
                    if (l != null) lc.drawBitmap(l, (i % TC) * 256 + dx, (i / TC) * 256 + dy, null);
                }

                // Radar frames, newest last; show each as it's ready.
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int k = 0; k < past.length() && !closed; k++) {
                    JSONObject fr = past.getJSONObject(k);
                    String path = fr.getString("path");
                    Bitmap img = base.copy(Bitmap.Config.ARGB_8888, true);
                    Canvas cv = new Canvas(img);
                    List<java.util.concurrent.Future<Bitmap>> rt = new ArrayList<>();
                    for (int r = 0; r < TR; r++) for (int c = 0; c < TC; c++) {
                        final int tx = x0 + c, ty = y0 + r;
                        rt.add(net.submit(() -> fetch(host + path + "/256/" + RADAR_ZOOM + "/" + tx + "/" + ty + "/2/1_1.png")));
                    }
                    Paint alpha = new Paint();
                    alpha.setAlpha(215);
                    for (int i = 0; i < rt.size(); i++) {
                        Bitmap t = rt.get(i).get();
                        if (t != null) cv.drawBitmap(t, (i % TC) * 256 + dx, (i / TC) * 256 + dy, alpha);
                    }
                    cv.drawBitmap(labels, 0, 0, null); // city names stay readable over the rain
                    dot.setColor(0xFFFFFFFF);
                    cv.drawCircle(homeX, homeY, 11, dot);
                    dot.setColor(0xFF0A84FF);
                    cv.drawCircle(homeX, homeY, 7, dot);
                    long when = fr.getLong("time") * 1000L;
                    final boolean first = k == 0;
                    ui.post(() -> {
                        if (closed) return;
                        frames.add(img);
                        frameTimes.add(when);
                        if (first) {
                            radarSpinner.setVisibility(View.GONE);
                            radarImage.setImageBitmap(img);
                            radarTime.setText(timeLabel(when, false));
                            radarTime.setVisibility(View.VISIBLE);
                        }
                    });
                }
                ui.post(() -> {
                    if (closed || frames.isEmpty()) return;
                    AppLog.i("Weather", "Radar loaded: " + frames.size() + " frames");
                    frame = frames.size() - 1;
                    if (radarView.getVisibility() == View.VISIBLE) { ui.removeCallbacks(animate); ui.post(animate); }
                });
            } catch (Exception e) {
                AppLog.w("Weather", "Radar failed (" + e.getClass().getSimpleName() + ")");
                ui.post(() -> {
                    radarSpinner.setVisibility(View.GONE);
                    radarTime.setText("Couldn't load the radar right now");
                    radarTime.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private String timeLabel(long when, boolean latest) {
        String t = new SimpleDateFormat(prefs.clock24h() ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(when));
        return (latest ? "Latest · " : "") + t;
    }

    private static Bitmap fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "LiveTVHome/1.0 (Android TV launcher)");
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
