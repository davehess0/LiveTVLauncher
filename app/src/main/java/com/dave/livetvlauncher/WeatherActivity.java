package com.dave.livetvlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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
import java.util.concurrent.Future;

/**
 * Weather, opened from the temperature next to the clock. Three tabs (Left / Right on the tabs):
 *  - Hourly (opens first): the next 24 hours.
 *  - 10-day forecast.
 *  - Radar: the last hour of the US NEXRAD radar mosaic, looping. Down moves into the map; then the
 *    arrows move around, OK zooms in, Rewind (or Back) zooms out, and Back at the starting zoom leaves it.
 *
 * Data: forecast from Open-Meteo; radar from the National Weather Service NEXRAD mosaic as served by
 * the Iowa Environmental Mesonet; map tiles from Esri (dark gray canvas). The radar and map are only
 * downloaded while the Radar tab is open.
 */
public final class WeatherActivity extends Activity {
    private static final int TAB_HOURLY = 0, TAB_DAILY = 1, TAB_RADAR = 2;
    private static final int COLS = 6, ROWS = 3;          // picture size in 256 px map tiles
    private static final int W = COLS * 256, H = ROWS * 256;
    private static final int START_ZOOM = 7, MIN_ZOOM = 4, MAX_ZOOM = 10;
    /** Radar frames: now, then 5, 10 … 55 minutes ago (the mosaic's own history). */
    private static final int FRAMES = 12;
    private static final long FRAME_MS = 450, HOLD_LAST_MS = 1800, RENDER_DELAY_MS = 450;
    private static final String IEM = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913";
    private static final String ESRI = "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/";

    private final Handler ui = new Handler(Looper.getMainLooper());
    // Each picture needs a few dozen small map pieces; fetch several at once so it appears quickly.
    private final ExecutorService net = Executors.newFixedThreadPool(8);
    private final ExecutorService renderer = Executors.newSingleThreadExecutor();
    /** Map and radar pieces already fetched this visit (moving back to a spot is instant). */
    private final LruCache<String, Bitmap> tiles = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private Prefs prefs;
    private final TextView[] tabs = new TextView[3];
    private int tab = TAB_HOURLY;
    private LinearLayout hourlyView, forecastView;
    private FrameLayout radarView;
    private ImageView radarImage;
    private ProgressBar radarSpinner;
    private TextView radarTime, radarHint;
    private volatile boolean closed;

