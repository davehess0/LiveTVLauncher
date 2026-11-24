package com.dave.livetvlauncher;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Option C, "Simple": four huge tiles and a clock. Built for the restricted profile —
 * anyone can pick up the remote and get to TV in one press.
 */
public final class SimpleLayout extends HomeLayout {
    private TextView clock, date, status, weather;
    private LinearLayout watch, plexBox, recent;

    public SimpleLayout(LauncherActivity host) { super(host); }

    @Override public View create() {
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(Ui.SIDE_DP), dp(32), dp(Ui.SIDE_DP), dp(32));
        root.setClipChildren(false);
        root.setClipToPadding(false); // focused tiles at the edges grow into the margin instead of being cut off

        LinearLayout clockRow = new LinearLayout(c);
        clockRow.setGravity(Gravity.CENTER);
        clock = Ui.text(c, 56, Ui.TEXT, true);
        clock.setGravity(Gravity.CENTER);
        clockRow.addView(clock);
        weather = weatherLabel(40);
        clockRow.addView(weather, Ui.margins(Ui.lp(-2, -2), dp(20), 0, 0, 0));
        root.addView(clockRow);
        date = Ui.text(c, 18, Ui.SUB, false);
        date.setGravity(Gravity.CENTER);
        date.setPadding(0, dp(8), 0, 0);
        root.addView(date);

        LinearLayout tiles = new LinearLayout(c);
        tiles.setClipChildren(false);
        tiles.setPadding(0, dp(32), 0, 0);
        // Watch TV is the main thing here, so it's twice as wide as the others. With Cameras there are
        // four tiles, which need slightly smaller titles to fit without "…".
        float ts = host.hasCameras() ? 24 : 28;
        watch = sourceTile("Watch TV", "Last channel", Ui.LIVE_RED, "●", ts, v -> host.resumeLastChannel());
        tiles.addView(watch, tileLp(2, false));
        tiles.addView(sourceTile("TV Guide", "What's on now", Ui.ACCENT, "☰", ts, v -> host.showGuide()), tileLp(1, false));
        if (host.hasCameras()) tiles.addView(sourceTile("Cameras", "Live view", Ui.LIVE_RED, "◉", ts,
                v -> host.openCameras()), tileLp(1, false));
        tiles.addView(sourceTile("Apps", "Everything else", Ui.SUB, "▦", ts, v -> host.openApps()), tileLp(1, true));
        root.addView(tiles, Ui.lp(-1, -2));

        LinearLayout lower = new LinearLayout(c);
        lower.setOrientation(LinearLayout.VERTICAL);
        lower.setClipChildren(false);
        recent = recentBox();
        lower.addView(recent);
        lower.addView(section("Streaming"));
        lower.addView(streamingRow(200, 112));
        plexBox = new LinearLayout(c);
        plexBox.setOrientation(LinearLayout.VERTICAL);
        plexBox.setClipChildren(false);
        lower.addView(plexBox);
        root.addView(lower, Ui.lp(-1, -2));

        LinearLayout bottom = new LinearLayout(c);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(36), 0, 0);
        status = Ui.text(c, 13, Ui.DIM, false);
        bottom.addView(status, Ui.lpWeight(0, -2, 1));
        LinearLayout gear = Ui.card(c, LinearLayout.HORIZONTAL, Ui.SUB);
        gear.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView gt = Ui.text(c, 14, Ui.SUB, true);
        gt.setText("⚙  Settings");
        gear.addView(gt);
        gear.setOnClickListener(v -> host.openSettings());
        bottom.addView(gear);
        root.addView(bottom, Ui.lp(-1, -2));
        sv.addView(root);
        return sv;
    }

    @Override public void bindPlex(PlexClient.Library lib) {
        // Simple keeps it to one Plex row: what you were in the middle of.
        plexBox.removeAllViews();
        if (lib == null) return;
        View cw = plexRow(lib.continueWatching, true, 110);
        if (cw != null) {
            plexBox.addView(section("Continue Watching"));
            plexBox.addView(cw);
        }
    }

    private LinearLayout.LayoutParams tileLp(float weight, boolean last) {
        return Ui.margins(Ui.lpWeight(0, dp(200), weight), 0, 0, last ? 0 : dp(24), 0);
    }

    @Override public void bind(List<Channel> channels, String st) {
        showStatus(status, st);
        fillRecent(recent, 200);
        tick();
    }

    @Override public void tick() {
        clock.setText(Ui.clock(prefs.clock24h()));
        showWeather(weather);
        date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date()));
        Channel last = ChannelStore.get().byNumber(prefs.lastChannel());
        if (last != null) setSubtitle(watch, last.number + " · " + nowTitle(last));
        else setSubtitle(watch, ChannelStore.get().isLoaded() ? "Start watching" : "Waiting for tuner…");
        refreshRecent();
    }

    @Override public void showLastWatched() {
        fillRecent(recent, 200);
        tick();
    }

    @Override public void focusDefault() { watch.requestFocus(); }
}
