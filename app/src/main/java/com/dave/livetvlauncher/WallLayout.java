package com.dave.livetvlauncher;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Option B, "Channel Wall": big source tiles across the top (HDHomeRun, Plex Live TV, resume),
 * then a wall of channel cards showing what's on right now, then your apps.
 */
public final class WallLayout extends HomeLayout {
    private static final int COLUMNS = 5;

    private TextView status, clock, weather;
    private LinearLayout resumeTile, plexTile, hdhrTile;
    private LinearLayout grid, plexBox, recent;
    private final List<Card> cards = new ArrayList<>();

    private static final class Card {
        Channel ch;
        LinearLayout view;
        TextView title;
        Ui.ProgressLine progress;
    }

    public WallLayout(LauncherActivity host) { super(host); }

    @Override public View create() {
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(28), dp(48), dp(40));
        root.setClipChildren(false);

        status = Ui.text(c, 13, Ui.SUB, false);
        clock = Ui.text(c, 22, Ui.TEXT, true);
        weather = weatherLabel(22);
        root.addView(header("Home", status, weather, clock));

        LinearLayout top = new LinearLayout(c);
        top.setClipChildren(false);
        top.setPadding(0, dp(20), 0, dp(8));
        resumeTile = sourceTile("Resume", "Last channel", Ui.LIVE_RED, "●", 22, v -> host.resumeLastChannel());
        hdhrTile = sourceTile("HDHomeRun", Apps.installed(c, Apps.HDHR) ? "Live TV & DVR" : "Not available in this profile",
                Ui.ACCENT, "▶", 22, v -> Apps.launch(host, Apps.HDHR));
        plexTile = sourceTile("Plex Live TV", Apps.installed(c, Apps.PLEX) ? "Live TV & DVR" : "Not available in this profile",
                Ui.PLEX, "▶", 22, v -> Apps.launch(host, Apps.PLEX));
        top.addView(resumeTile, Ui.margins(Ui.lpWeight(0, dp(150), 1.3f), 0, 0, dp(18), 0));
        top.addView(hdhrTile, Ui.margins(Ui.lpWeight(0, dp(150), 1), 0, 0, dp(18), 0));
        top.addView(plexTile, Ui.margins(Ui.lpWeight(0, dp(150), 1), 0, 0, dp(18), 0));
        if (host.hasCameras()) top.addView(sourceTile("Cameras", "Live view", Ui.LIVE_RED, "◉", 22, v -> host.openCameras()),
                Ui.margins(Ui.lpWeight(0, dp(150), 1), 0, 0, dp(18), 0));
        top.addView(sourceTile("Settings", null, Ui.SUB, "⚙", 18, v -> host.openSettings()),
                Ui.lp(dp(130), dp(150)));
        root.addView(top);

        recent = recentBox();
        root.addView(recent);

        root.addView(section("Streaming"));
        root.addView(streamingRow(176, 99));

        plexBox = new LinearLayout(c);
        plexBox.setOrientation(LinearLayout.VERTICAL);
        plexBox.setClipChildren(false);
        root.addView(plexBox);

        root.addView(section("On Now"));
        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        root.addView(grid);