    // Radar view: zoom level and the map point (in world pixels at that zoom) at the centre of the picture.
    private int zoom = START_ZOOM;
    private double cx, cy;
    private boolean mapActive;      // the arrows move the map (entered with Down)
    private int generation;          // bumps on every move: older pictures still being built are dropped
    private boolean radarStarted;
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
    private final Runnable render = this::render;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.pageBackground());
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setPadding(Ui.dp(this, Ui.SIDE_DP), Ui.dp(this, 22), Ui.dp(this, Ui.SIDE_DP), Ui.dp(this, 20));

        // Header: place + current conditions.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 22, Ui.TEXT, true);
        title.setText(prefs.weatherPlace().isEmpty() ? "Weather" : prefs.weatherPlace());
        head.addView(title, Ui.lpWeight(0, -2, 1));
        TextView now = Ui.text(this, 22, Ui.TEXT, true);
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
            s.setOnClickListener(v -> showAlert(a));
            root.addView(s, Ui.margins(Ui.lp(-1, -2), 0, Ui.dp(this, 12), 0, 0));
        }

        // Tabs.
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setClipChildren(false);
        tabRow.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
        String[] names = {"Hourly", "10-day forecast", "Radar"};
        for (int i = 0; i < tabs.length; i++) {
            final int which = i;
            tabs[i] = tab(names[i], v -> show(which));
            tabRow.addView(tabs[i], Ui.margins(Ui.lp(-2, -2), 0, 0, Ui.dp(this, 12), 0));
        }
        root.addView(tabRow);

        FrameLayout body = new FrameLayout(this);
        hourlyView = new LinearLayout(this);
        hourlyView.setOrientation(LinearLayout.VERTICAL);
        body.addView(hourlyView, new FrameLayout.LayoutParams(-1, -1));
        forecastView = new LinearLayout(this);
        forecastView.setGravity(Gravity.TOP);
        body.addView(forecastView, new FrameLayout.LayoutParams(-1, -1));
        radarView = buildRadarView();
        body.addView(radarView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, Ui.lpWeight(-1, 0, 1));

        TextView credit = Ui.text(this, 12, Ui.DIM, false);
        credit.setText("Forecast: Open-Meteo · Radar: NWS NEXRAD via Iowa Environmental Mesonet · Map: Esri, HERE, Garmin, "
                + "© OpenStreetMap contributors");
        credit.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(credit);
        setContentView(root);

        show(TAB_HOURLY);
        tabs[TAB_HOURLY].requestFocus();
        loadHourly();
        loadForecast();
    }

    @Override protected void onDestroy() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow();
        renderer.shutdownNow();
        super.onDestroy();
    }

    private void showAlert(Weather.Alert a) {
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
    }

    /** A tab: a glass pill like the Guide's buttons. Focus follows the open tab, so it carries the glow. */
    private TextView tab(String label, View.OnClickListener click) {
        TextView t = Ui.text(this, 16, Ui.TEXT, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 9), Ui.dp(this, 20), Ui.dp(this, 9));
        t.setBackground(Ui.glassWithGlow(this, Ui.dp(this, 20), Ui.CARD));
        t.setFocusable(true);
        t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, has) -> {
            if (has) click.onClick(v);
            Ui.lift(v, has, 1.06f);
        });
        return t;
    }

    private void show(int which) {
        tab = which;
        for (int i = 0; i < tabs.length; i++) tabs[i].setTextColor(i == which ? Ui.TEXT : Ui.SUB);
        hourlyView.setVisibility(which == TAB_HOURLY ? View.VISIBLE : View.GONE);
        forecastView.setVisibility(which == TAB_DAILY ? View.VISIBLE : View.GONE);
        radarView.setVisibility(which == TAB_RADAR ? View.VISIBLE : View.GONE);
        if (which == TAB_RADAR) {
            if (!radarStarted) { radarStarted = true; startRadar(); }
            else if (frames.size() > 1) { ui.removeCallbacks(animate); ui.post(animate); }
        } else {
            ui.removeCallbacks(animate);
            setMapActive(false);
        }
    }

    /**
     * Keys are caught here, before the views: in touch mode (opened by a tap) Android would otherwise
     * swallow the first arrow press just to leave touch mode.
     *  - On the tabs: Left / Right switch tabs; Down on Radar moves into the map.
     *  - In the map: arrows move it, OK / Fast-forward zoom in, Rewind zooms out, Back zooms out
     *    (or, at the starting zoom, goes back to the tabs).
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (mapActive) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (down) pan(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) pan(1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_UP: if (down) pan(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (down) pan(0, 1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    if (down && e.getRepeatCount() == 0) zoomBy(1);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (down && e.getRepeatCount() == 0) zoomBy(-1);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (!down) {
                        if (zoom > START_ZOOM) zoomBy(-1);
                        else setMapActive(false);
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) {
                int next = Math.max(0, Math.min(tabs.length - 1, tab + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                show(next);
                tabs[next].requestFocusFromTouch(); // also leaves touch mode without bouncing back to the first tab
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN && tab == TAB_RADAR && !isAlertFocused()) {
            if (down) setMapActive(true);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean isAlertFocused() {
        View f = getCurrentFocus();
        for (TextView t : tabs) if (t == f) return false;
        return f != null;
    }

    // ---- Hourly ----

    private void loadHourly() {
        TextView loading = Ui.text(this, 18, Ui.SUB, false);
        loading.setText("Loading the hourly forecast…");
        hourlyView.addView(loading);
        net.execute(() -> {
            List<Weather.Hour> hours = null;
            try { hours = Weather.hourly(prefs); } catch (Exception e) {
                AppLog.w("Weather", "Hourly forecast failed (" + e.getClass().getSimpleName() + ")");
            }
            final List<Weather.Hour> h = hours;
            ui.post(() -> { if (!closed) fillHourly(h); });
        });
    }

    /** The next 24 hours as two rows of 12, so it all fits without scrolling. */
    private void fillHourly(List<Weather.Hour> hours) {
        hourlyView.removeAllViews();
        if (hours == null || hours.isEmpty()) {
            TextView t = Ui.text(this, 18, Ui.SUB, false);
            t.setText("Couldn't load the hourly forecast. Check the internet connection and the Weather location in Settings.");
            hourlyView.addView(t);
            return;
        }
        SimpleDateFormat hf = new SimpleDateFormat(prefs.clock24h() ? "HH:mm" : "h a", Locale.getDefault());
        long nowSec = System.currentTimeMillis() / 1000L;
        for (int rowStart = 0; rowStart < hours.size(); rowStart += 12) {
            TextView label = Ui.text(this, 15, Ui.SUB, true);
            label.setText(rowStart == 0 ? "Next 12 hours" : "Then");
            label.setPadding(Ui.dp(this, 2), rowStart == 0 ? 0 : Ui.dp(this, 16), 0, Ui.dp(this, 8));
            hourlyView.addView(label);
            LinearLayout row = new LinearLayout(this);
            int end = Math.min(hours.size(), rowStart + 12);
            for (int i = rowStart; i < end; i++) {
                Weather.Hour h = hours.get(i);
                boolean current = h.time <= nowSec && nowSec < h.time + 3600;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                card.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12));
                card.setBackground(Ui.glass(this, current ? Ui.CARD_FOCUS : Ui.CARD, Ui.dp(this, 16), false));
                TextView time = Ui.text(this, 15, current ? Ui.ACCENT : Ui.TEXT, true);
                Date when = new Date(h.time * 1000L);
                // Midnight: say which day it is.
                boolean midnight = "00".equals(new SimpleDateFormat("HH", Locale.US).format(when));
                time.setText(current ? "Now" : midnight ? new SimpleDateFormat("EEE", Locale.getDefault()).format(when) : hf.format(when));
                card.addView(time);
                TextView icon = Ui.text(this, 28, Ui.TEXT, false);
                icon.setText(Weather.icon(h.code, h.day));
                icon.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
                card.addView(icon);
                TextView temp = Ui.text(this, 20, Ui.TEXT, true);
                temp.setText(h.temp + "°");
                card.addView(temp);
                TextView rain = Ui.text(this, 12, Ui.ACCENT, true);
                rain.setText(h.rainChance >= 10 ? "💧 " + h.rainChance + "%" : " ");
                rain.setPadding(0, Ui.dp(this, 4), 0, 0);
                card.addView(rain);
                row.addView(card, Ui.margins(Ui.lpWeight(0, -2, 1), 0, 0, i == end - 1 ? 0 : Ui.dp(this, 8), 0));
            }
            hourlyView.addView(row, Ui.lp(-1, -2));
        }
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
            card.setPadding(Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20));
            card.setBackground(Ui.glass(this, i == 0 ? Ui.CARD_FOCUS : Ui.CARD, Ui.dp(this, 18), false));
            TextView name = Ui.text(this, 17, i == 0 ? Ui.ACCENT : Ui.TEXT, true);
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
            TextView rain = Ui.text(this, 13, Ui.ACCENT, true);
            rain.setText(day.rainChance >= 10 ? "💧 " + day.rainChance + "%" : " ");
            rain.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(rain);
            forecastView.addView(card, Ui.margins(Ui.lpWeight(0, -2, 1), 0, 0, i == days.size() - 1 ? 0 : Ui.dp(this, 10), 0));
        }
    }

    // ---- Radar ----

    private FrameLayout buildRadarView() {
        // Same treatment as the Guide's preview panel: a glass frame with the map inset and rounded.
        FrameLayout f = new FrameLayout(this);
        int pad = Ui.dp(this, 12);
        f.setPadding(pad, pad, pad, pad);
        f.setBackground(Ui.glass(this, Ui.PANEL, Ui.dp(this, 24), false));
        radarImage = new ImageView(this);
        radarImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        radarImage.setBackgroundColor(0xFF101014);
        Ui.roundCorners(radarImage, Ui.dp(this, 14));
        f.addView(radarImage, new FrameLayout.LayoutParams(-1, -1));
        radarSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        radarSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        f.addView(radarSpinner, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
        radarTime = pill(16);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tl.setMargins(0, Ui.dp(this, 24), Ui.dp(this, 24), 0);
        radarTime.setVisibility(View.GONE); // shown once there is a time to show
        f.addView(radarTime, tl);
        radarHint = pill(14);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.setMargins(0, 0, 0, Ui.dp(this, 24));
        f.addView(radarHint, hl);
        updateHint();
        return f;
    }

    private TextView pill(float sp) {
        TextView t = Ui.text(this, sp, Ui.TEXT, true);
        t.setBackground(Ui.glass(this, Ui.withAlpha(Ui.BG, 0xD9), Ui.dp(this, 16), false));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        return t;
    }

    private void updateHint() {
        radarHint.setText(mapActive
                ? "▲ ▼ ◀ ▶ move   ·   OK zoom in   ·   ⏪ or Back zoom out   ·   zoom " + (zoom - START_ZOOM + 1)
                : "▼  Move and zoom the map");
    }

    /** In the map, the arrows move it (the frame gets the focus glow); out of it, they're back on the tabs. */
    private void setMapActive(boolean on) {
        if (mapActive == on) return;
        mapActive = on;
        radarView.setBackground(on
                ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                        new Ui.Glow(Ui.dp(this, 24), Ui.dp(this, 10)), Ui.glass(this, Ui.CARD_FOCUS, Ui.dp(this, 24), true)})
                : Ui.glass(this, Ui.PANEL, Ui.dp(this, 24), false));
        updateHint();
        if (!on && tab == TAB_RADAR) tabs[TAB_RADAR].requestFocusFromTouch();
    }

    private void startRadar() {
        if (prefs.weatherLat().isEmpty()) return;
        double lat = Double.parseDouble(prefs.weatherLat()), lon = Double.parseDouble(prefs.weatherLon());
        cx = worldX(lon, zoom);
        cy = worldY(lat, zoom);
        render();
    }

    /** Arrows: move by a quarter of the picture. */
    private void pan(int dx, int dy) {
        double n = 256 * Math.pow(2, zoom);
        cx = ((cx + dx * W / 4.0) % n + n) % n;
        cy = Math.max(H / 2.0, Math.min(n - H / 2.0, cy + dy * H / 4.0));
        scheduleRender();
    }

    private void zoomBy(int d) {
        int z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + d));
        if (z == zoom) return;
        double f = Math.pow(2, z - zoom);
        zoom = z;
        cx *= f;
        cy *= f;
        double n = 256 * Math.pow(2, zoom);
        cy = Math.max(H / 2.0, Math.min(n - H / 2.0, cy));
        updateHint();
        scheduleRender();
    }

    /** Several presses in a row build one picture, not one per press. */
    private void scheduleRender() {
        generation++; // anything still being built for the old spot is dropped
        ui.removeCallbacks(animate);
        radarSpinner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(render);
        ui.postDelayed(render, RENDER_DELAY_MS);
    }

    /**
     * Builds the pictures for the current spot: the newest frame first (shown straight away), then the
     * rest of the hour, after which it loops. Map labels go over the radar so places stay readable.
     */
    private void render() {
        final int gen = ++generation, z = zoom;
        final double ccx = cx, ccy = cy;
        final double homeX = worldX(Double.parseDouble(prefs.weatherLon()), z), homeY = worldY(Double.parseDouble(prefs.weatherLat()), z);
        radarSpinner.setVisibility(View.VISIBLE);
        renderer.execute(() -> {
            try {
                long latest = latestRadarTime();
                double left = ccx - W / 2.0, top = ccy - H / 2.0;
                Bitmap base = layer(z, left, top, (x, y) -> ESRI + "World_Dark_Gray_Base/MapServer/tile/" + z + "/" + y + "/" + x, 0xFF101014);
                Bitmap labels = layer(z, left, top, (x, y) -> ESRI + "World_Dark_Gray_Reference/MapServer/tile/" + z + "/" + y + "/" + x, 0);
                if (gen != generation || closed) return;
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                Paint radarPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                radarPaint.setAlpha(225);
                Bitmap[] built = new Bitmap[FRAMES];
                for (int k = 0; k < FRAMES; k++) { // k minutes*5 ago; newest first
                    final String layerName = k == 0 ? "" : String.format(Locale.US, "-m%02dm", k * 5);
                    Bitmap radar = layer(z, left, top, (x, y) -> IEM + layerName + "/" + z + "/" + x + "/" + y + ".png", 0);
                    if (gen != generation || closed) return;
                    Bitmap img = base.copy(Bitmap.Config.ARGB_8888, true);
                    Canvas cv = new Canvas(img);
                    cv.drawBitmap(radar, 0, 0, radarPaint);
                    cv.drawBitmap(labels, 0, 0, null);
                    float hx = (float) (homeX - left), hy = (float) (homeY - top);
                    dot.setColor(0xFFFFFFFF);
                    cv.drawCircle(hx, hy, 11, dot);
                    dot.setColor(0xFF0A84FF);
                    cv.drawCircle(hx, hy, 7, dot);
                    built[k] = img;
                    if (k == 0) {
                        ui.post(() -> {
                            if (gen != generation || closed) return;
                            radarImage.setImageBitmap(img);
                            radarTime.setText(timeLabel(latest, true));
                            radarTime.setVisibility(View.VISIBLE);
                        });
                    }
                }
                ui.post(() -> {
                    if (gen != generation || closed) return;
                    frames.clear();
                    frameTimes.clear();
                    for (int k = FRAMES - 1; k >= 0; k--) { // oldest to newest
                        frames.add(built[k]);
                        frameTimes.add(latest - k * 5 * 60_000L);
                    }
                    frame = frames.size() - 1;
                    radarSpinner.setVisibility(View.GONE);
                    AppLog.i("Weather", "Radar ready: zoom " + z + ", " + frames.size() + " frames");
                    if (tab == TAB_RADAR) { ui.removeCallbacks(animate); ui.postDelayed(animate, HOLD_LAST_MS); }
                });
            } catch (Exception e) {
                AppLog.w("Weather", "Radar failed (" + e.getClass().getSimpleName() + ")");
                ui.post(() -> {
                    if (gen != generation || closed) return;
                    radarSpinner.setVisibility(View.GONE);
                    radarTime.setText("Couldn't load the radar right now");
                    radarTime.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private interface TileUrl { String at(int x, int y); }

    /** One W × H picture of a tile layer with its top-left at (left, top) in world pixels. */
    private Bitmap layer(int z, double left, double top, TileUrl url, int fill) throws Exception {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (fill != 0) c.drawColor(fill);
        int n = 1 << z;
        int tx0 = (int) Math.floor(left / 256), ty0 = (int) Math.floor(top / 256);
        int tx1 = (int) Math.floor((left + W - 1) / 256), ty1 = (int) Math.floor((top + H - 1) / 256);
        List<Future<Bitmap>> got = new ArrayList<>();
        List<int[]> at = new ArrayList<>();
        for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = tx0; tx <= tx1; tx++) {
                final String u = url.at(((tx % n) + n) % n, ty);
                got.add(net.submit(() -> tile(u)));
                at.add(new int[]{(int) Math.round(tx * 256 - left), (int) Math.round(ty * 256 - top)});
            }
        }
        for (int i = 0; i < got.size(); i++) {
            Bitmap t = got.get(i).get();
            if (t != null) c.drawBitmap(t, at.get(i)[0], at.get(i)[1], null);
        }
        return out;
    }

    private long latestAt, latestFetched;

    /** Time of the newest radar picture (the mosaic updates every 5 minutes). */
    private long latestRadarTime() {
        long now = System.currentTimeMillis();
        if (latestAt > 0 && now - latestFetched < 60_000L) return latestAt;
        try {
            JSONObject o = new JSONObject(HdhrClient.get("https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.json"));
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            Date d = f.parse(o.getJSONObject("meta").getString("valid"));
            if (d != null) { latestAt = d.getTime(); latestFetched = now; return latestAt; }
        } catch (Exception ignored) { }
        return now - (now % (5 * 60_000L)); // about right
    }

    private static double worldX(double lon, int z) {
        return (lon + 180) / 360 * 256 * Math.pow(2, z);
    }

    private static double worldY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * 256 * Math.pow(2, z);
    }

    private String timeLabel(long when, boolean latest) {
        String t = new SimpleDateFormat(prefs.clock24h() ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(when));
        return (latest ? "Latest · " : "") + t;
    }

    /** One map / radar piece, kept for the rest of the visit. */
    private Bitmap tile(String url) {
        Bitmap hit = tiles.get(url);
        if (hit != null) return hit;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "LiveTVHome/1.0 (Android TV launcher)");
            try (InputStream in = c.getInputStream()) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) tiles.put(url, b);
                return b;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
