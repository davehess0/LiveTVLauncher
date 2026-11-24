package com.dave.livetvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** The HOME activity. Hosts whichever layout is selected in Settings. */
public final class LauncherActivity extends Activity implements ChannelStore.Listener {
    private static final long TICK_MS = 30_000L;
    private static final long REFRESH_MS = 15 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout container, content;
    private View ground;
    private GradientDrawable scrim;
    /** The ground color now showing (art-driven looks change it as you move around). */
    private int groundColor;
    private String lookApplied = "";
    private android.animation.ValueAnimator groundFade;

    /** Sets the ground, its glow and the darkening over the art for the chosen look. */
    private void applyLook() {
        lookApplied = Ui.theme();
        ground.setBackground(Ui.pageBackground());
        groundColor = Ui.BG;
        setScrim(Ui.BG);
        if (backdrop.getAlpha() > 0) backdrop.setAlpha(Ui.ART_STRENGTH);
    }

    private void setScrim(int color) {
        int[] a = Ui.SCRIM_ALPHA;
        scrim.setColors(new int[]{Ui.withAlpha(color, a[0]), Ui.withAlpha(color, a[1]), Ui.withAlpha(color, a[2])});
    }

    /** Art-driven looks: fade the ground and the darkening over to a color taken from the art. */
    private void tintGround(int averageColor) {
        if (!Ui.GROUND_FROM_ART) return;
        int to = Ui.groundFromArt(averageColor);
        if (groundFade != null) groundFade.cancel();
        groundFade = android.animation.ValueAnimator.ofArgb(groundColor, to);
        groundFade.setDuration(600);
        groundFade.addUpdateListener(an -> {
            groundColor = (int) an.getAnimatedValue();
            ground.setBackgroundColor(groundColor);
            setScrim(groundColor);
        });
        groundFade.start();
    }
    private android.widget.ImageView backdrop;
    private String backdropUrl;
    private final Runnable applyBackdrop = this::swapBackdrop;
    private HomeLayout current;
    private String builtSignature = "";
    private boolean guideOverlay;
    private Prefs prefs;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (current != null) current.tick();
            ChannelStore.get().refreshIfStale(LauncherActivity.this, REFRESH_MS);
            PlexStore.get().refreshIfStale(LauncherActivity.this, REFRESH_MS);
            Weather.get().refreshIfStale(LauncherActivity.this, weatherLoaded);
            Weather.get().refreshAlertsIfStale(LauncherActivity.this, alertsChanged);
            handler.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable weatherLoaded = () -> { if (current != null) current.tick(); };
    private final Runnable alertsChanged = () -> { if (current != null) current.tick(); maybeShowAlert(); };

    // ---- Weather alert pop-ups ----

    /** Alerts put off with "Later" (until the app restarts). */
    private static final java.util.Set<String> snoozedAlerts = new java.util.HashSet<>();
    private android.app.AlertDialog alertDialog;