        if (prefs.showAppRow()) {
            LinearLayout appsHead = new LinearLayout(c);
            appsHead.setGravity(Gravity.CENTER_VERTICAL);
            appsHead.addView(section("All Apps"), Ui.lpWeight(0, -2, 1));
            root.addView(appsHead);
            root.addView(appRow(12));
            LinearLayout more = Ui.card(c, LinearLayout.HORIZONTAL, Ui.SUB);
            more.setPadding(dp(16), dp(8), dp(16), dp(8));
            TextView mt = Ui.text(c, 14, Ui.TEXT, true);
            mt.setText("All apps  ›");
            more.addView(mt);
            more.setOnClickListener(v -> host.openApps());
            root.addView(more, Ui.margins(Ui.lp(-2, -2), 0, dp(6), 0, 0));
        }
        sv.addView(root);
        return sv;
    }

    @Override public void bind(List<Channel> channels, String st) {
        showStatus(status, st);
        clock.setText(Ui.clock(prefs.clock24h()));
        showWeather(weather);
        grid.removeAllViews();
        cards.clear();
        if (channels.isEmpty()) {
            TextView empty = Ui.text(c, 18, Ui.SUB, false);
            empty.setSingleLine(false);
            empty.setText(st);
            grid.addView(empty);
        } else {
            LinearLayout row = null;
            for (int i = 0; i < channels.size(); i++) {
                if (i % COLUMNS == 0) {
                    row = new LinearLayout(c);
                    row.setClipChildren(false);
                    grid.addView(row, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, dp(18)));
                }
                Card card = makeCard(channels.get(i));
                row.addView(card.view, Ui.margins(Ui.lpWeight(0, dp(176), 1), 0, 0,
                        (i % COLUMNS == COLUMNS - 1) ? 0 : dp(18), 0));
            }
            // Pad the last row so cards keep the same width.
            int rem = channels.size() % COLUMNS;
            if (rem != 0 && row != null) {
                for (int i = rem; i < COLUMNS; i++) {
                    View spacer = new View(c);
                    row.addView(spacer, Ui.margins(Ui.lpWeight(0, 1, 1), 0, 0, i == COLUMNS - 1 ? 0 : dp(18), 0));
                }
            }
        }
        updateResume();
        fillRecent(recent, 176);
    }

    @Override public void showLastWatched() {
        fillRecent(recent, 176);
        tick();
    }

    private Card makeCard(Channel ch) {
        Card k = new Card();
        k.ch = ch;
        LinearLayout v = Ui.card(c, LinearLayout.VERTICAL, Ui.ACCENT);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        View lg = logo(ch, 10, 74, 26);
        v.addView(lg, Ui.lp(-1, dp(74)));
        TextView nm = Ui.text(c, 13, Ui.ACCENT, true);
        nm.setText(ch.number + "  " + ch.name);
        nm.setPadding(0, dp(10), 0, 0);
        v.addView(nm);
        k.title = Ui.text(c, 15, Ui.TEXT, true);
        k.title.setPadding(0, dp(4), 0, 0);
        v.addView(k.title);
        k.progress = new Ui.ProgressLine(c, Ui.ACCENT);
        v.addView(k.progress, Ui.margins(Ui.lp(-1, dp(4)), 0, dp(10), 0, 0));
        v.setOnClickListener(x -> host.play(ch));
        v.setOnFocusChangeListener((x, has) -> {
            if (has) {
                Ui.adoptFocusColors(x);
                Channel.Airing a = ch.now(now());
                host.setBackdrop(a != null && a.imageUrl != null ? a.imageUrl : ch.logoUrl);
            }
            Ui.lift(x, has, 1.06f);
        });
        k.view = v;
        fill(k);
        cards.add(k);
        return k;
    }

    private void fill(Card k) {
        long now = now();
        Channel.Airing a = k.ch.now(now);
        k.title.setText(a != null ? a.title : k.ch.name);
        k.progress.setProgress(a != null ? a.progress(now) : 0);
        k.progress.setVisibility(a != null ? View.VISIBLE : View.INVISIBLE);
    }

    private void updateResume() {
        Channel last = ChannelStore.get().byNumber(prefs.lastChannel());
        if (last != null) setSubtitle(resumeTile, last.number + " " + last.name + " · " + nowTitle(last));
        else setSubtitle(resumeTile, ChannelStore.get().isLoaded() ? "Start watching" : "Waiting for tuner…");
    }

    @Override public void bindPlex(PlexClient.Library lib) {
        fillPlexRows(plexBox, lib, 112);
    }

    @Override public void tick() {
        clock.setText(Ui.clock(prefs.clock24h()));
        showWeather(weather);
        for (Card k : cards) fill(k);
        updateResume();
        refreshRecent();
    }

    @Override public void focusDefault() {
        resumeTile.requestFocus();
    }
}
