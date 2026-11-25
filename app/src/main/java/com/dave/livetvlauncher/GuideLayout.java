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
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class GuideLayout extends HomeLayout {

    // ---- Live preview of the selected channel (muted, in the preview panel) ----

    private android.widget.FrameLayout previewBox;
    private androidx.media3.ui.PlayerView previewView;
    private androidx.media3.exoplayer.ExoPlayer previewPlayer;
    private VlcEngine previewVlc;
    private Channel previewCh;        // channel being previewed (or about to be)
    private boolean visible = true;   // false while another screen is in front
    private static final long PREVIEW_DELAY_MS = 1200; // settle on a channel this long before tuning
    private final Runnable startPreview = this::startPreview;

    /** Preview the channel after a short pause, so scrolling through the list doesn't grab tuners. */
    private void schedulePreview(Channel ch) {
        stopPreview();
        if (ch == null || !prefs.livePreview() || !visible || ch.streamUrl.isEmpty()) return;
        previewCh = ch;
        previewBox.postDelayed(startPreview, PREVIEW_DELAY_MS);
    }

    private void startPreview() {
        Channel ch = previewCh;
        if (ch == null || !visible) return;
        previewPlayer = new androidx.media3.exoplayer.ExoPlayer.Builder(c).build();
        previewPlayer.setVolume(0f);
        previewPlayer.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onTracksChanged(androidx.media3.common.Tracks t) {
                String info = describe(t);
                if (!info.isEmpty() && !info.equals(streamInfo.get(ch))) {
                    streamInfo.put(ch, info);
                    if (heroCh == ch) showHero(ch);
                }
                // No decoder for this video here (e.g. MPEG-2 on a tablet): preview with VLC instead.
                if (t.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                        && !t.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO) && previewCh == ch) {
                    releaseExoPreview();
                    previewVlc = new VlcEngine(c, previewBox, 1, new VlcEngine.Listener() {
                        @Override public void onPlaying() { if (previewCh == ch) heroLogoSlot.setVisibility(View.INVISIBLE); }
                        @Override public void onBuffering(boolean b) { }
                        @Override public void onError(String why) { stopPreview(); }
                        @Override public void onEnded() { stopPreview(); }
                    });
                    previewVlc.setMuted(true);
                    previewVlc.play(ch.streamUrl, 0);
                }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_READY && previewCh == ch) {
                    previewView.setVisibility(View.VISIBLE);
                    heroLogoSlot.setVisibility(View.INVISIBLE);
                }
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                // Usually all tuners are busy: just keep showing the logo.
                AppLog.w("Guide", "Preview of " + ch.number + " didn't start (" + e.getErrorCodeName() + ")");
                stopPreview();
            }
        });
        previewView.setPlayer(previewPlayer);
        previewPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(ch.streamUrl));
        previewPlayer.prepare();
        previewPlayer.setPlayWhenReady(true);
    }

    private void releaseExoPreview() {
        if (previewPlayer != null) {
            previewView.setPlayer(null);
            previewPlayer.release();
            previewPlayer = null;
        }
        previewView.setVisibility(View.GONE);
    }

    /** Stop any preview and free its tuner straight away. */
    private void stopPreview() {
        if (previewBox != null) previewBox.removeCallbacks(startPreview);
        releaseExoPreview();
        if (previewVlc != null) { previewVlc.release(); previewVlc = null; }
        previewCh = null;
        if (heroLogoSlot != null) heroLogoSlot.setVisibility(View.VISIBLE);
    }

    @Override public void onHidden() {
        visible = false;
        stopPreview();
    }

    @Override public void onShown() {
        visible = true;
        if (heroCh != null) schedulePreview(heroCh);
    }

    private final boolean asOverlay;
    private TextView status, clock, weather;
    private LinearLayout heroLogoSlot;
    private TextView heroChannel, heroTitle, heroMeta, heroSynopsis, heroNext;
    private Ui.ProgressLine heroProgress;
    private LinearLayout list, plexBox, recent;
    private ScrollView scroll;
    private View firstAction;
    private final List<Row> rows = new ArrayList<>();
    private Channel heroCh;

    private static final class Row {
        Channel ch;
        LinearLayout view;
        TextView title;
        Ui.ProgressLine progress;
        android.widget.ImageView logo; // shown instead of the progress bar when there's no guide
    }

    /** What each previewed channel's stream turned out to be, e.g. "1080p · H.264 · Stereo". */
    private final java.util.Map<Channel, String> streamInfo = new java.util.HashMap<>();

    private static String describe(androidx.media3.common.Tracks t) {
        String video = "", audio = "";
        for (androidx.media3.common.Tracks.Group g : t.getGroups()) {
            if (g.length == 0) continue;
            androidx.media3.common.Format f = g.getTrackFormat(0);
            if (g.getType() == androidx.media3.common.C.TRACK_TYPE_VIDEO && video.isEmpty()) {
                int h = Math.min(f.width, f.height) > 0 ? Math.min(f.width, f.height) : f.height;
                String res = h >= 2000 ? "4K" : h > 0 ? h + "p" : "";
                String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
                String codec = m.contains("hevc") ? "HEVC" : m.contains("avc") ? "H.264" : m.contains("mpeg2") ? "MPEG-2"
                        : m.contains("av01") ? "AV1" : m.contains("vp9") ? "VP9" : "";
                video = res + (codec.isEmpty() ? "" : (res.isEmpty() ? "" : " · ") + codec);
            } else if (g.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO && audio.isEmpty() && f.channelCount > 0) {
                audio = f.channelCount >= 6 ? "5.1" : f.channelCount == 1 ? "Mono" : "Stereo";
            }
        }
        return video + (audio.isEmpty() ? "" : (video.isEmpty() ? "" : " · ") + audio);
    }

    public GuideLayout(LauncherActivity host, boolean asOverlay) {
        super(host);
        this.asOverlay = asOverlay;
    }

    /** How far (dp) the channel list fades out at its top and bottom edges. */
    private static final int FADE_DP = 56;

    @Override public View create() {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(Ui.SIDE_DP), dp(22), dp(Ui.SIDE_DP), dp(0));
        root.setClipChildren(false);
        root.setClipToPadding(false); // focused tiles at the edges grow into the margin instead of being cut off

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
        if (host.hasCameras()) top.addView(pill("Cameras", v -> host.openCameras()), pillLp());
        top.addView(pill("Apps", v -> host.openApps()), pillLp());
        top.addView(pill("Settings", v -> host.openSettings()), pillLp());
        top.addView(new View(c), Ui.lpWeight(0, 1, 1));
        weather = weatherLabel(20);
        top.addView(weather, weatherLp());
        clock = Ui.text(c, 20, Ui.TEXT, true);
        top.addView(clock);
        root.addView(top);

        // Streaming apps, same row as Channel Wall, with room above and below for a tile to grow.
        View streaming = streamingRow(128, 72);
        ((LinearLayout.LayoutParams) streaming.getLayoutParams()).setMargins(-dp(Ui.SIDE_DP), dp(16), -dp(Ui.SIDE_DP), dp(4));
        root.addView(streaming);

        // Body: channel list on the left, preview of the focused channel on the right.
        LinearLayout body = new LinearLayout(c);
        // Must clip: with hardware drawing, the list is only cut off at its edges if its parent clips.
        body.setClipChildren(true);

        scroll = new ScrollView(c) {
            private final android.graphics.Paint fade = new android.graphics.Paint();
            { fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)); }

            // Scrolling keeps the selected row clear of the faded edges, so it's never cut off and its
            // glow has room.
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                android.graphics.Rect roomy = new android.graphics.Rect(rect);
                roomy.inset(0, -dp(FADE_DP + 8));
                return super.computeScrollDeltaToGetChildRectOnScreen(roomy);
            }

            /**
             * Rows fade out at the top and bottom of the list instead of being cut off by a hard line.
             * Android's own fading edge stops at the padding and would cut the selected row's glow, so
             * this fades the whole width of the list instead.
             */
            @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
                int w = getWidth(), h = getHeight(), y = getScrollY();
                View child = getChildAt(0);
                boolean moreAbove = y > 0;
                boolean moreBelow = child != null && y + h < child.getBottom() + getPaddingBottom();
                if (!moreAbove && !moreBelow) { super.dispatchDraw(canvas); return; }
                float len = dp(FADE_DP);
                int save = canvas.saveLayer(0, y, w, y + h, null);
                super.dispatchDraw(canvas);
                if (moreAbove) {
                    fade.setShader(new android.graphics.LinearGradient(0, y, 0, y + len, 0xFF000000, 0x00000000,
                            android.graphics.Shader.TileMode.CLAMP));
                    canvas.drawRect(0, y, w, y + len, fade);
                }
                if (moreBelow) {
                    fade.setShader(new android.graphics.LinearGradient(0, y + h - len, 0, y + h, 0x00000000, 0xFF000000,
                            android.graphics.Shader.TileMode.CLAMP));
                    canvas.drawRect(0, y + h - len, w, y + h, fade);
                }
                canvas.restoreToCount(save);
            }
        };
        // The body (above) cuts the list off at the list's own edges, so rows scrolled out of view don't
        // draw over the streaming apps or the preview panel. The list itself must not clip: a clipping
        // container cuts each child at the child's own width, which would chop the selected row's glow.
        // The padding is the room the glow and lift get inside the list's area.
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        // The list reaches into the page margin, so a selected row has room to grow and glow, while
        // the left padding keeps the rows lined up with the title and the streaming apps above.
        scroll.setPadding(dp(Ui.SIDE_DP), dp(18), dp(22), dp(28));
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        // Recently watched channels sit above the full list.
        recent = recentBox();
        content.addView(recent);
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
        // Live preview window (16:9-ish): the channel logo sits in it until the video starts.
        previewBox = new android.widget.FrameLayout(c);
        previewBox.setBackground(Ui.rounded(0xFF000000, dp(14), 0, 0));
        previewBox.setClipToOutline(true);
        heroLogoSlot = new LinearLayout(c);
        // Same size as the logo plate put in it (showHero), or the plate's bottom gets cut off.
        previewBox.addView(heroLogoSlot, new android.widget.FrameLayout.LayoutParams(dp(150), dp(92), Gravity.CENTER));
        previewView = new androidx.media3.ui.PlayerView(c);
        previewView.setUseController(false);
        previewView.setShutterBackgroundColor(0xFF000000);
        previewView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        previewView.setFocusable(false);
        previewView.setVisibility(View.GONE);
        previewBox.addView(previewView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        hero.addView(previewBox, Ui.lp(-1, dp(prefs.livePreview() ? 164 : 92)));
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
        // The description fills whatever room is left in the panel (a two-line title or "Up Next" take
        // some of it). At rest it shows only the lines that fit, ending in "…", so it's never cut
        // mid-line; with the panel selected it shows in full and Up / Down scroll it.
        heroSynopsis = new TextView(c);
        heroSynopsis.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        heroSynopsis.setTextColor(0xFFD1D1D6);
        heroSynopsis.setIncludeFontPadding(false);
        heroSynopsis.setLineSpacing(0, 1.1f);
        heroSynopsis.setPadding(0, dp(12), 0, 0);
        synScroll = new ScrollView(c) {
            @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                // Only whole lines: a little slack so a line never peeks out half-cut at the bottom.
                int room = h - heroSynopsis.getPaddingTop() - dp(6);
                fitLines = Math.max(0, room / Math.max(1, heroSynopsis.getLineHeight()));
                post(() -> expandSynopsis(heroExpanded));
            }
        };
        synScroll.setVerticalScrollBarEnabled(false);
        synScroll.setFocusable(false);
        synScroll.setVerticalFadingEdgeEnabled(true);
        synScroll.setFadingEdgeLength(dp(24));
        synScroll.addView(heroSynopsis);
        hero.addView(synScroll, Ui.lpWeight(-1, 0, 1));
        heroNext = Ui.text(c, 14, Ui.SUB, false);
        heroNext.setPadding(0, dp(10), 0, dp(4));
        hero.addView(heroNext);
        heroHint = Ui.text(c, 12, Ui.ACCENT, true);
        heroHint.setText("OK to watch  ·  ▲ ▼ read more  ·  ◀ back to the list");
        heroHint.setPadding(0, dp(6), 0, 0);
        heroHint.setVisibility(View.GONE);
        hero.addView(heroHint);
        status = Ui.text(c, 12, Ui.DIM, false);
        hero.addView(status);

        // The whole panel can be selected (Right from the list): it glows, OK opens the channel,
        // Up / Down scroll the full description, Left goes back to that channel in the list.
        hero.setBackground(Ui.glassWithGlow(c, dp(24), Ui.PANEL));
        hero.setFocusable(true);
        hero.setClickable(true);
        hero.setOnClickListener(v -> { if (heroCh != null) host.play(heroCh); });
        hero.setOnFocusChangeListener((v, has) -> {
            // Selected: fold the video window away so the whole description (and the keys hint) has room.
            previewBox.setVisibility(has ? View.GONE : View.VISIBLE);
            expandSynopsis(has);
            heroHint.setVisibility(has ? View.VISIBLE : View.GONE);
            Ui.lift(v, has, 1f);
        });
        hero.setOnKeyListener((v, keyCode, e) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    int step = heroSynopsis.getLineHeight() * 3;
                    synScroll.smoothScrollBy(0, keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ? -step : step);
                }
                return true; // stay in the panel
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                for (Row r : rows) if (r.ch == heroCh) { r.view.requestFocus(); return true; }
                focusDefault();
                return true;
            }
            return false;
        });
        // Room on every side for the selected panel's glow (the body below clips at its edges).
        body.addView(hero, Ui.margins(Ui.lpWeight(0, -1, 1), dp(16), dp(24), dp(Ui.SIDE_DP), dp(24)));

        root.addView(body, Ui.margins(Ui.lpWeight(-1, 0, 1), -dp(Ui.SIDE_DP), 0, -dp(Ui.SIDE_DP), 0));
        return root;
    }

    private ScrollView synScroll;
    private TextView heroHint;
    private int fitLines = 3;
    private boolean heroExpanded;

    /** Full description (panel selected) or just the lines that fit, ending in "…". */
    private void expandSynopsis(boolean full) {
        heroExpanded = full;
        heroSynopsis.setMaxLines(full ? Integer.MAX_VALUE : Math.max(1, fitLines));
        heroSynopsis.setEllipsize(full ? null : android.text.TextUtils.TruncateAt.END);
        heroSynopsis.setVisibility(!full && fitLines == 0 ? View.INVISIBLE : View.VISIBLE);
        if (!full) synScroll.scrollTo(0, 0);
    }

    /** Recent Channels, with a clear gap before the full channel list when the row is showing. */
    private void fillRecentSpaced() {
        fillRecent(recent, 150);
        recent.setPadding(0, 0, 0, recent.getChildCount() > 0 ? dp(28) : 0);
    }

    /** Recent Channels and Plex rows stay inside the channel-list column. */
    @Override protected boolean fullBleedRows() { return false; }

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
        fillRecentSpaced();
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
            if (has) showHero(ch);
            Ui.lift(view, has, 1.02f);
        });

        TextView num = Ui.text(c, 18, Ui.TEXT, true);
        num.setText(ch.number);
        v.addView(num, Ui.lp(dp(62), -2));
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, 12, Ui.SUB, true);
        name.setText(ch.displayName() + (ch.hd ? "  ·  HD" : ""));
        mid.addView(name);
        r.title = Ui.text(c, 16, Ui.TEXT, true);
        r.title.setPadding(0, dp(3), 0, 0);
        mid.addView(r.title);
        v.addView(mid, Ui.lpWeight(0, -2, 1));
        r.progress = new Ui.ProgressLine(c, Ui.TEXT);
        v.addView(r.progress, Ui.margins(Ui.lp(dp(72), dp(4)), dp(14), 0, 0, 0));
        // No guide: the channel's logo sits where the progress bar would be.
        if (ch.logoUrl != null) {
            r.logo = new android.widget.ImageView(c);
            r.logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            r.logo.setVisibility(View.GONE);
            v.addView(r.logo, Ui.margins(Ui.lp(dp(72), dp(36)), dp(14), 0, 0, 0));
            ImageLoader.load(r.logo, ch.logoUrl, dp(72));
        }

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
        // Without a guide: the playlist group (IPTV) instead of a dash, and the logo instead of a bar.
        String noGuide = r.ch.iptv && !r.ch.group.isEmpty() ? r.ch.group : r.ch.iptv ? "Live" : "—";
        r.title.setText(a != null ? a.title : noGuide);
        r.title.setTextColor(a != null ? Ui.TEXT : Ui.SUB);
        r.progress.setProgress(a != null ? a.progress(now) : 0f);
        boolean showLogo = a == null && r.logo != null;
        r.progress.setVisibility(a != null ? View.VISIBLE : showLogo ? View.GONE : View.INVISIBLE);
        if (r.logo != null) r.logo.setVisibility(showLogo ? View.VISIBLE : View.GONE);
    }

    private void showHero(Channel ch) {
        if (ch != heroCh || previewCh == null) schedulePreview(ch);
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
        heroChannel.setText(ch.number + "  ·  " + ch.displayName());
        String info = streamInfo.get(ch);
        if (a != null) {
            heroTitle.setText(a.title);
            String ep = a.episodeTitle.isEmpty() ? "" : "  ·  " + (a.episodeNumber.isEmpty() ? "" : a.episodeNumber + " ") + a.episodeTitle;
            heroMeta.setText(Ui.range(a, h24) + "  ·  " + Ui.timeLeft(a, now) + ep + (info != null ? "  ·  " + info : ""));
            heroProgress.setVisibility(View.VISIBLE);
            heroSynopsis.setText(a.synopsis);
            heroProgress.setProgress(a.progress(now));
        } else {
            // No guide: what we do know about the channel, and how to get a guide for it.
            heroTitle.setText(ch.displayName());
            String source = ch.iptv ? "IPTV" : ch.tunarr ? "Tunarr" : "Antenna";
            String group = ch.iptv && !ch.group.isEmpty() ? ch.group + "  ·  " : "";
            heroMeta.setText(group + source + (info != null ? "  ·  " + info : ""));
            heroSynopsis.setText(ch.iptv
                    ? "No guide for this channel. Your provider's guide may list it under another name, or set a guide in Settings → IPTV → Guide (XMLTV)."
                    : "No guide data for this channel right now.");
            heroProgress.setVisibility(View.GONE);
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
        refreshRecent();
        if (heroCh != null) showHero(heroCh);
    }

    @Override protected void onRecentFocused(Channel ch) { showHero(ch); }

    @Override public void showLastWatched() {
        fillRecentSpaced();
        Channel last = ChannelStore.get().byNumber(prefs.lastChannel());
        if (last == null) return;
        for (Row r : rows) {
            if (r.ch == last) {
                r.view.requestFocus();     // scrolls the list to it
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
