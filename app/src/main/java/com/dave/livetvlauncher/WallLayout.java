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
    private LinearLayout resumeTile, hdhrTile;
    private LinearLayout grid, plexBox, recent;
    private final List<Card> cards = new ArrayList<>();

    private static final class Card {
        Channel ch;
        LinearLayout view;
        TextView title, nm;
        Ui.ProgressLine progress;
    }

    public WallLayout(LauncherActivity host) { super(host); }

    @Override public View create() {
        ScrollView sv = Ui.pageScroller(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(Ui.SIDE_DP), dp(28), dp(Ui.SIDE_DP), dp(Ui.SCROLL_CLEAR_DP + 8));
        root.setClipChildren(false);
        root.setClipToPadding(false); // focused tiles at the edges grow into the margin instead of being cut off

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
        top.addView(resumeTile, Ui.margins(Ui.lpWeight(0, dp(150), 1.3f), 0, 0, dp(24), 0));
        top.addView(sourceTile("Full Guide", "What's on later", Ui.ACCENT, "☰", 22, v -> host.openGrid()),
                Ui.margins(Ui.lpWeight(0, dp(150), 1), 0, 0, dp(24), 0));
        top.addView(hdhrTile, Ui.margins(Ui.lpWeight(0, dp(150), 1), 0, 0, dp(24), 0));
        if (host.hasCameras()) top.addView(sourceTile("Cameras", "Live view", Ui.LIVE_RED, "◉", 22, v -> host.openCameras()),
                Ui.margins(Ui.lpWeight(0, dp(150), 1), 0, 0, dp(24), 0));
        top.addView(sourceTile("Settings", null, Ui.SUB, "⚙", 18, v -> host.openSettings()),
                Ui.lp(dp(130), dp(150)));
        keepInRow(top);
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
            // Heading with a "See all" button on its right, lined up with the end of the page.
            LinearLayout appsHead = new LinearLayout(c);
            appsHead.setGravity(Gravity.BOTTOM);
            appsHead.setClipChildren(false);
            appsHead.addView(section("All Apps"), Ui.lpWeight(0, -2, 1));
            LinearLayout more = Ui.card(c, LinearLayout.HORIZONTAL, Ui.SUB);
            more.setGravity(Gravity.CENTER_VERTICAL);
            more.setPadding(dp(18), dp(9), dp(18), dp(9));
            TextView mt = Ui.text(c, 15, Ui.TEXT, true);
            mt.setText("See all  ›");
            more.addView(mt);
            more.setOnClickListener(v -> host.openApps());
            appsHead.addView(more, Ui.margins(Ui.lp(-2, -2), 0, 0, 0, dp(8)));
            root.addView(appsHead);
            root.addView(appRow(12));
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
        k.nm = Ui.text(c, 13, Ui.ACCENT, true);
        k.nm.setPadding(0, dp(10), 0, 0);
        v.addView(k.nm);
        k.title = Ui.text(c, 15, Ui.TEXT, true);
        k.title.setPadding(0, dp(4), 0, 0);
        v.addView(k.title);
        k.progress = new Ui.ProgressLine(c, Ui.ACCENT);
        v.addView(k.progress, Ui.margins(Ui.lp(-1, dp(4)), 0, dp(10), 0, 0));
        v.setOnClickListener(x -> host.play(ch));
        v.setOnFocusChangeListener((x, has) -> {
            if (has) {
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
        k.nm.setText(a != null ? k.ch.number + "  " + k.ch.displayName() : k.ch.number);
        k.title.setText(a != null ? a.title : k.ch.displayName());
        k.progress.setProgress(a != null ? a.progress(now) : 0);
        k.progress.setVisibility(a != null ? View.VISIBLE : View.INVISIBLE);
    }

    private void updateResume() {
        Channel last = ChannelStore.get().byNumber(prefs.lastChannel());
        if (last != null) setSubtitle(resumeTile, last.number + " " + last.displayName()
                + (last.now(now()) != null ? " · " + nowTitle(last) : ""));
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
