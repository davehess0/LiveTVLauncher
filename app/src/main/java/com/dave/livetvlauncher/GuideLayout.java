package com.dave.livetvlauncher;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Option A, "Guide": a cable-box-style home screen. A large preview panel at the top shows
 * what's on the focused channel; below it, every channel with now / next and a progress bar.
 * Press OK on a channel to start watching.
 */
public final class GuideLayout extends HomeLayout {

    private final boolean asOverlay;
    private TextView status, clock, weather;
    private LinearLayout heroLogoSlot;
    private TextView heroChannel, heroTitle, heroMeta, heroSynopsis, heroNext;
    private Ui.ProgressLine heroProgress;
    private LinearLayout list, plexBox;
    private ScrollView scroll;
    private View firstAction;
    private final List<Row> rows = new ArrayList<>();
    private Channel heroCh;

    private static final class Row {
        Channel ch;
        LinearLayout view;
        TextView title;
        Ui.ProgressLine progress;
    }

    public GuideLayout(LauncherActivity host, boolean asOverlay) {
        super(host);
        this.asOverlay = asOverlay;
    }

    @Override public View create() {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(22), dp(48), dp(0));
        root.setClipChildren(false);

        // Top bar: title, quick actions as small glass pills, clock (no heavy chrome).
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        TextView title = Ui.text(c, 22, Ui.TEXT, true);
        title.setText(asOverlay ? "TV Guide" : "Live TV");
        title.setPadding(0, 0, dp(24), 0);
        top.addView(title);
        firstAction = pill("Last Channel", v -> host.resumeLastChannel());
        top.addView(firstAction, pillLp());
        top.addView(pill("HDHomeRun", v -> Apps.launch(host, Apps.HDHR)), pillLp());
        top.addView(pill("Plex Live TV", v -> Apps.launch(host, Apps.PLEX)), pillLp());
        if (host.hasCameras()) top.addView(pill("Cameras", v -> host.openCameras()), pillLp());
        top.addView(pill("Apps", v -> host.openApps()), pillLp());
        top.addView(pill("Settings", v -> host.openSettings()), pillLp());
        top.addView(new View(c), Ui.lpWeight(0, 1, 1));
        weather = weatherLabel(20);
        top.addView(weather);
        clock = Ui.text(c, 20, Ui.TEXT, true);
        top.addView(clock);
        root.addView(top);

        // Streaming apps, same row as Channel Wall.
        root.addView(streamingRow(128, 72));

        // Body: channel list on the left, preview of the focused channel on the right.
        LinearLayout body = new LinearLayout(c);
        // Must clip: with hardware drawing, the list is only cut off at its edges if its parent clips.
        body.setClipChildren(true);