    /** Shows the most serious alert that hasn't been dismissed, if any, while the home screen is in front. */
    void maybeShowAlert() {
        if (!resumed || isFinishing() || (alertDialog != null && alertDialog.isShowing())) return;
        Weather.Alert a = null;
        for (Weather.Alert x : Weather.get().undismissed(prefs)) if (!snoozedAlerts.contains(x.id)) { a = x; break; }
        if (a == null) return;
        final Weather.Alert alert = a;
        AppLog.i("Weather", "Showing alert: " + a.event);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 24);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        TextView strip = Ui.text(this, 15, a.severity.equals("Moderate") || a.severity.equals("Minor") ? 0xFF000000 : Ui.TEXT, true);
        strip.setText("  " + a.severity.toUpperCase(java.util.Locale.US) + "  ·  " + a.until(prefs.clock24h()));
        strip.setBackground(Ui.rounded(a.color(), Ui.dp(this, 8), 0, 0));
        strip.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        box.addView(strip, Ui.lp(-2, -2));
        TextView body = Ui.text(this, 17, Ui.TEXT, false);
        body.setSingleLine(false);
        body.setText((a.headline.isEmpty() ? a.event : a.headline) + "\n\n" + a.area);
        body.setPadding(0, Ui.dp(this, 14), 0, 0);
        box.addView(body);
        alertDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("⚠  " + a.event)
                .setView(box)
                .setPositiveButton("Dismiss", (d, w) -> {
                    prefs.dismissAlert(alert.id, alert.endsMs);
                    AppLog.i("Weather", "Dismissed alert: " + alert.event);
                    if (current != null) current.tick();
                    content.post(this::maybeShowAlert); // the next one, if there is one
                })
                .setNeutralButton("Details", (d, w) -> showAlertDetails(alert))
                .setNegativeButton("Later", (d, w) -> snoozedAlerts.add(alert.id))
                .setOnCancelListener(d -> snoozedAlerts.add(alert.id)) // Back = later
                .show();
    }

    private void showAlertDetails(Weather.Alert a) {
        TextView t = Ui.text(this, 15, Ui.TEXT, false);
        t.setSingleLine(false);
        t.setText(a.description + (a.instruction.isEmpty() ? "" : "\n\nWHAT TO DO\n" + a.instruction)
                + (a.sender.isEmpty() ? "" : "\n\n— " + a.sender));
        int p = Ui.dp(this, 24);
        t.setPadding(p, Ui.dp(this, 8), p, p);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFocusable(true); // Up / Down scroll with the remote
        sv.addView(t);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("⚠  " + a.event + "  " + a.until(prefs.clock24h()))
                .setView(sv)
                .setPositiveButton("Dismiss", (x, w) -> {
                    prefs.dismissAlert(a.id, a.endsMs);
                    if (current != null) current.tick();
                    content.post(this::maybeShowAlert);
                })
                .setNegativeButton("Close", (x, w) -> snoozedAlerts.add(a.id))
                .setOnCancelListener(x -> snoozedAlerts.add(a.id))
                .show();
        alertDialog = d;
        sv.post(sv::requestFocus);
        if (d.getWindow() != null) d.getWindow().setLayout(Ui.dp(this, 900), Ui.dp(this, 520));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        container = new FrameLayout(this);
        // The ground (and its corner glow) follow the chosen look; see applyLook().
        ground = new View(this);
        container.addView(ground, new FrameLayout.LayoutParams(-1, -1));
        // Apple TV-style backdrop: art for whatever is focused, soft and dimmed behind everything.
        backdrop = new android.widget.ImageView(this);
        backdrop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        container.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0, 0});
        View scrimView = new View(this);
        scrimView.setBackground(scrim);
        container.addView(scrimView, new FrameLayout.LayoutParams(-1, -1));
        applyLook();
        content = new FrameLayout(this);
        container.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        ChannelStore.get().addListener(this);
        PlexStore.get().addListener(plexListener);
        rebuild();
        ChannelStore.get().refresh(this);
        PlexStore.get().refresh(this);
    }

    @Override protected void onDestroy() {
        ChannelStore.get().removeListener(this);
        PlexStore.get().removeListener(plexListener);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!lookApplied.equals(Ui.theme())) applyLook(); // the look was changed in Settings
        maybeStartOnLastChannel(); // channels may already be loaded
        if (!signature().equals(builtSignature)) rebuild();
        else if (current != null) current.tick();
        // Back from the player: show the channel that was playing last (it may have been changed
        // inside the player with the channel list, number keys or CH+/−).
        if (returningFromPlayer && current != null) {
            returningFromPlayer = false;
            HomeLayout l = current;
            content.post(l::showLastWatched);
        }
        if (current != null) current.onShown();
        ChannelStore.get().refreshIfStale(this, REFRESH_MS);
        // Plex refreshes more often so Continue Watching reflects what you just watched.
        PlexStore.get().refreshIfStale(this, 60_000L);
        Weather.get().refreshIfStale(this, weatherLoaded);
        Weather.get().refreshAlertsIfStale(this, alertsChanged);
        content.post(this::maybeShowAlert); // an alert that arrived while another screen was open
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
    }

    @Override protected void onPause() {
        resumed = false;
        if (current != null) current.onHidden();
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    /** Pressing Home while already home: leave the guide overlay and go back to the top. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (guideOverlay) { guideOverlay = false; rebuild(); }
        else if (current != null) current.focusDefault();
    }

    @Override public void onBackPressed() {
        if (current != null && current.onBack()) return;
        if (guideOverlay) { guideOverlay = false; rebuild(); }
        // A home screen has nowhere to go back to, so otherwise Back does nothing.
    }

    /** The once-per-start decision (app launch or reboot) has been made. */
    private static boolean startupHandled;
    private static final long PROCESS_START = android.os.SystemClock.elapsedRealtime();
    private boolean resumed;

    /**
     * Like a cable box: when the app starts, go straight to the last channel you were watching.
     * Only once per start, only if channels load within a minute, and only if you haven't
     * already moved on to another screen.
     */
    private void maybeStartOnLastChannel() {
        if (startupHandled || ChannelStore.get().all().isEmpty()) return;
        startupHandled = true;
        if (!prefs.startOnLastChannel() || !resumed || guideOverlay) return;
        if (android.os.SystemClock.elapsedRealtime() - PROCESS_START > 60_000L) return;
        Channel ch = ChannelStore.get().byNumber(prefs.lastChannel());
        if (ch == null) return; // never watched anything yet: stay on the home screen
        AppLog.i("App", "Starting on the last channel: " + ch.number + " " + ch.name);
        play(ch);
    }

    @Override public void onChannelsChanged() {
        maybeStartOnLastChannel();
        if (current == null) return;
        View focused = getCurrentFocus();
        boolean hadFocus = focused != null;
        current.bind(visibleChannels(), ChannelStore.get().status());
        if (!hadFocus || getCurrentFocus() == null) current.focusDefault();
    }

    private final ChannelStore.Listener plexListener = () -> {
        if (current == null) return;
        boolean hadFocus = getCurrentFocus() != null;
        current.bindPlex(PlexStore.get().library());
        if (!hadFocus || getCurrentFocus() == null) current.focusDefault();
    };

    private String signature() {
        return prefs.pinnedApps() + "|" + Ui.theme() + "|" + prefs.layout() + "|" + prefs.showAppRow() + "|" + prefs.favoritesOnly() + "|" + prefs.clock24h() + "|" + guideOverlay
                + "|" + prefs.cameras().isEmpty() + "|" + prefs.livePreview();
    }

    private void rebuild() {
        if (current != null) current.onHidden(); // the old layout's preview (if any) lets go of its tuner
        content.removeAllViews();
        if (guideOverlay) current = new GuideLayout(this, true);
        else switch (prefs.layout()) {
            case Prefs.LAYOUT_WALL: current = new WallLayout(this); break;
            case Prefs.LAYOUT_SIMPLE: current = new SimpleLayout(this); break;
            default: current = new GuideLayout(this, false);
        }
        content.addView(current.create(), new FrameLayout.LayoutParams(-1, -1));
        current.bind(visibleChannels(), ChannelStore.get().status());
        current.bindPlex(PlexStore.get().library());
        builtSignature = signature();
        content.post(() -> current.focusDefault());
    }

    /** Change the background art. Debounced so scrolling quickly doesn't thrash the network. */
    void setBackdrop(String url) {
        if (url == null || url.equals(backdropUrl)) return;
        backdropUrl = url;
        handler.removeCallbacks(applyBackdrop);
        handler.postDelayed(applyBackdrop, 300);
    }

    private void swapBackdrop() {
        final String url = backdropUrl;
        backdrop.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            // Full-size art is shown sharp; small pictures become a smooth blur rather than a pixelated stretch.
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            ImageLoader.loadBackdrop(backdrop, url, Math.max(dm.widthPixels, dm.heightPixels), this::tintGround);
            backdrop.animate().alpha(Ui.ART_STRENGTH).setDuration(420).start();
        }).start();
    }

    List<Channel> visibleChannels() {
        return ChannelStore.get().visible(prefs);
    }

    // ---- actions used by the layouts --------------------------------------------------------

    void play(Channel ch) {
        if (current != null) current.onHidden(); // free the preview's tuner before the player takes one
        returningFromPlayer = true;
        Apps.play(this, ch, visibleChannels());
    }

    /** Set when a channel is opened, so coming back lands on whatever was watched last. */
    private boolean returningFromPlayer;

    void resumeLastChannel() {
        List<Channel> list = visibleChannels();
        Channel ch = ChannelStore.get().byNumber(prefs.lastChannel());
        if (ch == null && !list.isEmpty()) ch = list.get(0);
        if (ch != null) { play(ch); return; }
        Toast.makeText(this, "Still looking for your HDHomeRun — opening the HDHomeRun app.", Toast.LENGTH_LONG).show();
        Apps.launch(this, Apps.HDHR);
    }

    void showGuide() {
        guideOverlay = true;
        rebuild();
    }

    void openApps() {
        startActivity(new Intent(this, AppsActivity.class));
    }

    void openWeather() {
        if (prefs.weatherLat().isEmpty()) {
            Toast.makeText(this, "Set your ZIP code or city in Settings → Weather first.", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, WeatherActivity.class));
    }

    void openCameras() {
        startActivity(new Intent(this, CamerasActivity.class));
    }

    boolean hasCameras() {
        return !prefs.cameras().isEmpty();
    }

    /** Launcher settings, behind the optional PIN so the restricted profile can't change them. */
    void openSettings() {
        String pin = prefs.pin();
        if (pin.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Enter launcher PIN")
                .setView(in)
                .setPositiveButton("OK", (d, w) -> {
                    if (pin.equals(in.getText().toString())) startActivity(new Intent(this, SettingsActivity.class));
                    else Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