        scroll = new ScrollView(c);
        // Clip to the list's own area so rows and Plex posters scrolled out of view don't draw over
        // the top bar or the preview panel. The padding leaves room for the focused row's lift.
        scroll.setClipChildren(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(10), dp(8), dp(12), dp(24));
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        content.addView(list);
        // Plex rows sit under the channel list: scroll past the last channel to reach them.
        plexBox = new LinearLayout(c);
        plexBox.setOrientation(LinearLayout.VERTICAL);
        plexBox.setClipChildren(false);
        content.addView(plexBox);
        scroll.addView(content);
        body.addView(scroll, Ui.lpWeight(0, -1, 1.35f));

        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(24), dp(22), dp(24), dp(22));
        hero.setBackground(Ui.rounded(0x1FFFFFFF, dp(24), 0, 0));
        heroLogoSlot = new LinearLayout(c);
        hero.addView(heroLogoSlot, Ui.lp(dp(150), dp(88)));
        LinearLayout chLine = new LinearLayout(c);
        chLine.setGravity(Gravity.CENTER_VERTICAL);
        chLine.setPadding(0, dp(16), 0, 0);
        chLine.addView(livePill());
        heroChannel = Ui.text(c, 14, Ui.SUB, true);
        heroChannel.setPadding(dp(10), 0, 0, 0);
        chLine.addView(heroChannel);
        hero.addView(chLine);
        heroTitle = Ui.text(c, 24, Ui.TEXT, true);
        heroTitle.setSingleLine(false);
        heroTitle.setMaxLines(2);
        heroTitle.setPadding(0, dp(10), 0, 0);
        hero.addView(heroTitle);
        heroMeta = Ui.text(c, 14, Ui.SUB, false);
        heroMeta.setPadding(0, dp(6), 0, 0);
        hero.addView(heroMeta);
        heroProgress = new Ui.ProgressLine(c, Ui.TEXT);
        hero.addView(heroProgress, Ui.margins(Ui.lp(-1, dp(4)), 0, dp(12), 0, 0));
        heroSynopsis = Ui.text(c, 14, 0xFFD1D1D6, false);
        heroSynopsis.setSingleLine(false);
        heroSynopsis.setMaxLines(3);
        heroSynopsis.setPadding(0, dp(12), 0, 0);
        hero.addView(heroSynopsis);
        heroNext = Ui.text(c, 14, Ui.SUB, false);
        heroNext.setPadding(0, dp(12), 0, 0);
        hero.addView(heroNext);
        hero.addView(new View(c), Ui.lpWeight(-1, 0, 1));
        status = Ui.text(c, 12, Ui.DIM, false);
        hero.addView(status);
        body.addView(hero, Ui.margins(Ui.lpWeight(0, -1, 1), dp(16), dp(6), 0, dp(24)));

        root.addView(body, Ui.lpWeight(-1, 0, 1));
        return root;
    }

    private View pill(String label, View.OnClickListener click) {
        LinearLayout p = Ui.card(c, LinearLayout.HORIZONTAL, 0);
        p.setGravity(Gravity.CENTER);
        // Slimmer when the Cameras button is added, so the weather and clock still fit on the right.
        int side = host.hasCameras() ? 11 : 16;
        p.setPadding(dp(side), dp(8), dp(side), dp(8));
        TextView t = Ui.text(c, host.hasCameras() ? 13 : 14, Ui.TEXT, true);
        t.setText(label);
        p.addView(t);
        p.setOnClickListener(click);
        return p;
    }

    private LinearLayout.LayoutParams pillLp() {
        return Ui.margins(Ui.lp(-2, -2), 0, 0, dp(10), 0);
    }

    @Override public void bind(List<Channel> channels, String st) {
        showStatus(status, st);
        clock.setText(Ui.clock(prefs.clock24h()));
        showWeather(weather);
        list.removeAllViews();
        rows.clear();
        if (channels.isEmpty()) {
            TextView empty = Ui.text(c, 18, Ui.SUB, false);
            empty.setSingleLine(false);
            empty.setText(st);
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            list.addView(empty);
            showHero(null);
            return;
        }
        for (Channel ch : channels) list.addView(makeRow(ch).view);
        Channel last = ChannelStore.get().byNumber(prefs.lastChannel());
        showHero(last != null ? last : channels.get(0));
    }

    private Row makeRow(Channel ch) {
        Row r = new Row();
        r.ch = ch;
        LinearLayout v = Ui.card(c, LinearLayout.HORIZONTAL, Ui.ACCENT);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(18), dp(11), dp(18), dp(11));
        // Rows zoom less than tiles; a long list wobbling looks busy.
        v.setOnFocusChangeListener((view, has) -> {
            if (has) { Ui.adoptFocusColors(view); showHero(ch); }
            Ui.lift(view, has, 1.02f);
        });

        TextView num = Ui.text(c, 18, Ui.TEXT, true);
        num.setText(ch.number);
        v.addView(num, Ui.lp(dp(62), -2));
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, 12, Ui.SUB, true);
        name.setText(ch.name + (ch.hd ? "  ·  HD" : ""));
        mid.addView(name);
        r.title = Ui.text(c, 16, Ui.TEXT, true);
        r.title.setPadding(0, dp(3), 0, 0);
        mid.addView(r.title);
        v.addView(mid, Ui.lpWeight(0, -2, 1));
        r.progress = new Ui.ProgressLine(c, Ui.TEXT);
        v.addView(r.progress, Ui.margins(Ui.lp(dp(72), dp(4)), dp(14), 0, 0, 0));

        v.setOnClickListener(x -> host.play(ch));
        LinearLayout.LayoutParams lp = Ui.margins(Ui.lp(-1, -2), 0, 0, 0, dp(8));
        v.setLayoutParams(lp);
        r.view = v;
        fillRow(r);
        rows.add(r);
        return r;
    }

    private void fillRow(Row r) {
        long now = now();
        Channel.Airing a = r.ch.now(now);
        r.title.setText(a != null ? a.title : "—");
        r.progress.setProgress(a != null ? a.progress(now) : 0f);
        r.progress.setVisibility(a != null ? View.VISIBLE : View.INVISIBLE);
    }

    private void showHero(Channel ch) {
        heroCh = ch;
        heroLogoSlot.removeAllViews();
        if (ch == null) {
            heroChannel.setText("");
            heroTitle.setText("No channels yet");
            heroMeta.setText("");
            heroSynopsis.setText("");
            heroNext.setText("");
            heroProgress.setProgress(0);
            return;
        }
        heroLogoSlot.addView(logo(ch, 150, 92, 30));
        Channel.Airing showing = ch.now(now());
        host.setBackdrop(showing != null && showing.imageUrl != null ? showing.imageUrl : ch.logoUrl);
        long now = now();
        boolean h24 = prefs.clock24h();
        Channel.Airing a = ch.now(now);
        Channel.Airing n = ch.next(now);
        heroChannel.setText(ch.number + "  ·  " + ch.name);
        if (a != null) {
            heroTitle.setText(a.title);
            String ep = a.episodeTitle.isEmpty() ? "" : "  ·  " + (a.episodeNumber.isEmpty() ? "" : a.episodeNumber + " ") + a.episodeTitle;
            heroMeta.setText(Ui.range(a, h24) + "  ·  " + Ui.timeLeft(a, now) + ep);
            heroSynopsis.setText(a.synopsis);
            heroProgress.setProgress(a.progress(now));
        } else {
            heroTitle.setText(ch.name);
            heroMeta.setText("Guide data not available for this channel");
            heroSynopsis.setText("");
            heroProgress.setProgress(0);
        }
        heroNext.setText(n != null ? "Up Next  ·  " + Ui.time(n.start, h24) + "  " + n.title : "");
    }

    @Override public void bindPlex(PlexClient.Library lib) {
        fillPlexRows(plexBox, lib, 100);
    }

    @Override public void tick() {
        clock.setText(Ui.clock(prefs.clock24h()));
        showWeather(weather);
        for (Row r : rows) fillRow(r);
        if (heroCh != null) showHero(heroCh);
    }

    @Override public void showLastWatched() {
        Channel last = ChannelStore.get().byNumber(prefs.lastChannel());
        if (last == null) return;
        for (Row r : rows) {
            if (r.ch == last) {
                r.view.requestFocus();     // scrolls the list to it
                Ui.adoptFocusColors(r.view);
                showHero(last);            // in case that row already had focus (no focus change event)
                return;
            }
        }
        showHero(last); // not in the list (e.g. favorites only): still show it in the preview
    }

    @Override public void focusDefault() {
        // Land on the last-watched channel so "Home, OK" gets you back to TV quickly.
        Channel last = ChannelStore.get().byNumber(prefs.lastChannel());
        for (Row r : rows) {
            if (last != null && r.ch == last) { r.view.requestFocus(); return; }
        }
        if (!rows.isEmpty()) rows.get(0).view.requestFocus();
        else if (firstAction != null) firstAction.requestFocus();
    }

    @Override public boolean onBack() {
        // From deep in the list, Back jumps to the top first.
        if (scroll != null && scroll.getScrollY() > 0) {
            scroll.smoothScrollTo(0, 0);
            if (!rows.isEmpty()) rows.get(0).view.requestFocus();
            return true;
        }
        return false;
    }
}
