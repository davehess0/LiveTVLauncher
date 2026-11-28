package com.dave.livetvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.List;

/**
 * Full-screen live TV straight from the HDHomeRun tuner.
 *
 * Also plays Plex movies/episodes directly from your server (no transcoding) when
 * Settings → "Open Plex movies in" is set to Built-in player; progress is reported back to Plex.
 *
 * Live TV remote:  Up / CH+  next channel      Down / CH-  previous channel
 *          OK / ⏯    pause / resume (live TV keeps recording while paused)
 *          Left      back 10 s          Right     forward 10 s
 *          ⏪        back 30 s          ⏩        forward 30 s (past the live point = back to live)
 *          Hold Left jump to the channel you were just on
 *          0-9       type a channel number (51 = 5.1)
 *          Back      return to the home screen (the tuner is released)
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    private static final String X_NUMS = "nums", X_NAMES = "names", X_URLS = "urls", X_START = "start";

    public static Intent intent(Context c, List<Channel> lineup, Channel start) {
        int n = lineup.size();
        boolean contains = lineup.contains(start);
        if (!contains) n++;
        String[] nums = new String[n], names = new String[n], urls = new String[n];
        int i = 0, startIdx = 0;
        if (!contains) { nums[0] = start.number; names[0] = start.displayName(); urls[0] = start.streamUrl; i = 1; }
        for (Channel ch : lineup) {
            if (ch == start) startIdx = i;
            nums[i] = ch.number; names[i] = ch.displayName(); urls[i] = ch.streamUrl; i++;
        }
        return new Intent(c, PlayerActivity.class)
                .putExtra(X_NUMS, nums).putExtra(X_NAMES, names).putExtra(X_URLS, urls)
                .putExtra(X_START, startIdx);
    }

    private static final String X_PLEX = "plex";

    /** Direct-play a Plex item from the server. */
    public static Intent plexIntent(Context c, PlexClient.Item it, String base, String token) {
        String url = PlexClient.strip(base) + it.partKey
                + (it.partKey.contains("?") ? "&" : "?") + "X-Plex-Token=" + Uri.encode(token);
        return new Intent(c, PlayerActivity.class)
                .putExtra(X_PLEX, true)
                .putExtra(X_NUMS, new String[]{it.title})
                .putExtra(X_NAMES, new String[]{it.subtitle})
                .putExtra(X_URLS, new String[]{url})
                .putExtra("ratingKey", it.ratingKey)
                .putExtra("startMs", it.viewOffsetMs)
                .putExtra("durationMs", it.durationMs)
                .putExtra("base", base)
                .putExtra("token", token);
    }

    /** Direct-play a Jellyfin / Emby item (same player mode as Plex; progress goes back to that server). */
    public static Intent mediaIntent(Context c, PlexClient.Item it, String base, String token, String userId) {
        return new Intent(c, PlayerActivity.class)
                .putExtra(X_PLEX, true)
                .putExtra(X_NUMS, new String[]{it.title})
                .putExtra(X_NAMES, new String[]{it.subtitle})
                .putExtra(X_URLS, new String[]{it.partKey})
                .putExtra("ratingKey", it.ratingKey)
                .putExtra("startMs", it.viewOffsetMs)
                .putExtra("durationMs", it.durationMs)
                .putExtra("base", base)
                .putExtra("token", token)
                .putExtra("server", it.source)
                .putExtra("mediaSourceId", it.mediaSourceId)
                .putExtra("userId", userId);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean plexMode;
    private String plexKey, plexBase, plexToken;
    private long plexStartMs, plexDurationMs;
    private PlexClient plex;
    /** Which server the video is from: "plex", or JellyfinClient.JELLYFIN / EMBY. */
    private String mediaKind = "plex";
    private JellyfinClient jelly;
    private String mediaSourceId;
    private boolean jellyStarted;
    private final Runnable reporter = new Runnable() {
        @Override public void run() {
            reportPlex(playingNow() ? "playing" : "paused");
            handler.postDelayed(this, 10_000);
        }
    };
    private String[] nums, names, urls;
    private int index, previousIndex = -1;
    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout banner;
    private TextView bannerChannel, bannerTitle, bannerMeta, entry, error;
    private android.widget.ProgressBar spinner;
    private Ui.ProgressLine bannerProgress;
    private final StringBuilder typed = new StringBuilder();
    private int retries;
    private boolean undecodable; // current channel has no playable video/audio track
    private long tuneStartedAt;  // for logging how long tuning takes
    private boolean plexFailed;  // built-in player couldn't play this Plex file: OK opens it in Plex instead
    private FrameLayout root;

    // ---- Weather alerts while watching ----
    private TextView alertBanner;
    /** Alerts already shown as a banner in the player (until the app restarts). */
    private static final java.util.Set<String> alertsShownInPlayer = new java.util.HashSet<>();
    private final Runnable hideAlert = () -> alertBanner.animate().alpha(0f).setDuration(400)
            .withEndAction(() -> alertBanner.setVisibility(View.GONE)).start();
    /** Checks for new alerts every minute (the home screen isn't running its own checks meanwhile). */
    private final Runnable alertCheck = new Runnable() {
        @Override public void run() {
            Weather.get().refreshAlertsIfStale(PlayerActivity.this, PlayerActivity.this::showAlertBanner);
            showAlertBanner();
            handler.postDelayed(this, 60_000L);
        }
    };

    private void showAlertBanner() {
        if (alertBanner == null || isFinishing()) return;
        for (Weather.Alert a : Weather.get().undismissed(prefs)) {
            if (!alertsShownInPlayer.add(a.id)) continue; // once per alert
            AppLog.i("Weather", "Alert banner in player: " + a.event);
            boolean light = a.severity.equals("Moderate") || a.severity.equals("Minor");
            alertBanner.setTextColor(light ? 0xFF000000 : Ui.TEXT);
            alertBanner.setBackground(Ui.rounded(a.color(), Ui.dp(this, 14), 0, 0));
            alertBanner.setText("⚠  " + a.event + "  " + a.until(prefs.clock24h()) + "\nDetails on the home screen");
            alertBanner.animate().cancel();
            alertBanner.setAlpha(1f);
            alertBanner.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideAlert);
            handler.postDelayed(hideAlert, 15_000L);
            return; // one at a time
        }
    }

    // ---- VLC fallback: for video / audio this device can't decode itself ----

    /** VLC's engine while it's playing (null = the regular player is in use). */
    private VlcEngine vlc;
    /** Sources (host:port) that needed VLC once already this session: go straight to VLC for them. */
    private static final java.util.Set<String> vlcSources = new java.util.HashSet<>();

    private static String sourceOf(String url) {
        Uri u = Uri.parse(url);
        return u.getHost() + ":" + u.getPort();
    }

    private void ensureVlc() {
        if (vlc != null) return;
        vlc = new VlcEngine(this, root, 1, new VlcEngine.Listener() {
            @Override public void onPlaying() {
                spinner.setVisibility(View.GONE);
                error.setVisibility(View.GONE);
                retries = 0;
                if (tuneStartedAt > 0) {
                    AppLog.i("Player", (plexMode ? serverName() + " item" : "Channel " + nums[index]) + " playing in VLC after "
                            + (System.currentTimeMillis() - tuneStartedAt) + " ms");
                    tuneStartedAt = 0;
                }
                if (plexMode) showPlexBanner();
            }
            @Override public void onBuffering(boolean buffering) {
                if (vlc != null && !vlc.isPlaying()) spinner.setVisibility(buffering ? View.VISIBLE : View.GONE);
            }
            @Override public void onError(String why) {
                AppLog.e("Player", (plexMode ? serverName() + " item" : "Channel " + nums[index]) + ": " + why, null);
                if (plexMode) {
                    plexFailed = true;
                    showError(jelly == null
                            ? "This file can't play here, even with VLC.\n\nPress OK to open it in the Plex app, or Back to return."
                            : "This file can't play here, even with VLC.\n\nPress Back to return.");
                } else {
                    retryOrFail("VLC_ERROR");
                }
            }
            @Override public void onEnded() {
                if (plexMode) { reportPlex("stopped"); finish(); }
                else retryOrFail("STREAM_ENDED");
            }
        });
        vlc.setSubtitles(subsOn);
        view.setVisibility(View.INVISIBLE);
        if (player != null) player.stop();
    }

    private void releaseVlc() {
        if (vlc == null) return;
        vlc.release();
        vlc = null;
        view.setVisibility(View.VISIBLE);
    }

    // Engine-neutral helpers (regular player or VLC).
    private boolean wantPlay() { return vlc != null ? vlc.wantPlay() : player != null && player.getPlayWhenReady(); }
    private void setWantPlay(boolean p) { if (vlc != null) vlc.setWantPlay(p); else if (player != null) player.setPlayWhenReady(p); }
    private boolean playingNow() { return vlc != null ? vlc.isPlaying() : player != null && player.isPlaying(); }
    private long posMs() { return vlc != null ? vlc.timeMs() : player != null ? player.getCurrentPosition() : 0; }
    private long durMs() {
        long d = vlc != null ? vlc.lengthMs() : player != null ? player.getDuration() : 0;
        return d > 0 ? d : plexDurationMs;
    }
    private void seekToMs(long ms) { if (vlc != null) vlc.seekTo(ms); else if (player != null) player.seekTo(ms); }

    /** Live channel the regular player can't decode: keep the recording buffer, play it with VLC. */
    private void switchLiveToVlc(String why) {
        AppLog.i("Player", "Channel " + nums[index] + ": switching to VLC (" + why + ")");
        vlcSources.add(sourceOf(urls[index]));
        error.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        ensureVlc();
        if (direct || timeshift == null) playDirect();
        else playFrom(timeshift.offsetAt(streamTimeMs()));
        setWantPlay(true);
    }

    /** Plex file the regular player can't decode: continue in VLC from the same spot. */
    private void switchPlexToVlc(String why) {
        long at = Math.max(posMs(), Math.max(0, plexStartMs - 5000));
        AppLog.i("Player", serverName() + ": switching to VLC at " + at / 1000 + " s (" + why + ")");
        error.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        tuneStartedAt = System.currentTimeMillis();
        ensureVlc();
        vlc.play(urls[0], at);
    }
    private Prefs prefs;
    /** Subtitles / closed captions wanted (Settings, or the menu / CC button while watching). */
    private boolean subsOn;
    /** Live TV plays from this on-disk buffer so it can be paused and rewound. */
    private Timeshift timeshift;
    /**
     * The channel is an adaptive stream (HLS / DASH) or RTMP / RTSP / UDP, e.g. from an IPTV playlist:
     * it can't be recorded into the timeshift buffer, so it plays directly and pause / rewind use the
     * stream's own live window instead.
     */
    private boolean direct;
    /** Broadcast time (wall-clock ms) at the start of the current media source. */
    private long startTimeMs;
    /** Within this of the live point counts as live (covers normal player buffering). */
    private static final long LIVE_SLACK_MS = 6000;
    private boolean leftHeld;

    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(250).start();
    private final Runnable bannerTick = () -> { if (!plexMode && player != null) showBanner(); };
    private final Runnable commitEntry = this::commitTypedChannel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startMode = display().getMode();
        readTvHdr();
        prefs = new Prefs(this);
        subsOn = prefs.subtitles();
        Intent in = getIntent();
        nums = in.getStringArrayExtra(X_NUMS);
        names = in.getStringArrayExtra(X_NAMES);
        urls = in.getStringArrayExtra(X_URLS);
        index = in.getIntExtra(X_START, 0);
        if (nums == null || nums.length == 0) { finish(); return; }
        plexMode = in.getBooleanExtra(X_PLEX, false);
        if (plexMode) {
            plexKey = in.getStringExtra("ratingKey");
            plexBase = in.getStringExtra("base");
            plexToken = in.getStringExtra("token");
            plexStartMs = in.getLongExtra("startMs", 0);
            plexDurationMs = in.getLongExtra("durationMs", 0);
            plex = new PlexClient(prefs.clientId());
            String kind = in.getStringExtra("server");
            if (kind != null && !"plex".equals(kind)) {
                mediaKind = kind;
                mediaSourceId = in.getStringExtra("mediaSourceId");
                if (mediaSourceId == null) mediaSourceId = plexKey;
                jelly = new JellyfinClient(kind, plexBase, prefs.clientId()).withUser(plexToken, in.getStringExtra("userId"));
            }
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        // Touch layer over the video (VLC's view is inserted below it when used): tap = controls,
        // swipe up/down = channel list, swipe left/right = skip back/forward.
        root.addView(touchLayer(), new FrameLayout.LayoutParams(-1, -1));

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 28);
        banner.setPadding(p, p, p, p);
        banner.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        bannerChannel = Ui.text(this, 18, Ui.ACCENT, true);
        bannerTitle = Ui.text(this, 30, Ui.TEXT, true);
        bannerTitle.setPadding(0, Ui.dp(this, 6), 0, 0);
        bannerMeta = Ui.text(this, 16, Ui.SUB, false);
        bannerMeta.setPadding(0, Ui.dp(this, 6), 0, 0);
        bannerProgress = new Ui.ProgressLine(this, Ui.ACCENT);
        banner.addView(bannerChannel);
        banner.addView(bannerTitle);
        banner.addView(bannerMeta);
        banner.addView(bannerProgress, Ui.margins(Ui.lp(Ui.dp(this, 420), Ui.dp(this, 5)), 0, Ui.dp(this, 12), 0, 0));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(banner, blp);

        entry = Ui.text(this, 48, Ui.TEXT, true);
        entry.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        entry.setBackground(Ui.rounded(0xCC000000, Ui.dp(this, 12), 0, 0));
        entry.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        elp.setMargins(0, Ui.dp(this, 32), Ui.dp(this, 40), 0);
        root.addView(entry, elp);

        error = Ui.text(this, 20, Ui.TEXT, false);
        error.setSingleLine(false);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(View.GONE);
        root.addView(error, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Spinner while a channel tunes or the player buffers (also after rewinding / skipping).
        spinner = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        spinner.setVisibility(View.GONE);
        int s = Ui.dp(this, 64);
        root.addView(spinner, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));

        // Weather alert banner across the top (doesn't interrupt; the full pop-up waits for the home screen).
        alertBanner = Ui.text(this, 18, Ui.TEXT, true);
        alertBanner.setSingleLine(false);
        alertBanner.setMaxLines(2);
        alertBanner.setGravity(Gravity.CENTER_VERTICAL);
        alertBanner.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        alertBanner.setVisibility(View.GONE);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        alp.setMargins(Ui.dp(this, 120), Ui.dp(this, 24), Ui.dp(this, 120), 0);
        root.addView(alertBanner, alp);

        buildTouchControls(root);
        if (!plexMode) buildChannelGuide(root);
        if (!plexMode) buildFullGuide(root);
        setContentView(root);
    }

    // ---- Touch controls (phones / tablets). A TV remote never triggers these. ----

    private FrameLayout controls;
    private TextView playPauseBtn, liveBtn;
    private final Runnable hideControls = () -> {
        if (controls != null) controls.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> controls.setVisibility(View.GONE)).start();
    };

    private View touchLayer() {
        View v = new View(this);
        android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                if (guideShowing()) { hideChannelGuide(); return true; }
                if (controls.getVisibility() == View.VISIBLE) hideControls.run(); else showControls();
                return true;
            }
            @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                // Double-tap the left / right half: back 10 s / forward 10 s (like most video apps).
                boolean left = e.getX() < v.getWidth() / 2f;
                if (plexMode) onPlexKey(left ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
                else seekBy(left ? -10_000 : 10_000);
                showControls();
                return true;
            }
            @Override public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                if (a == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > Ui.dp(PlayerActivity.this, 60)) {
                    if (!plexMode) showChannelGuide(); // swipe up or down: channel list
                    return true;
                }
                if (Math.abs(dx) > Ui.dp(PlayerActivity.this, 80)) {
                    if (plexMode) onPlexKey(dx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
                    else seekBy(dx < 0 ? -30_000 : 30_000);
                    showControls();
                    return true;
                }
                return false;
            }
        });
        v.setOnTouchListener((view1, ev) -> gd.onTouchEvent(ev));
        return v;
    }

    /** A round, semi-transparent touch button. Not focusable, so the TV remote never lands on it. */
    private TextView touchButton(String label, float sp, int sizeDp, View.OnClickListener click) {
        TextView b = Ui.text(this, sp, Ui.TEXT, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(0x99000000, Ui.dp(this, sizeDp / 2), 0, 0));
        b.setFocusable(false);
        b.setOnClickListener(x -> { click.onClick(x); bumpControls(); });
        return b;
    }

    private FrameLayout.LayoutParams at(int sizeDp, int gravity, int mx, int my) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp), gravity);
        lp.setMargins(Ui.dp(this, mx), Ui.dp(this, my), Ui.dp(this, mx), Ui.dp(this, my));
        return lp;
    }

    private void buildTouchControls(FrameLayout root) {
        controls = new FrameLayout(this);
        controls.setVisibility(View.GONE);

        // Top left: back to the home screen, and subtitles on / off.
        controls.addView(touchButton("←", 26, 64, x -> finish()), at(64, Gravity.TOP | Gravity.START, 24, 24));
        TextView cc = touchButton("CC", 16, 64, x -> toggleSubtitles());
        FrameLayout.LayoutParams ccp = new FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64), Gravity.TOP | Gravity.START);
        ccp.setMargins(Ui.dp(this, 104), Ui.dp(this, 24), 0, 0);
        controls.addView(cc, ccp);

        // Centre: skip back, play / pause, skip forward.
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        int gap = Ui.dp(this, 28);
        if (plexMode) {
            mid.addView(touchButton("−10", 18, 76, x -> onPlexKey(KeyEvent.KEYCODE_DPAD_LEFT)), Ui.margins(Ui.lp(Ui.dp(this, 76), Ui.dp(this, 76)), 0, 0, gap, 0));
            playPauseBtn = touchButton("❚❚", 30, 104, x -> { onPlexKey(KeyEvent.KEYCODE_DPAD_CENTER); updatePlayPause(); });
            mid.addView(playPauseBtn, Ui.margins(Ui.lp(Ui.dp(this, 104), Ui.dp(this, 104)), 0, 0, gap, 0));
            mid.addView(touchButton("+30", 18, 76, x -> onPlexKey(KeyEvent.KEYCODE_DPAD_RIGHT)), Ui.lp(Ui.dp(this, 76), Ui.dp(this, 76)));
        } else {
            mid.addView(touchButton("−30", 16, 64, x -> seekBy(-30_000)), Ui.margins(Ui.lp(Ui.dp(this, 64), Ui.dp(this, 64)), 0, 0, gap, 0));
            mid.addView(touchButton("−10", 18, 76, x -> seekBy(-10_000)), Ui.margins(Ui.lp(Ui.dp(this, 76), Ui.dp(this, 76)), 0, 0, gap, 0));
            playPauseBtn = touchButton("❚❚", 30, 104, x -> { togglePause(); updatePlayPause(); });
            mid.addView(playPauseBtn, Ui.margins(Ui.lp(Ui.dp(this, 104), Ui.dp(this, 104)), 0, 0, gap, 0));
            mid.addView(touchButton("+10", 18, 76, x -> seekBy(10_000)), Ui.margins(Ui.lp(Ui.dp(this, 76), Ui.dp(this, 76)), 0, 0, gap, 0));
            mid.addView(touchButton("+30", 16, 64, x -> seekBy(30_000)), Ui.lp(Ui.dp(this, 64), Ui.dp(this, 64)));
        }
        controls.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        if (!plexMode) {
            // Top right: channel list, and jump back to live.
            TextView list = touchButton("☰  Channels", 16, 56, x -> showChannelGuide());
            TextView grid = touchButton("▦  Guide", 16, 56, x -> showFullGuide(nums[index]));
            LinearLayout topRight = new LinearLayout(this);
            list.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
            grid.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
            topRight.addView(grid, Ui.margins(Ui.lp(-2, Ui.dp(this, 56)), 0, 0, Ui.dp(this, 12), 0));
            topRight.addView(list, Ui.lp(-2, Ui.dp(this, 56)));
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            llp.setMargins(0, Ui.dp(this, 28), Ui.dp(this, 24), 0);
            controls.addView(topRight, llp);
            liveBtn = touchButton("● LIVE", 15, 48, x -> seekBy(24L * 3600_000));
            liveBtn.setTextColor(Ui.LIVE_RED);
            FrameLayout.LayoutParams lvp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 48), Gravity.TOP | Gravity.END);
            lvp.setMargins(0, Ui.dp(this, 96), Ui.dp(this, 24), 0);
            liveBtn.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
            controls.addView(liveBtn, lvp);

            // Right edge: channel up / down.
            LinearLayout ch = new LinearLayout(this);
            ch.setOrientation(LinearLayout.VERTICAL);
            ch.setGravity(Gravity.CENTER_HORIZONTAL);
            ch.addView(touchButton("▲", 24, 68, x -> tune(index + 1, true)), Ui.lp(Ui.dp(this, 68), Ui.dp(this, 68)));
            TextView chLabel = Ui.text(this, 13, Ui.SUB, true);
            chLabel.setText("CH");
            chLabel.setGravity(Gravity.CENTER);
            chLabel.setShadowLayer(4, 0, 1, 0xFF000000);
            ch.addView(chLabel, Ui.margins(Ui.lp(-2, -2), 0, Ui.dp(this, 6), 0, Ui.dp(this, 6)));
            ch.addView(touchButton("▼", 24, 68, x -> tune(index - 1, true)), Ui.lp(Ui.dp(this, 68), Ui.dp(this, 68)));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END);
            clp.setMargins(0, 0, Ui.dp(this, 28), 0);
            controls.addView(ch, clp);
        }
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showControls() {
        if (controls == null) return;
        controls.animate().cancel();
        controls.setAlpha(1f);
        controls.setVisibility(View.VISIBLE);
        updatePlayPause();
        if (plexMode) showPlexBanner(); else showBanner();
        bumpControls();
    }

    /** Controls stay up while paused; otherwise they fade out after a few seconds without a touch. */
    private void bumpControls() {
        handler.removeCallbacks(hideControls);
        if (controls != null && controls.getVisibility() == View.VISIBLE && wantPlay())
            handler.postDelayed(hideControls, 4000);
        updatePlayPause();
    }

    private void updatePlayPause() {
        if (playPauseBtn != null) playPauseBtn.setText(wantPlay() ? "❚❚" : "▶");
        if (liveBtn != null) liveBtn.setVisibility(!plexMode && behindLiveMs() > 0 ? View.VISIBLE : View.GONE);
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (guideShowing()) bumpGuideTimer(); // scrolling the channel list keeps it open
        if (fullGuideShowing()) bumpFullGuideTimer();
        return super.dispatchTouchEvent(e);
    }

    // ---- Channel guide overlay (Up / Down while watching) ----

    private LinearLayout guide;
    private TextView guideClock;
    private android.widget.ListView guideList;
    private final Runnable hideGuide = this::hideChannelGuide;
    private static final long GUIDE_IDLE_MS = 10_000;

    private void buildChannelGuide(FrameLayout root) {
        guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.VERTICAL);
        // No side padding on the panel itself: the list carries it instead (below), so the selected
        // channel's glow has room inside the list and isn't cut flat at its left and right edges.
        int p = Ui.dp(this, 24);
        guide.setPadding(0, p, 0, p);
        guide.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20A0A0C, 0xE60A0A0C, 0x000A0A0C}));
        TextView title = Ui.text(this, 22, Ui.TEXT, true);
        title.setText("Channels");
        title.setPadding(p + Ui.dp(this, 8), 0, 0, Ui.dp(this, 12));
        guide.addView(title);
        guideList = new android.widget.ListView(this);
        guideList.setDivider(null);
        guideList.setDividerHeight(Ui.dp(this, 6));
        guideList.setSelector(Ui.focusedGlass(this, Ui.dp(this, 14)));
        guideList.setDrawSelectorOnTop(false);
        guideList.setVerticalScrollBarEnabled(false);
        guideList.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        guideList.setClipToPadding(false);
        // A ListView keeps the selected row this far from its top and bottom while scrolling, and fades
        // rows out there, so the selected channel never sits on the bottom edge of the screen.
        guideList.setVerticalFadingEdgeEnabled(true);
        guideList.setFadingEdgeLength(Ui.dp(this, Ui.SCROLL_CLEAR_DP));
        guideList.setAdapter(new ChannelGuideAdapter());
        guideList.setOnItemClickListener((parent, v, pos, id) -> {
            hideChannelGuide();
            if (pos != index) tune(pos, true); else showBanner();
        });
        guideList.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                ((ChannelGuideAdapter) guideList.getAdapter()).selected = pos;
                ((ChannelGuideAdapter) guideList.getAdapter()).notifyDataSetChanged();
                updateGuideInfo(pos); // what's on the channel you're hovered over, on the right
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        guide.addView(guideList, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView more = Ui.text(this, 14, Ui.SUB, true);
        more.setText("▶  Full Guide");
        more.setPadding(p + Ui.dp(this, 8), Ui.dp(this, 10), 0, 0);
        guide.addView(more);
        guide.setVisibility(View.GONE);
        root.addView(guide, new FrameLayout.LayoutParams(Ui.dp(this, 520), -1, Gravity.START));
        buildGuideInfo(root);
        // The time, top right (as in the Full Guide), on a dark pill so it reads over any picture.
        guideClock = Ui.text(this, 20, Ui.TEXT, true);
        guideClock.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        guideClock.setBackground(Ui.rounded(0x990A0A0C, Ui.dp(this, 18), 0, 0));
        guideClock.setVisibility(View.GONE);
        FrameLayout.LayoutParams gclp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        gclp.setMargins(0, Ui.dp(this, 18), Ui.dp(this, Ui.SIDE_DP), 0);
        root.addView(guideClock, gclp);
    }

    // ---- Full Guide over the video (Guide button, or Right in the channel list) ----

    private GuideGrid fullGuide;
    private final Runnable hideFullGuideRun = this::hideFullGuide;
    private static final long FULL_GUIDE_IDLE_MS = 60_000;
    private boolean fullGuideCloseArmed;

    private void buildFullGuide(FrameLayout root) {
        fullGuide = new GuideGrid(this, true, this::watchFromFullGuide);
        fullGuide.setVisibility(View.GONE);
        root.addView(fullGuide, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showFullGuide(String channel) {
        if (fullGuide == null) return;
        hideChannelGuide();
        banner.animate().alpha(0f).setDuration(150).start();
        if (controls != null) { handler.removeCallbacks(hideControls); controls.setVisibility(View.GONE); }
        fullGuideCloseArmed = false;
        fullGuide.setVisibility(View.VISIBLE);
        fullGuide.start();
        fullGuide.selectChannel(channel);
        bumpFullGuideTimer();
        AppLog.i("Player", "Full Guide opened on " + channel);
    }

    private void hideFullGuide() {
        if (!fullGuideShowing()) return;
        handler.removeCallbacks(hideFullGuideRun);
        fullGuide.stop();
        fullGuide.setVisibility(View.GONE);
        view.requestFocus();
    }

    private boolean fullGuideShowing() { return fullGuide != null && fullGuide.getVisibility() == View.VISIBLE; }

    private void bumpFullGuideTimer() {
        handler.removeCallbacks(hideFullGuideRun);
        handler.postDelayed(hideFullGuideRun, FULL_GUIDE_IDLE_MS);
    }

    /** OK in the Full Guide on something on now (or Watch now): tune it here, in the player. */
    private void watchFromFullGuide(Channel ch) {
        hideFullGuide();
        for (int i = 0; i < nums.length; i++) {
            if (nums[i].equals(ch.number)) {
                if (i != index) tune(i, true); else showBanner();
                return;
            }
        }
        // Not in this player's lineup (e.g. favorites only): open it fresh.
        Apps.play(this, ch, ChannelStore.get().visible(prefs));
        finish();
    }

    // ---- What's-on panel on the right of the channel list ----

    private LinearLayout guideInfo;
    private TextView giChannel, giTitle, giMeta, giDesc, giNext;
    private Ui.ProgressLine giProgress;

    private void buildGuideInfo(FrameLayout root) {
        guideInfo = new LinearLayout(this);
        guideInfo.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 26);
        guideInfo.setPadding(p, p, p, p);
        guideInfo.setBackground(Ui.rounded(0xE6141418, Ui.dp(this, 20), 0, 0));
        guideInfo.setVisibility(View.GONE);

        LinearLayout chLine = new LinearLayout(this);
        chLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView live = Ui.text(this, 11, Ui.TEXT, true);
        live.setText(" LIVE ");
        live.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        live.setBackground(Ui.rounded(Ui.LIVE_RED, Ui.dp(this, 4), 0, 0));
        chLine.addView(live);
        giChannel = Ui.text(this, 14, Ui.SUB, true);
        giChannel.setPadding(Ui.dp(this, 10), 0, 0, 0);
        chLine.addView(giChannel);
        guideInfo.addView(chLine);

        giTitle = Ui.text(this, 22, Ui.TEXT, true);
        giTitle.setSingleLine(false);
        giTitle.setMaxLines(2);
        giTitle.setPadding(0, Ui.dp(this, 10), 0, 0);
        guideInfo.addView(giTitle);

        giMeta = Ui.text(this, 14, Ui.SUB, false);
        giMeta.setPadding(0, Ui.dp(this, 6), 0, 0);
        guideInfo.addView(giMeta);

        giProgress = new Ui.ProgressLine(this, Ui.TEXT);
        guideInfo.addView(giProgress, Ui.margins(Ui.lp(-1, Ui.dp(this, 4)), 0, Ui.dp(this, 12), 0, 0));

        giDesc = Ui.text(this, 14, 0xFFD1D1D6, false);
        giDesc.setSingleLine(false);
        giDesc.setMaxLines(6);
        giDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        giDesc.setPadding(0, Ui.dp(this, 12), 0, 0);
        guideInfo.addView(giDesc);

        giNext = Ui.text(this, 14, Ui.SUB, false);
        giNext.setPadding(0, Ui.dp(this, 12), 0, 0);
        guideInfo.addView(giNext);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(this, 380), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = Ui.dp(this, 36);
        root.addView(guideInfo, lp);
    }

    /** Fills the right-hand panel with what's on the channel at {@code pos} in the list. */
    private void updateGuideInfo(int pos) {
        if (guideInfo == null || nums == null || pos < 0 || pos >= nums.length) return;
        Channel ch = ChannelStore.get().byNumber(nums[pos]);
        long now = System.currentTimeMillis() / 1000L;
        Channel.Airing a = ch != null ? ch.now(now) : null;
        Channel.Airing n = ch != null ? ch.next(now) : null;
        boolean h24 = prefs.clock24h();
        // With a guide the title line also names the channel; without one the name is the big title.
        giChannel.setText(nums[pos] + (a != null ? "  ·  " + names[pos] : ""));
        if (a != null) {
            giTitle.setText(a.title);
            String ep = a.episodeTitle.isEmpty() ? ""
                    : "  ·  " + (a.episodeNumber.isEmpty() ? "" : a.episodeNumber + " ") + a.episodeTitle;
            giMeta.setText(Ui.range(a, h24) + "  ·  " + Ui.timeLeft(a, now) + ep);
            giMeta.setVisibility(View.VISIBLE);
            giProgress.setProgress(a.progress(now));
            giProgress.setVisibility(View.VISIBLE);
            giDesc.setText(a.synopsis);
            giDesc.setVisibility(a.synopsis.isEmpty() ? View.GONE : View.VISIBLE);
            giNext.setText(n != null ? "Up Next  ·  " + Ui.time(n.start, h24) + "  " + n.title : "");
            giNext.setVisibility(n != null ? View.VISIBLE : View.GONE);
        } else {
            giTitle.setText(names[pos]);
            giMeta.setText(ch != null && ch.iptv ? "No guide for this channel" : "No guide data for this channel");
            giMeta.setVisibility(View.VISIBLE);
            giProgress.setVisibility(View.GONE);
            giDesc.setVisibility(View.GONE);
            giNext.setVisibility(View.GONE);
        }
    }

    private void showChannelGuide() {
        if (guide == null) return;
        banner.animate().alpha(0f).setDuration(150).start();
        if (controls != null) { handler.removeCallbacks(hideControls); controls.setVisibility(View.GONE); }
        ((ChannelGuideAdapter) guideList.getAdapter()).notifyDataSetChanged();
        guide.setVisibility(View.VISIBLE);
        if (guideInfo != null) { updateGuideInfo(index); guideInfo.setVisibility(View.VISIBLE); }
        if (guideClock != null) { guideClock.setText(Ui.clock(prefs.clock24h())); guideClock.setVisibility(View.VISIBLE); }
        guideList.requestFocus();
        guideList.setSelectionFromTop(index, Ui.dp(this, 200)); // current channel, a little below the top
        bumpGuideTimer();
    }

    private void hideChannelGuide() {
        if (guide == null || guide.getVisibility() != View.VISIBLE) return;
        handler.removeCallbacks(hideGuide);
        guide.setVisibility(View.GONE);
        if (guideInfo != null) guideInfo.setVisibility(View.GONE);
        if (guideClock != null) guideClock.setVisibility(View.GONE);
        view.requestFocus();
    }

    private boolean guideShowing() { return guide != null && guide.getVisibility() == View.VISIBLE; }

    private void bumpGuideTimer() {
        if (guideClock != null) guideClock.setText(Ui.clock(prefs.clock24h()));
        handler.removeCallbacks(hideGuide);
        handler.postDelayed(hideGuide, GUIDE_IDLE_MS);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // CC button (on remotes that have one): subtitles on or off. (Android TV keeps the menu key to
        // itself, so on the Shield remote it's hold OK instead.)
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_CAPTIONS) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) toggleSubtitles();
            return true;
        }
        if (fullGuideShowing()) {
            bumpFullGuideTimer();
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_GUIDE) {
                // Only a press made while it's open closes it (not the release of the press that opened it).
                if (e.getAction() == KeyEvent.ACTION_DOWN) fullGuideCloseArmed = true;
                else if (e.getAction() == KeyEvent.ACTION_UP && fullGuideCloseArmed) hideFullGuide();
                return true;
            }
            if (fullGuide.handleKey(e)) return true;
            // Volume still works; nothing else reaches the player underneath.
            if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE)
                return super.dispatchKeyEvent(e);
            return true;
        }
        if (guideShowing()) {
            bumpGuideTimer();
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideChannelGuide();
                return true;
            }
            // Right: the Full Guide, on the channel you're hovered over.
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_GUIDE) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    int pos = guideList.getSelectedItemPosition();
                    showFullGuide(nums[pos >= 0 && pos < nums.length ? pos : index]);
                }
                return true;
            }
            // Left closes the list without changing anything.
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideChannelGuide();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** One row per channel: number, name, what's on now (+ progress). The playing channel is marked. */
    private final class ChannelGuideAdapter extends android.widget.BaseAdapter {
        int selected = -1;

        @Override public int getCount() { return nums.length; }
        @Override public Object getItem(int i) { return nums[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, android.view.ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(PlayerActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = Ui.dp(PlayerActivity.this, 14);
                row.setPadding(pad, Ui.dp(PlayerActivity.this, 10), pad, Ui.dp(PlayerActivity.this, 10));
                TextView num = Ui.text(PlayerActivity.this, 20, Ui.TEXT, true);
                row.addView(num, Ui.lp(Ui.dp(PlayerActivity.this, 70), -2));
                LinearLayout mid = new LinearLayout(PlayerActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                mid.addView(Ui.text(PlayerActivity.this, 13, Ui.SUB, true));
                mid.addView(Ui.text(PlayerActivity.this, 17, Ui.TEXT, true));
                Ui.ProgressLine pl = new Ui.ProgressLine(PlayerActivity.this, Ui.ACCENT);
                mid.addView(pl, Ui.margins(Ui.lp(Ui.dp(PlayerActivity.this, 160), Ui.dp(PlayerActivity.this, 3)), 0, Ui.dp(PlayerActivity.this, 6), 0, 0));
                row.addView(mid, Ui.lpWeight(0, -2, 1));
            }
            boolean playing = i == index;
            TextView num = (TextView) row.getChildAt(0);
            LinearLayout mid = (LinearLayout) row.getChildAt(1);
            TextView name = (TextView) mid.getChildAt(0), show = (TextView) mid.getChildAt(1);
            Ui.ProgressLine pl = (Ui.ProgressLine) mid.getChildAt(2);
            num.setText(nums[i]);
            Channel ch = ChannelStore.get().byNumber(nums[i]);
            long now = System.currentTimeMillis() / 1000L;
            Channel.Airing a = ch != null ? ch.now(now) : null;
            // With a guide: small channel name, then the show. Without one: just the channel's
            // name (no dash), with the playing marker in front of it.
            String dot = playing ? "● " : "";
            name.setText(dot + names[i]);
            name.setVisibility(a != null ? View.VISIBLE : View.GONE);
            show.setText(a != null ? a.title : dot + names[i]);
            pl.setProgress(a != null ? a.progress(now) : 0);
            pl.setVisibility(a != null ? View.VISIBLE : View.GONE);
            // The highlight is an outline, so text keeps its colors; the playing channel's name is red.
            num.setTextColor(Ui.TEXT);
            show.setTextColor(a == null && playing ? Ui.LIVE_RED : Ui.TEXT);
            name.setTextColor(playing ? Ui.LIVE_RED : Ui.SUB);
            return row;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (nums == null) return;
        player = new ExoPlayer.Builder(this, renderers())
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, extractors()))
                .build();
        // Which decoder actually plays the video (shows whether Dolby Vision or its HDR10 base layer is used).
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String name, long initializedMs, long durationMs) {
                AppLog.i("Player", "Video decoder: " + name + (playingDv
                        ? (name.toLowerCase(java.util.Locale.US).contains("dovi") || name.toLowerCase(java.util.Locale.US).contains("dolby")
                           ? " (Dolby Vision)" : " (HDR10 base layer)") : ""));
            }
            @Override public void onAudioDecoderInitialized(EventTime t, String name, long initializedMs, long durationMs) {
                AppLog.i("Player", "Audio decoder: " + name + " (decoding to PCM)");
            }
            @Override public void onAudioTrackInitialized(EventTime t, androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig c) {
                String enc;
                switch (c.encoding) {
                    case android.media.AudioFormat.ENCODING_DOLBY_TRUEHD: enc = "TrueHD bitstream (Atmos if the track has it)"; break;
                    case android.media.AudioFormat.ENCODING_E_AC3_JOC: enc = "E-AC3 Atmos bitstream"; break;
                    case android.media.AudioFormat.ENCODING_E_AC3: enc = "E-AC3 bitstream"; break;
                    case android.media.AudioFormat.ENCODING_AC3: enc = "AC3 bitstream"; break;
                    case android.media.AudioFormat.ENCODING_DTS: enc = "DTS bitstream"; break;
                    case android.media.AudioFormat.ENCODING_DTS_HD: enc = "DTS-HD bitstream"; break;
                    default: enc = "PCM (decoded)";
                }
                AppLog.i("Player", "Audio output: " + enc + " · " + c.sampleRate + " Hz");
            }
        });
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                if (vlc != null) return; // VLC has taken over
                AppLog.e("Player", (plexMode ? serverName() + " item " : "Channel " + nums[index] + " " + names[index] + " ")
                        + "error " + e.getErrorCodeName(), e.getCause() != null ? e.getCause() : e);
                boolean decoderProblem = e.errorCode >= 4000 && e.errorCode < 6000; // decoding / audio output
                if (plexMode) {
                    // Dolby Vision decoder failed: retry with the HDR10 base layer, still in ExoPlayer
                    // (keeps Atmos), before giving up to VLC.
                    if (decoderProblem && playingDv && !dvBaseLayerOnly) {
                        dvBaseLayerOnly = true;
                        AppLog.w("Player", "Dolby Vision decoder failed (" + e.getErrorCodeName()
                                + ") · retrying with the HDR10 base layer");
                        player.seekTo(Math.max(0, player.getCurrentPosition()));
                        player.prepare();
                        return;
                    }
                    // Video or audio this device can't handle: VLC has its own decoders.
                    if (decoderProblem) { switchPlexToVlc(e.getErrorCodeName()); return; }
                    plexFailed = true;
                    showError("This file can't play in the built-in player (" + e.getErrorCodeName() + ").\n\n"
                            + (jelly == null ? "Press OK to open it in the Plex app, or Back to return." : "Press Back to return."));
                    return;
                }
                if (decoderProblem) { switchLiveToVlc(e.getErrorCodeName()); return; }
                if (direct && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Paused or rewound past the start of the stream's live window: rejoin at live.
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                retryOrFail(e.getErrorCodeName());
            }

            @Override public void onTracksChanged(Tracks tracks) {
                if (vlc != null) return;
                logVideoFormat(tracks);
                logAvCapabilities(tracks);
                for (Tracks.Group g : tracks.getGroups())
                    if (g.getType() == C.TRACK_TYPE_VIDEO && g.length > 0) {
                        androidx.media3.common.Format vf = g.getTrackFormat(0);
                        playingDv = androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(vf.sampleMimeType);
                        if (playingDv || (vf.colorInfo != null && (vf.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                                || vf.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG))) playedHdr = true;
                    }
                if (tracks.isEmpty()) return;
                // A video or audio track this device has no decoder for: hand over to VLC
                // (otherwise the picture stays black, or the sound is missing).
                String missing = unsupported(tracks, C.TRACK_TYPE_VIDEO, "video")
                        + unsupported(tracks, C.TRACK_TYPE_AUDIO, "audio");
                if (plexMode && missing.isEmpty()) { audioWorked = true; handler.removeCallbacks(vlcIfStillMissing); }
                if (plexMode && !missing.isEmpty() && !missing.contains("video")
                        && (audioWorked || System.currentTimeMillis() - displaySwitchAt < 20_000L)) {
                    // The audio played a moment ago: the TV / receiver is re-syncing (e.g. after a
                    // frame-rate switch) and briefly reports no bitstream support. Wait for it to come back.
                    AppLog.w("Player", "Audio output unavailable for a moment (" + missing.trim() + ") · waiting for the TV / receiver");
                    stillMissing = missing.trim();
                    handler.removeCallbacks(vlcIfStillMissing);
                    handler.postDelayed(vlcIfStillMissing, 12_000L);
                    return;
                }
                if (!missing.isEmpty()) {
                    AppLog.w("Player", (plexMode ? serverName() + " item" : "Channel " + nums[index])
                            + " has no built-in decoder for " + missing.trim());
                    if (plexMode) switchPlexToVlc("no decoder for " + missing.trim());
                    else switchLiveToVlc("no decoder for " + missing.trim());
                }
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                // The Plex banner is first drawn before playback starts; refresh it so it doesn't
                // stay stuck on "0:00 · Paused" (and so it hides itself once playing).
                if (plexMode && vlc == null && error.getVisibility() != View.VISIBLE) showPlexBanner();
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (vlc != null) return;
                if (state == Player.STATE_BUFFERING) spinner.setVisibility(View.VISIBLE);
                else if (state == Player.STATE_READY) spinner.setVisibility(View.GONE);
                if (state == Player.STATE_READY) { retries = 0; error.setVisibility(View.GONE); }
                if (state == Player.STATE_READY && tuneStartedAt > 0) {
                    AppLog.i("Player", (plexMode ? serverName() + " item" : "Channel " + nums[index]) + " playing after "
                            + (System.currentTimeMillis() - tuneStartedAt) + " ms");
                    tuneStartedAt = 0;
                }
                if (plexMode && state == Player.STATE_ENDED) { reportPlex("stopped"); finish(); }
                // A live tuner stream never really ends: the connection dropped, so retune.
                if (!plexMode && state == Player.STATE_ENDED && !undecodable) retryOrFail("STREAM_ENDED");
            }
        });
        view.setPlayer(player);
        // Subtitles in the viewer's own caption style (Android settings > Accessibility > Captions).
        if (view.getSubtitleView() != null) {
            view.getSubtitleView().setUserDefaultStyle();
            view.getSubtitleView().setUserDefaultTextSize();
        }
        applySubtitles(false);
        if (plexMode) startPlex(); else tune(index, true);
        handler.postDelayed(alertCheck, 5000);
    }

    private void startPlex() {
        // Resume where Plex says you left off (minus a few seconds of context).
        long start = Math.max(0, plexStartMs - 5000);
        tuneStartedAt = System.currentTimeMillis();
        AppLog.i("Player", serverName() + ": " + nums[0] + " from " + start / 1000 + " s");
        // Separate subtitle files (e.g. Movie.en.srt) come from the Plex server; tracks inside the file
        // need nothing extra. A quick lookup on the home network, then play.
        net.execute(() -> {
            List<PlexClient.SubFile> subs = java.util.Collections.emptyList();
            try {
                subs = jelly != null ? jelly.subtitleFiles(plexKey) : plex.subtitleFiles(plexBase, plexToken, plexKey);
            } catch (Exception e) {
                AppLog.w("Player", serverName() + ": couldn't list subtitle files (" + e.getClass().getSimpleName() + ")");
            }
            final List<PlexClient.SubFile> files = subs;
            final float serverFps = jelly != null ? jelly.lastFrameRate : plex.lastFrameRate;
            boolean converted = chooseOriginalOrConverted();
            if (!converted) checkProfile7(jelly != null ? jelly.lastDvProfile : plex.lastDvProfile);
            handler.post(() -> switchDisplayThen(serverFps, () -> {
                if (player == null || vlc != null) return; // closed, or already handed to VLC
                MediaItem.Builder mi = new MediaItem.Builder().setUri(Uri.parse(urls[0]));
                if (converted) mi.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
                List<MediaItem.SubtitleConfiguration> cfg = new java.util.ArrayList<>();
                for (PlexClient.SubFile f : files) {
                    cfg.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(f.url))
                            .setMimeType(f.mime).setLanguage(f.language.isEmpty() ? null : f.language)
                            .setLabel(f.label).build());
                }
                if (!cfg.isEmpty()) {
                    mi.setSubtitleConfigurations(cfg);
                    AppLog.i("Player", serverName() + ": " + cfg.size() + " subtitle file" + (cfg.size() == 1 ? "" : "s"));
                }
                player.setMediaItem(mi.build(), start);
                player.prepare();
                player.setPlayWhenReady(true);
            }));
        });
        bannerChannel.setText(serverName().toUpperCase(java.util.Locale.US));
        bannerChannel.setTextColor(jelly == null ? Ui.PLEX : JellyfinClient.EMBY.equals(mediaKind) ? 0xFF52B54B : 0xFFAA5CC3);
        bannerTitle.setText(nums[0]);
        showPlexBanner();
        handler.postDelayed(reporter, 10_000);
    }

    private void showPlexBanner() {
        if (player == null && vlc == null) return;
        long pos = posMs();
        long dur = durMs();
        bannerMeta.setText((names[0].isEmpty() ? "" : names[0] + "  ·  ") + fmt(pos) + " / " + fmt(dur)
                + (playingNow() ? "" : "   ❚❚ Paused"));
        bannerProgress.setProgress(dur > 0 ? pos / (float) dur : 0);
        bannerProgress.setVisibility(View.VISIBLE);
        banner.animate().cancel();
        banner.setAlpha(1f);
        handler.removeCallbacks(hideBanner);
        if (playingNow()) handler.postDelayed(hideBanner, 4000);
    }

    private static String fmt(long ms) {
        long t = Math.max(0, ms / 1000);
        return t >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
                : String.format(java.util.Locale.US, "%d:%02d", t / 60, t % 60);
    }

    private String serverName() { return MediaStore.sourceName(mediaKind); }

    private void reportPlex(String state) {
        if (!plexMode || (player == null && vlc == null)) return;
        if ("stopped".equals(state) && transcodeSession != null) {
            final String session = transcodeSession;
            transcodeSession = null;
            final JellyfinClient jc = jelly;
            net.execute(() -> { if (jc != null) jc.stopTranscode(session); else plex.stopTranscode(plexBase, plexToken, session); });
        }
        final long pos = posMs();
        final long dur = durMs();
        if (jelly != null) {
            // The first report starts the session; the server marks it watched near the end by itself.
            final String st = "stopped".equals(state) ? "stopped" : !jellyStarted ? "playing" : state;
            jellyStarted = !"stopped".equals(state); // after a stop, the next report starts a new session
            final JellyfinClient jc = jelly;
            final String id = plexKey, msid = mediaSourceId;
            net.execute(() -> jc.report(st, id, msid, pos));
            return;
        }
        final boolean finished = "stopped".equals(state) && dur > 0 && pos > dur * 0.9;
        net.execute(() -> {
            plex.timeline(plexBase, plexToken, plexKey, state, pos, dur);
            if (finished) plex.scrobble(plexBase, plexToken, plexKey);
        });
    }

    private boolean onPlexKey(int keyCode) {
        if (plexFailed && jelly == null && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            AppLog.i("Plex", "Falling back to the Plex app for " + nums[0]);
            PlexStore.openInPlexApp(this, plexKey, plexStartMs);
            finish();
            return true;
        }
        if (player == null && vlc == null) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                setWantPlay(!wantPlay());
                handler.postDelayed(this::showPlexBanner, 150);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekToMs(Math.max(0, posMs() - 10_000));
                showPlexBanner();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekToMs(posMs() + 30_000);
                showPlexBanner();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_INFO:
                showPlexBanner();
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_MENU:
                toggleSubtitles();
                return true;
            default:
                return false;
        }
    }

    /**
     * Stream readers that always look for US broadcast captions (CEA-608 / 708) in TV streams. By
     * default they only look when the stream's header announces captions, and HDHomeRun streams don't.
     */
    private static androidx.media3.extractor.DefaultExtractorsFactory extractors() {
        return new androidx.media3.extractor.DefaultExtractorsFactory().setTsSubtitleFormats(java.util.Arrays.asList(
                new androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608)
                        .setAccessibilityChannel(1).build(),
                new androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA708)
                        .setAccessibilityChannel(1).build()));
    }

    // ---- Dolby Vision ----
    // Stock ExoPlayer only knows decoders for single-layer Dolby Vision (profiles 5 / 8 / 9). For a
    // dual-layer UHD Blu-ray remux (profile 7, "dvhe.07") it finds no decoder, so the whole title used
    // to go to VLC, which also decoded the TrueHD / E-AC3 Atmos to plain PCM. Instead:
    //  1. Try the Shield's own Dolby Vision decoder (what the Plex app uses), even if it doesn't list
    //     the exact profile.
    //  2. If that decoder won't start, or errors, play the HEVC base layer (HDR10) in ExoPlayer.
    //  3. VLC only as a last resort.
    // Steps 1-2 stay in ExoPlayer, so Atmos keeps bitstreaming to the receiver either way.

    /** After the Dolby Vision decoder failed on this title: base layer (HDR10) only. */
    private boolean dvBaseLayerOnly;
    /** The playing title's video is Dolby Vision (set when its tracks are known). */
    private boolean playingDv;
    /** The server is converting this title for us (null = playing the original file). */
    private volatile String transcodeSession;

    /** The TV's largest picture (its highest-resolution mode). */
    private android.view.Display.Mode tvMaxMode() {
        android.view.Display.Mode best = display().getMode();
        for (android.view.Display.Mode m : display().getSupportedModes())
            if ((long) m.getPhysicalWidth() * m.getPhysicalHeight() > (long) best.getPhysicalWidth() * best.getPhysicalHeight()) best = m;
        return best;
    }

    /** This device has a hardware or software decoder for the video at its full size. */
    private static boolean canDecode(String codec, int w, int h) {
        String mime = codec.startsWith("hevc") || codec.startsWith("h265") ? "video/hevc"
                : codec.startsWith("h264") || codec.startsWith("avc") ? "video/avc"
                : codec.startsWith("av1") ? "video/av01" : codec.startsWith("vp9") ? "video/x-vnd.on2.vp9"
                : codec.startsWith("mpeg2") ? "video/mpeg2" : null;
        if (mime == null || w <= 0 || h <= 0) return true; // not known: try it
        android.media.MediaFormat f = android.media.MediaFormat.createVideoFormat(mime, w, h);
        return new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
    }

    /**
     * Original file or the server's converted version? The original is best whenever this device can decode it
     * and the TV can show it: a 4K file on a 1080p TV is decoded at 4K and scaled down by the device, which
     * looks better than a re-encoded 1080p copy. Converted (sized for the TV, HDR tone-mapped to SDR) only when
     *  - the device can't decode the video at its size (e.g. 4K on a 1080p-only streaming stick), or
     *  - the video is HDR / Dolby Vision and the TV has no HDR (it would look washed out).
     * Runs on the network thread before playback; true when urls[0] now points at the converted version.
     */
    private boolean chooseOriginalOrConverted() {
        int w = jelly != null ? jelly.lastWidth : plex.lastWidth, h = jelly != null ? jelly.lastHeight : plex.lastHeight;
        String codec = jelly != null ? jelly.lastCodec : plex.lastCodec;
        boolean hdr = jelly != null ? jelly.lastHdr : plex.lastHdr;
        android.view.Display.Mode tv = tvMaxMode();
        int tvW = tv.getPhysicalWidth(), tvH = tv.getPhysicalHeight();
        boolean tvHasHdr = tvHdr10 || tvHlg || tvDolbyVision;
        boolean decodes = canDecode(codec, w, h);
        String video = (w > 0 ? w + "x" + h + " " : "") + (codec.isEmpty() ? "" : codec.toUpperCase(java.util.Locale.US) + " ") + (hdr ? "HDR" : "SDR");
        String tvDesc = "TV up to " + tvW + "x" + tvH + (tvHasHdr ? " with HDR" : ", no HDR");
        String why = !decodes ? "this device can't decode " + w + "x" + h + " " + codec
                : hdr && !tvHasHdr ? "HDR video on a TV without HDR" : null;
        if (why == null) {
            AppLog.i("Player", "Video " + video + " · " + tvDesc + " · playing the original file"
                    + (w > tvW ? " (this device scales it to " + tvW + "x" + tvH + ")" : ""));
            return false;
        }
        int maxW = Math.min(tvW, 3840), maxH = Math.min(tvH, 2160);
        if (!decodes) { maxW = Math.min(maxW, 1920); maxH = Math.min(maxH, 1080); }
        String session = java.util.UUID.randomUUID().toString();
        try {
            urls[0] = jelly != null ? jelly.transcodeUrl(plexKey, mediaSourceId, maxW, maxH, session)
                    : plex.transcodeUrl(plexBase, plexToken, plexKey, maxW, maxH, session);
        } catch (Exception e) {
            AppLog.w("Player", "Couldn't build the converted-version link (" + e.getClass().getSimpleName() + ") · playing the original file");
            return false;
        }
        transcodeSession = session;
        AppLog.i("Player", "Video " + video + " · " + tvDesc + " · " + why + " · asking " + serverName()
                + " for a converted version up to " + maxW + "x" + maxH
                + (hdr && !tvHasHdr ? " (tone-mapped to SDR if the server has tone mapping turned on)" : ""));
        return true;
    }

    /** This profile 7 title has a full enhancement layer (FEL): play its HDR10 base layer. Set before playback. */
    private volatile boolean p7Fel;

    /**
     * Profile 7 in Automatic mode: is it a FEL disc (flickers in Dolby Vision on the Shield) or MEL (fine)?
     * Checked once per title from two short reads of the file, then remembered. Runs on the network thread.
     */
    private void checkProfile7(int dvProfile) {
        String mode = prefs.dvProfile7Mode();
        if (dvProfile != 7 || !Prefs.DV7_AUTO.equals(mode)) return;
        String key = mediaKind + ":" + plexKey;
        String known = prefs.dvElKind(key);
        if (!known.isEmpty()) {
            p7Fel = "fel".equals(known);
            AppLog.i("Player", "Dolby Vision profile 7 · " + (p7Fel ? "full" : "minimal") + " enhancement layer (checked before)");
            return;
        }
        long t0 = System.currentTimeMillis();
        DvScan.Result r = DvScan.scan(urls[0]);
        AppLog.i("Player", "Dolby Vision profile 7 · " + (r.kind == DvScan.Kind.FEL ? "full enhancement layer (FEL)"
                : r.kind == DvScan.Kind.MEL ? "minimal enhancement layer (MEL)" : "couldn't tell FEL from MEL")
                + " · " + r.why + " · " + (System.currentTimeMillis() - t0) + " ms");
        if (r.kind == DvScan.Kind.UNKNOWN) return;
        p7Fel = r.kind == DvScan.Kind.FEL;
        prefs.setDvElKind(key, p7Fel ? "fel" : "mel");
    }

    /** What the TV says it can show (read when the player opens). */
    private boolean tvDolbyVision, tvHdr10, tvHlg;
    private boolean loggedTvDvFallback;

    /** Read the TV's HDR support from the display (Android reports what the TV's EDID lists). */
    @SuppressWarnings("deprecation")
    private void readTvHdr() {
        android.view.Display.HdrCapabilities caps = display().getHdrCapabilities();
        StringBuilder sb = new StringBuilder();
        if (caps != null) for (int t : caps.getSupportedHdrTypes()) {
            switch (t) {
                case android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: tvDolbyVision = true; sb.append("Dolby Vision, "); break;
                case android.view.Display.HdrCapabilities.HDR_TYPE_HDR10: tvHdr10 = true; sb.append("HDR10, "); break;
                case android.view.Display.HdrCapabilities.HDR_TYPE_HLG: tvHlg = true; sb.append("HLG, "); break;
                case android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS: sb.append("HDR10+, "); break;
                default: break;
            }
        }
        AppLog.i("Player", "TV supports: " + (sb.length() == 0 ? "SDR only (no HDR)" : sb.substring(0, sb.length() - 2)));
    }

    /** Dolby Vision whose base layer is a normal HEVC picture: profile 7 (HDR10), 8.1 (HDR10), 8.4 (HLG). */
    private static boolean hasHevcBase(androidx.media3.common.Format f) {
        if (!androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType)) return false;
        android.util.Pair<Integer, Integer> p = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(f);
        return p != null && (p.first == android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb
                || p.first == android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt);
    }

    /** Decoders for a MIME type; Dolby Vision also gets the HEVC decoders, for its base layer. */
    private static final androidx.media3.exoplayer.mediacodec.MediaCodecSelector DV_SELECTOR = (mime, secure, tunneling) -> {
        List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> l =
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling);
        if (!androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(mime)) return l;
        List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> out = new java.util.ArrayList<>(l);
        out.addAll(androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(
                androidx.media3.common.MimeTypes.VIDEO_H265, secure, tunneling));
        return out;
    };

    private androidx.media3.exoplayer.DefaultRenderersFactory renderers() {
        return new androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            @Override protected void buildVideoRenderers(Context context, int extensionRendererMode,
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector selector, boolean enableDecoderFallback,
                    Handler eventHandler, androidx.media3.exoplayer.video.VideoRendererEventListener eventListener,
                    long allowedVideoJoiningTimeMs, java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
                out.add(new DvVideoRenderer(context, selector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                        eventHandler, eventListener));
            }
        }.setMediaCodecSelector(DV_SELECTOR).setEnableDecoderFallback(true);
    }

    /** The regular video renderer, except that Dolby Vision tries the real DV decoder before HEVC. */
    private final class DvVideoRenderer extends androidx.media3.exoplayer.video.MediaCodecVideoRenderer {
        DvVideoRenderer(Context c, androidx.media3.exoplayer.mediacodec.MediaCodecSelector s, long joining,
                        boolean fallback, Handler h, androidx.media3.exoplayer.video.VideoRendererEventListener l) {
            super(c, s, joining, fallback, h, l, 50);
        }

        /** Times the Dolby Vision decoder has been configured for this title (1st: as profile 7, then as 8.1). */
        private int dvConfigAttempt;

        // Profile 7: ExoPlayer refuses it outright (the Shield's DV decoder only lists P4/P5/P8/P9, and
        // ExoPlayer won't let an HEVC decoder take a P7 base layer). The Shield's decoder plays P7 for
        // Kodi and Plex anyway, so answer the "can you play this?" question as for profile 8.
        @Override protected int supportsFormat(androidx.media3.exoplayer.mediacodec.MediaCodecSelector s,
                androidx.media3.common.Format format) throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
            return super.supportsFormat(s, isDvProfile7(format) ? asProfile8(format) : format);
        }

        @Override protected List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> getDecoderInfos(
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector s, androidx.media3.common.Format format,
                boolean secure) throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
            List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> sorted = super.getDecoderInfos(s, format, secure);
            if (!androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) return sorted;
            // ExoPlayer puts decoders that list the exact profile first, which for profile 7 means
            // HEVC (base layer only). Put the Dolby Vision decoders first instead.
            List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> dv = new java.util.ArrayList<>(), base = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : sorted) {
                if (!seen.add(i.name + "|" + i.mimeType)) continue;
                (androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(i.mimeType) ? dv : base).add(i);
            }
            boolean tvNoDv = !tvDolbyVision && hasHevcBase(format);
            String dv7 = prefs.dvProfile7Mode();
            boolean p7Hdr10 = isDvProfile7(format)
                    && (Prefs.DV7_HDR10.equals(dv7) || (Prefs.DV7_AUTO.equals(dv7) && p7Fel));
            if ((dvBaseLayerOnly || tvNoDv || p7Hdr10) && !base.isEmpty()) {
                if (!dvBaseLayerOnly && !loggedTvDvFallback) {
                    loggedTvDvFallback = true;
                    AppLog.i("Player", tvNoDv
                            ? "The TV doesn't support Dolby Vision · playing the HDR10 / HLG base layer"
                            : Prefs.DV7_AUTO.equals(dv7)
                            ? "Dolby Vision profile 7 with a full enhancement layer · playing the HDR10 base layer (it flickers in Dolby Vision on the Shield)"
                            : "Dolby Vision profile 7 · playing the HDR10 base layer (Settings → Playback)");
                }
                return base;
            }
            if (!tvDolbyVision && !loggedTvDvFallback) {
                loggedTvDvFallback = true;
                AppLog.w("Player", "The TV doesn't support Dolby Vision, and this file (profile 5) has no HDR10 version · "
                        + "trying the Dolby Vision decoder anyway (colors may look wrong)");
            }
            dvConfigAttempt = 0;
            // Profile 7: offer each DV decoder twice, first configured as profile 7, then as 8.1.
            if (isDvProfile7(format)) {
                List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> twice = new java.util.ArrayList<>();
                for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : dv) { twice.add(i); twice.add(i); }
                dv = twice;
            }
            dv.addAll(base);
            return dv;
        }

        @Override protected android.media.MediaFormat getMediaFormat(androidx.media3.common.Format format,
                String codecMimeType, CodecMaxValues max, float rate, boolean noPostProcess, int tunnelingSession) {
            android.media.MediaFormat mf = super.getMediaFormat(format, codecMimeType, max, rate, noPostProcess, tunnelingSession);
            // Base layer on an HEVC decoder: ask for HEVC Main 10, not the Dolby Vision profile number.
            if (androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                    && androidx.media3.common.MimeTypes.VIDEO_H265.equals(codecMimeType))
                mf.setInteger(android.media.MediaFormat.KEY_PROFILE,
                        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
            // Profile 7 on the Dolby Vision decoder: as profile 7 first; if that's refused, as 8.1.
            if (isDvProfile7(format) && androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(codecMimeType)) {
                int attempt = dvConfigAttempt++;
                if (attempt > 0) mf.setInteger(android.media.MediaFormat.KEY_PROFILE,
                        android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt);
                AppLog.i("Player", "Dolby Vision profile 7 · opening the Dolby Vision decoder as profile "
                        + (attempt > 0 ? "8.1 (retry)" : "7"));
            }
            return mf;
        }
    }

    private static boolean isDvProfile7(androidx.media3.common.Format f) {
        if (!androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType)) return false;
        android.util.Pair<Integer, Integer> p = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(f);
        return p != null && p.first == android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb;
    }

    /** The same format, described as Dolby Vision profile 8 ("dvhe.07.06" → "dvhe.08.06"). */
    private static androidx.media3.common.Format asProfile8(androidx.media3.common.Format f) {
        String c = f.codecs == null ? "" : f.codecs;
        String p8 = c.matches("(?i)(dvhe|dvh1)\\.07.*") ? c.substring(0, 5) + "08" + c.substring(7) : "dvhe.08.06";
        return f.buildUpon().setCodecs(p8).build();
    }

    /** "P5 P8" etc.: the Dolby Vision profiles a decoder says it handles. */
    private static String dvProfiles(androidx.media3.exoplayer.mediacodec.MediaCodecInfo info) {
        int[] bits = {0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80, 0x100, 0x200, 0x400};
        String[] names = {"P0", "P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10"};
        java.util.TreeSet<Integer> have = new java.util.TreeSet<>();
        for (android.media.MediaCodecInfo.CodecProfileLevel pl : info.getProfileLevels())
            for (int i = 0; i < bits.length; i++) if (pl.profile == bits[i]) have.add(i);
        StringBuilder sb = new StringBuilder();
        for (int i : have) sb.append(sb.length() > 0 ? " " : "").append(names[i]);
        return sb.length() > 0 ? sb.toString() : "none listed";
    }

    /** Hold OK, a CC button, or the CC touch button: subtitles on or off, remembered for next time. */
    private void toggleSubtitles() {
        subsOn = !subsOn;
        prefs.setSubtitles(subsOn);
        applySubtitles(true);
    }

    /** Turns subtitle / caption tracks on (preferring this device's language, then English) or off. */
    private void applySubtitles(boolean announce) {
        boolean has;
        if (vlc != null) {
            vlc.setSubtitles(subsOn);
            has = vlc.hasSubtitles();
        } else if (player != null) {
            androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn);
            if (subsOn) b.setPreferredTextLanguages(java.util.Locale.getDefault().getLanguage(), "en")
                    .setSelectUndeterminedTextLanguage(true); // broadcast captions carry no language
            player.setTrackSelectionParameters(b.build());
            has = player.getCurrentTracks().containsType(C.TRACK_TYPE_TEXT);
        } else {
            return;
        }
        if (!announce) return;
        String what = plexMode ? "this video" : "this channel";
        android.widget.Toast.makeText(this, !subsOn ? "Subtitles off"
                : has ? "Subtitles on" : "Subtitles on · " + what + " has none right now", android.widget.Toast.LENGTH_SHORT).show();
        AppLog.i("Player", "Subtitles " + (subsOn ? "on" : "off") + (subsOn && !has ? " (none available)" : ""));
    }

    /** Release the player (and the tuner) whenever we're not on screen. */
    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        if (fullGuideShowing()) { fullGuide.stop(); fullGuide.setVisibility(View.GONE); }
        if (plexMode) {
            reportPlex("stopped");
            if (player != null || vlc != null) plexStartMs = posMs() + 5000; // resume here if reopened
        }
        releasePlayback();
        stopWaitingForDisplay();
        super.onStop();
    }

    private void releasePlayback() {
        releaseVlc();
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
        if (timeshift != null) { timeshift.stop(); timeshift = null; } // releases the tuner, deletes the buffer
    }

    // ---- Leaving without a flash of the home screen ----
    // With the Shield's "Match content frame rate" / "Match content dynamic range" on, the TV's
    // signal changes while a video plays (e.g. 23.976 Hz, or HDR / Dolby Vision). When the video
    // stops, the Shield switches back and the TV goes black for a moment while it resyncs. If the
    // home screen is drawn first, it shows, goes black, then comes back. So on leaving: stop the
    // video behind a black screen, wait for the switch back, and only then show the home screen.

    /** The display mode when the player opened (before any frame-rate switch). */
    private android.view.Display.Mode startMode;
    /** The video played was HDR or Dolby Vision (the TV resyncs when it ends, even at the same frame rate). */
    private boolean playedHdr;
    private boolean exiting;
    private android.hardware.display.DisplayManager.DisplayListener displayWait;
    private static final long WAIT_MODE_MS = 3000, WAIT_HDR_MS = 1500, SETTLE_MS = 150;

    @SuppressWarnings("deprecation")
    private android.view.Display display() { return getWindowManager().getDefaultDisplay(); }

    @Override public void finish() {
        if (exiting) { super.finish(); return; }
        exiting = true;
        android.view.Display.Mode now = display().getMode();
        boolean modeChanged = startMode != null && now.getModeId() != startMode.getModeId();
        if (!modeChanged && !playedHdr) { super.finish(); return; }

        // Black screen, then stop the video (this is what makes the Shield switch back).
        View black = new View(this);
        black.setBackgroundColor(0xFF000000);
        root.addView(black, new FrameLayout.LayoutParams(-1, -1));
        reportPlex("stopped");
        releasePlayback();
        view.setVisibility(View.GONE); // drop the video surface too
        if (frameRateSet) { // let go of our frame-rate mode so the TV switches back
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = 0;
            getWindow().setAttributes(lp);
        }

        final long started = System.currentTimeMillis();
        final String why = modeChanged
                ? "display " + describe(now) + " → back to " + describe(startMode)
                : "HDR / Dolby Vision ended";
        android.hardware.display.DisplayManager dm = getSystemService(android.hardware.display.DisplayManager.class);
        Runnable done = () -> {
            if (isFinishing() || isDestroyed()) return;
            stopWaitingForDisplay();
            AppLog.i("Player", "Leaving after " + (System.currentTimeMillis() - started) + " ms (" + why + ")");
            super.finish();
            overridePendingTransition(0, 0);
        };
        displayWait = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                android.view.Display.Mode m = display().getMode();
                AppLog.i("Player", "Display changed after " + (System.currentTimeMillis() - started) + " ms · " + describe(m));
                // Switched back (or, for HDR at the same rate, any change): give it a moment, then leave.
                if (!modeChanged || m.getModeId() == startMode.getModeId()) {
                    handler.removeCallbacks(done);
                    handler.postDelayed(done, SETTLE_MS);
                }
            }
        };
        dm.registerDisplayListener(displayWait, handler);
        handler.postDelayed(done, modeChanged ? WAIT_MODE_MS : WAIT_HDR_MS); // in case nothing is reported
    }

    // ---- Frame rate ----
    // A 23.976 fps film shown at 60 Hz needs 3:2 pulldown: every other frame is held longer, which shows
    // as judder / shimmer in pans and fine detail (skies, grain). For movies and shows, ask for a TV mode at
    // the film's own rate (same resolution), the way Kodi does, instead of relying on the Shield's setting.

    private boolean frameRateSet;
    /** When we last changed the TV's mode (its audio link drops for a moment then). */
    private long displaySwitchAt;
    /** This title's audio has played in ExoPlayer (so a later "no decoder" is the HDMI link re-syncing). */
    private boolean audioWorked;
    private String stillMissing = "";
    private final Runnable vlcIfStillMissing = () -> {
        if (player == null || vlc != null || isFinishing()) return;
        AppLog.w("Player", "Audio output didn't come back · switching to VLC");
        switchPlexToVlc("no decoder for " + stillMissing);
    };

    /** The TV mode for this frame rate at the current resolution, or null (unknown, none, or already there). */
    private android.view.Display.Mode modeFor(float fps) {
        android.view.Display.Mode cur = display().getMode();
        if (fps <= 0 || fits(cur.getRefreshRate(), fps)) return null;
        android.view.Display.Mode best = null;
        for (android.view.Display.Mode m : display().getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            if (!fits(m.getRefreshRate(), fps)) continue;
            if (best == null || Math.abs(m.getRefreshRate() - fps) < Math.abs(best.getRefreshRate() - fps)) best = m;
        }
        return best;
    }

    /**
     * Before playback: when the server knows the frame rate, switch the TV first and start once it has
     * switched (as Kodi does), so the picture and the receiver's audio aren't interrupted mid-film.
     */
    private void switchDisplayThen(float fps, Runnable start) {
        android.view.Display.Mode cur = display().getMode();
        if (!prefs.matchFrameRate() || fps <= 0) { start.run(); return; }
        frameRateSet = true;
        android.view.Display.Mode best = modeFor(fps);
        String from = String.format(java.util.Locale.US, "Frame rate %.3f fps (from %s) · ", fps, serverName());
        if (best == null) {
            AppLog.i("Player", from + (fits(cur.getRefreshRate(), fps) ? "TV already at " + describe(cur)
                    : "no TV mode for it, staying at " + describe(cur)));
            start.run();
            return;
        }
        if (player != null) player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF);
        AppLog.i("Player", from + "switching TV " + describe(cur) + " → " + describe(best) + " before playing");
        final long asked = System.currentTimeMillis();
        displaySwitchAt = asked;
        final boolean[] started = {false};
        final android.hardware.display.DisplayManager dm = getSystemService(android.hardware.display.DisplayManager.class);
        final android.hardware.display.DisplayManager.DisplayListener[] l = new android.hardware.display.DisplayManager.DisplayListener[1];
        final Runnable go = () -> {
            if (started[0]) return;
            started[0] = true;
            dm.unregisterDisplayListener(l[0]);
            if (switchListener == l[0]) switchListener = null;
            AppLog.i("Player", "TV at " + describe(display().getMode()) + " after " + (System.currentTimeMillis() - asked) + " ms · starting");
            displaySwitchAt = System.currentTimeMillis();
            if (!isFinishing()) start.run();
        };
        final int target = best.getModeId();
        l[0] = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                // Switched: give the HDMI link (and the receiver's audio) a moment to settle.
                if (display().getMode().getModeId() == target) { handler.removeCallbacks(go); handler.postDelayed(go, 1500); }
            }
        };
        dm.registerDisplayListener(l[0], handler);
        switchListener = l[0];
        handler.postDelayed(go, 6000); // in case no change is reported
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = target;
        getWindow().setAttributes(lp);
    }

    private void matchFrameRate(float fps) {
        android.view.Display.Mode cur = display().getMode();
        if (frameRateSet) return;
        frameRateSet = true;
        if (!prefs.matchFrameRate()) {
            AppLog.i("Player", "Frame rate matching off in Settings · TV at " + describe(cur));
            return;
        }
        if (fps <= 0) {
            // Not in the file (common in MKV): measure it from the first few seconds of frames.
            measureFrameRate();
            return;
        }
        if (fits(cur.getRefreshRate(), fps)) {
            AppLog.i("Player", "TV already at " + describe(cur) + " for " + String.format(java.util.Locale.US, "%.3f", fps) + " fps");
            return;
        }
        android.view.Display.Mode best = null;
        for (android.view.Display.Mode m : display().getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            if (!fits(m.getRefreshRate(), fps)) continue;
            // Prefer the exact rate (23.976) over a multiple (47.952).
            if (best == null || Math.abs(m.getRefreshRate() - fps) < Math.abs(best.getRefreshRate() - fps)) best = m;
        }
        if (best == null) {
            AppLog.w("Player", "No TV mode for " + String.format(java.util.Locale.US, "%.3f", fps) + " fps at "
                    + cur.getPhysicalWidth() + "x" + cur.getPhysicalHeight() + " · staying at " + describe(cur));
            return;
        }
        // ExoPlayer's own request only allows seamless switches; ours replaces it.
        if (player != null) player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
        AppLog.i("Player", "Frame rate: " + String.format(java.util.Locale.US, "%.3f", fps) + " fps · switching TV "
                + describe(cur) + " → " + describe(best));
        final long asked = System.currentTimeMillis();
        displaySwitchAt = asked;
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            AppLog.i("Player", "TV now at " + describe(display().getMode()) + " (" + (System.currentTimeMillis() - asked) / 1000 + " s after asking)");
        }, 5000);
    }

    private androidx.media3.exoplayer.video.VideoFrameMetadataListener fpsProbe;

    /**
     * Frame rate from the video's own timestamps: ~3 s of frames, then the nearest standard rate.
     * MKV timestamps are whole milliseconds, so the average over many frames is what separates
     * 23.976 from 24.
     */
    private void measureFrameRate() {
        if (player == null) return;
        final long[] ts = new long[80];
        final int[] n = {0};
        fpsProbe = (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
            if (n[0] >= ts.length) return;
            if (n[0] > 0 && presentationTimeUs <= ts[n[0] - 1]) { n[0] = 0; } // seek / reorder: start over
            ts[n[0]++] = presentationTimeUs;
            if (n[0] < ts.length) return;
            // Typical gap (median), then the average of the normal gaps (skips any dropped frames).
            long[] gaps = new long[ts.length - 1];
            for (int i = 1; i < ts.length; i++) gaps[i - 1] = ts[i] - ts[i - 1];
            long[] sorted = gaps.clone();
            java.util.Arrays.sort(sorted);
            long median = sorted[sorted.length / 2];
            long sum = 0; int count = 0;
            for (long g : gaps) if (g > 0 && g < median * 1.5) { sum += g; count++; }
            final float measured = count > 0 ? 1_000_000f * count / sum : 0;
            handler.post(() -> {
                if (player != null && fpsProbe != null) player.clearVideoFrameMetadataListener(fpsProbe);
                fpsProbe = null;
                float std = standardRate(measured);
                AppLog.i("Player", String.format(java.util.Locale.US, "Measured frame rate %.3f fps", measured)
                        + (std > 0 ? String.format(java.util.Locale.US, " → %.3f", std) : " (not a standard rate)"));
                if (std > 0 && !isFinishing()) { frameRateSet = false; matchFrameRate(std); }
            });
        };
        player.setVideoFrameMetadataListener(fpsProbe);
        AppLog.i("Player", "Frame rate not in the file · measuring it from the video");
    }

    /** The nearest common video frame rate, if the measurement is within 0.3 % of it. */
    private static float standardRate(float fps) {
        float[] rates = {24000f / 1001, 24, 25, 30000f / 1001, 30, 50, 60000f / 1001, 60};
        float best = 0;
        for (float r : rates) if (Math.abs(fps - r) < r * 0.003f && (best == 0 || Math.abs(fps - r) < Math.abs(fps - best))) best = r;
        return best;
    }

    /** The refresh rate shows each frame for a whole number of refreshes (1 or 2), to within 0.05 % (so 24 Hz doesn't count as 23.976). */
    private static boolean fits(float hz, float fps) {
        for (int k = 1; k <= 2; k++) if (Math.abs(hz - fps * k) < fps * k * 0.0005f) return true;
        return false;
    }

    /** Listener of a frame-rate switch still waiting for the TV (let go of when the player stops). */
    private android.hardware.display.DisplayManager.DisplayListener switchListener;

    private void stopWaitingForDisplay() {
        if (switchListener != null) {
            getSystemService(android.hardware.display.DisplayManager.class).unregisterDisplayListener(switchListener);
            switchListener = null;
        }
        if (displayWait == null) return;
        getSystemService(android.hardware.display.DisplayManager.class).unregisterDisplayListener(displayWait);
        displayWait = null;
    }

    private static String describe(android.view.Display.Mode m) {
        return m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + " @ "
                + String.format(java.util.Locale.US, "%.3f", m.getRefreshRate()) + " Hz";
    }

    private void retryOrFail(String reason) {
        AppLog.w("Player", "Channel " + nums[index] + ": " + reason + (retries < 2 ? " · retune " + (retries + 1) + "/2" : " · giving up"));
        if (retries++ < 2) {
            handler.removeCallbacks(retune);
            handler.postDelayed(retune, 1500);
        } else {
            showError("Couldn't play " + nums[index] + " " + names[index] + "\n" + reason
                    + "\n\nAll tuners may be in use. Try another channel or press Back.");
        }
    }

    /**
     * Logs the video format, e.g. "video/avc 3840x2160 · BT2020/PQ · 8-bit". Flags HDR in an 8-bit
     * stream (an H.264 transcode that kept HDR tags), which shows up as badly wrong colors.
     */
    private void logVideoFormat(Tracks tracks) {
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO || g.length == 0) continue;
            androidx.media3.common.Format f = g.getTrackFormat(0);
            androidx.media3.common.ColorInfo ci = f.colorInfo;
            String color = ci == null ? "SDR (no color info)" : ci.toLogString();
            boolean hdr = ci != null && (ci.colorTransfer == C.COLOR_TRANSFER_ST2084 || ci.colorTransfer == C.COLOR_TRANSFER_HLG);
            boolean eightBit = ci != null && ci.lumaBitdepth == 8
                    || androidx.media3.common.MimeTypes.VIDEO_H264.equals(f.sampleMimeType);
            String what = plexMode ? serverName() + " item" : "Channel " + nums[index];
            AppLog.i("Player", what + " video " + f.sampleMimeType + " " + f.width + "x" + f.height + " · " + color
                    + (f.frameRate > 0 ? String.format(java.util.Locale.US, " · %.3f fps", f.frameRate) : " · frame rate not in the file"));
            if (plexMode) matchFrameRate(f.frameRate);
            if (plexMode && (hdr || androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType))
                    && !tvHdr10 && !tvHlg && !tvDolbyVision)
                AppLog.w("Player", what + " is HDR but the TV doesn't support HDR: it will look washed out. "
                        + "A server that converts HDR to SDR (e.g. Plex Pass tone mapping) is needed for this TV.");
            if (hdr && eightBit)
                AppLog.w("Player", what + " is 8-bit video tagged as HDR: colors will look wrong. "
                        + "Fix at the source (e.g. Tunarr: use HEVC 10-bit for 4K).");
            return;
        }
    }

    private boolean loggedCaps;

    /**
     * One-time, per title: the audio tracks and whether ExoPlayer can play each; what the connected
     * TV / receiver accepts as a bitstream (Dolby Atmos needs E-AC3-JOC or TrueHD listed here); and
     * whether the video is Dolby Vision. Explains why Atmos/DV may differ from the Plex app.
     */
    private void logAvCapabilities(Tracks tracks) {
        if (loggedCaps) return;
        loggedCaps = true;
        String what = plexMode ? serverName() + " item" : "Channel " + nums[index];

        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || g.length == 0) continue;
            androidx.media3.common.Format f = g.getTrackFormat(0);
            AppLog.i("Player", what + " audio " + f.sampleMimeType + " · " + f.channelCount + "ch"
                    + (f.codecs != null ? " · " + f.codecs : "")
                    + " · " + (g.isTrackSupported(0) ? "ExoPlayer plays it (passthrough or decode)"
                    : "ExoPlayer can't → would use VLC (decodes to PCM, loses Atmos)"));
        }

        // What the HDMI chain (TV or AV receiver) will bitstream. Atmos only passes through if
        // "Atmos(E-AC3-JOC)" or "TrueHD" appears here; otherwise ExoPlayer decodes to PCM at best.
        try {
            androidx.media3.exoplayer.audio.AudioCapabilities caps =
                    androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(this);
            String[] names = {"AC3", "E-AC3", "Atmos(E-AC3-JOC)", "TrueHD", "DTS", "DTS-HD"};
            int[] encs = {android.media.AudioFormat.ENCODING_AC3, android.media.AudioFormat.ENCODING_E_AC3,
                    android.media.AudioFormat.ENCODING_E_AC3_JOC, android.media.AudioFormat.ENCODING_DOLBY_TRUEHD,
                    android.media.AudioFormat.ENCODING_DTS, android.media.AudioFormat.ENCODING_DTS_HD};
            StringBuilder ok = new StringBuilder();
            for (int i = 0; i < encs.length; i++)
                if (caps.supportsEncoding(encs[i])) ok.append(ok.length() > 0 ? ", " : "").append(names[i]);
            AppLog.i("Player", "TV/receiver bitstream support: " + (ok.length() > 0 ? ok : "none (PCM only)")
                    + " · up to " + caps.getMaxChannelCount() + "ch");
        } catch (Exception e) {
            AppLog.w("Player", "Couldn't read audio bitstream capabilities (" + e.getClass().getSimpleName() + ")");
        }

        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO || g.length == 0) continue;
            androidx.media3.common.Format f = g.getTrackFormat(0);
            boolean dv = androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType)
                    || (f.codecs != null && (f.codecs.startsWith("dvh") || f.codecs.startsWith("dva") || f.codecs.startsWith("dav")));
            if (dv) {
                AppLog.i("Player", what + " is Dolby Vision (codecs " + f.codecs + ") · "
                        + (g.isTrackSupported(0) ? "playing in ExoPlayer (Dolby Vision decoder first, HDR10 base layer as backup)"
                        : "no decoder at all"));
                try {
                    List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> ds = androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                            .getDecoderInfos(androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION, false, false);
                    if (ds.isEmpty()) AppLog.i("Player", "Dolby Vision decoders on this device: none");
                    for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo d : ds)
                        AppLog.i("Player", "Dolby Vision decoder " + d.name + " · profiles " + dvProfiles(d));
                } catch (Exception e) {
                    AppLog.w("Player", "Couldn't list Dolby Vision decoders (" + e.getClass().getSimpleName() + ")");
                }
            }
            break;
        }
    }

    private void showError(String msg) {
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
    }

    /** "video (video/mpeg2) " when the stream has tracks of this type but none can be decoded, else "". */
    private static String unsupported(Tracks tracks, int type, String label) {
        if (!tracks.containsType(type) || tracks.isTypeSupported(type)) return "";
        StringBuilder mimes = new StringBuilder();
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != type) continue;
            String m = g.getTrackFormat(0).sampleMimeType;
            if (m != null && mimes.indexOf(m) < 0) mimes.append(mimes.length() > 0 ? ", " : "").append(m);
        }
        return label + (mimes.length() > 0 ? " (" + mimes + ")" : "") + " ";
    }

    /** Retry of a channel that failed; cancelled when you tune something else meanwhile. */
    private final Runnable retune = () -> tune(index, false);

    private void tune(int i, boolean userAction) {
        if (player == null) return;
        if (userAction) handler.removeCallbacks(retune);
        if (userAction && i != index) previousIndex = index;
        index = (i + nums.length) % nums.length;
        if (userAction) retries = 0;
        undecodable = false;
        loggedCaps = false; // log this channel's audio/video capabilities once it starts
        prefs.setLastChannel(nums[index]);
        error.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE); // tuning can take a few seconds (Tunarr longer)
        tuneStartedAt = System.currentTimeMillis();
        AppLog.i("Player", "Tune " + nums[index] + " " + names[index] + (userAction ? "" : " (retry)") + " · " + urls[index]);
        // Free the old tuner before taking a new one, then record the new channel
        // (http://<tuner>:5004/auto/v<channel>) into the timeshift buffer and play from that.
        player.stop();
        // A source that already needed VLC (e.g. MPEG-2 antenna channels on a tablet) goes straight to VLC.
        if (vlcSources.contains(sourceOf(urls[index]))) ensureVlc(); else releaseVlc();
        if (timeshift != null) { timeshift.stop(); timeshift = null; }
        direct = IptvClient.isDirect(urls[index]);
        if (direct) {
            playDirect();
        } else {
            timeshift = new Timeshift(getCacheDir(), urls[index]);
            playFrom(0);
        }
        setWantPlay(true);
        showBanner();
    }

    /** Start playback at a byte offset in the timeshift buffer. */
    private void playFrom(long offset) {
        startTimeMs = timeshift.timeAt(offset);
        if (vlc != null) {
            try {
                vlc.play(timeshift.httpUrl(offset), 0);
            } catch (java.io.IOException e) {
                AppLog.e("Player", "VLC: couldn't serve the buffer", e);
                retryOrFail("VLC_BUFFER");
            }
            return;
        }
        player.setMediaSource(new ProgressiveMediaSource.Factory(timeshift.sourceFrom(offset), extractors())
                .createMediaSource(MediaItem.fromUri(urls[index])));
        player.prepare();
    }

    /** Plays a direct (non-recordable) stream from its live point. */
    private void playDirect() {
        startTimeMs = System.currentTimeMillis();
        if (vlc != null) {
            vlc.play(urls[index], 0);
            return;
        }
        MediaItem.Builder mi = new MediaItem.Builder().setUri(urls[index]);
        if (urls[index].toLowerCase(java.util.Locale.US).contains("m3u8")) mi.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(mi.build());
        player.prepare();
    }

    /** How far a direct stream is behind its normal live point (0 = live). */
    private long directBehindMs() {
        if (vlc != null || player == null) return 0;
        long off = player.getCurrentLiveOffset();
        if (off == C.TIME_UNSET) return 0;
        long target = C.TIME_UNSET;
        androidx.media3.common.Timeline t = player.getCurrentTimeline();
        if (!t.isEmpty()) {
            androidx.media3.common.Timeline.Window w = t.getWindow(player.getCurrentMediaItemIndex(), new androidx.media3.common.Timeline.Window());
            if (w.liveConfiguration != null) target = w.liveConfiguration.targetOffsetMs;
        }
        if (target == C.TIME_UNSET) target = 20_000; // a typical HLS live delay
        return Math.max(0, off - target);
    }

    /** The moment of the broadcast currently on screen (wall-clock ms). */
    private long streamTimeMs() {
        if (direct) return System.currentTimeMillis() - directBehindMs();
        return startTimeMs + posMs();
    }

    private long behindLiveMs() {
        long b = System.currentTimeMillis() - streamTimeMs();
        return b < LIVE_SLACK_MS ? 0 : b;
    }

    /** Rewind (negative) or skip ahead (positive) within the buffer; past the live point = go live. */
    private void seekBy(long deltaMs) {
        if (direct) {
            // Within the stream's own live window (often a few minutes for IPTV).
            if (vlc != null) vlc.seekTo(Math.max(0, vlc.timeMs() + deltaMs));
            else if (player != null) {
                if (deltaMs > 0 && deltaMs >= directBehindMs() - LIVE_SLACK_MS) player.seekToDefaultPosition();
                else player.seekTo(Math.max(0, player.getCurrentPosition() + deltaMs));
            }
            showBanner();
            return;
        }
        if ((player == null && vlc == null) || timeshift == null) return;
        long oldest = timeshift.oldestTimeMs();
        if (oldest < 0) return; // nothing recorded yet
        long now = System.currentTimeMillis();
        long target = Math.max(oldest, streamTimeMs() + deltaMs);
        boolean play = wantPlay();
        if (target >= now - LIVE_SLACK_MS) {
            if (behindLiveMs() == 0) { showBanner(); return; } // already live
            playFrom(timeshift.offsetAt(now - 1500));
            play = true; // catching up to live always plays
        } else {
            playFrom(timeshift.offsetAt(target));
        }
        setWantPlay(play);
        showBanner();
    }

    private void togglePause() {
        if (player == null && vlc == null) return;
        if (wantPlay()) {
            setWantPlay(false);
        } else {
            // Paused longer than the buffer holds: the start has been dropped, so resume at the oldest part.
            long oldest = timeshift != null ? timeshift.oldestTimeMs() : -1;
            if (oldest >= 0 && streamTimeMs() < oldest + 1000) playFrom(timeshift.offsetAt(oldest + 2000));
            setWantPlay(true);
        }
        showBanner();
    }

    private void lastChannel() {
        if (previousIndex >= 0) tune(previousIndex, true);
    }

    private void showBanner() {
        boolean paused = (player != null || vlc != null) && !wantPlay();
        long behind = behindLiveMs();
        Channel ch = ChannelStore.get().byNumber(nums[index]);
        long now = streamTimeMs() / 1000L; // what's on screen, which may be from a few minutes ago
        Channel.Airing a = ch != null ? ch.now(now) : null;
        // Without a guide the channel's name is the big title, so the top line doesn't repeat it.
        bannerChannel.setText(nums[index] + (a != null ? "  ·  " + names[index] : "") + "      "
                + (paused ? "❚❚ PAUSED  ·  " : "")
                + (behind > 0 ? fmt(behind) + " behind live" : "● LIVE"));
        Channel.Airing n = ch != null ? ch.next(now) : null;
        boolean h24 = prefs.clock24h();
        if (a != null) {
            bannerTitle.setText(a.title);
            String meta = Ui.range(a, h24) + "  ·  " + Ui.timeLeft(a, now);
            if (n != null) meta += "      Next: " + n.title;
            bannerMeta.setText(meta);
            bannerProgress.setProgress(a.progress(now));
            bannerProgress.setVisibility(View.VISIBLE);
        } else {
            bannerTitle.setText(names[index]);
            bannerMeta.setText(Ui.clock(h24));
            bannerProgress.setVisibility(View.INVISIBLE);
        }
        banner.animate().cancel();
        banner.setAlpha(1f);
        handler.removeCallbacks(hideBanner);
        handler.removeCallbacks(bannerTick);
        // While paused, keep the banner up and its "behind live" count ticking.
        if (paused) handler.postDelayed(bannerTick, 1000);
        else handler.postDelayed(hideBanner, 5000);
    }

    private void commitTypedChannel() {
        String t = typed.toString();
        typed.setLength(0);
        entry.setVisibility(View.GONE);
        if (t.isEmpty()) return;
        for (int i = 0; i < nums.length; i++) {
            if (nums[i].replaceAll("[^0-9]", "").equals(t) || nums[i].equals(t)) { tune(i, true); return; }
        }
        // No exact match: take the first channel whose major number matches ("5" -> 5.1).
        for (int i = 0; i < nums.length; i++) {
            if (nums[i].split("[.\\-]")[0].equals(t)) { tune(i, true); return; }
        }
        entry.setText(t + " ?");
        entry.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> entry.setVisibility(View.GONE), 1200);
    }

    /** OK is acted on when released: a short press pauses / plays, holding it toggles subtitles. */
    private boolean okTracking, okHeld;

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // (Not while typing a channel number, where OK tunes, or when a Plex file failed, where OK opens Plex.)
        if (isOk(keyCode) && (plexMode ? !plexFailed : typed.length() == 0)) {
            if (event.getRepeatCount() == 0) { event.startTracking(); okTracking = true; okHeld = false; }
            return true;
        }
        if (plexMode) return onPlexKey(keyCode) || super.onKeyDown(keyCode, event);
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (typed.length() < 5) typed.append((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
            entry.setText(typed);
            entry.setVisibility(View.VISIBLE);
            handler.removeCallbacks(commitEntry);
            handler.postDelayed(commitEntry, 1800);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showChannelGuide(); // pick a channel from the list instead of flipping blind
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                tune(index + 1, true);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                tune(index - 1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Tap = back 10 s (handled on key up); hold = previous channel.
                if (event.getRepeatCount() == 0) { event.startTracking(); leftHeld = false; }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(10_000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
                seekBy(-30_000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
                seekBy(30_000);
                return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                lastChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (typed.length() > 0) { handler.removeCallbacks(commitEntry); commitTypedChannel(); }
                else togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!wantPlay()) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (wantPlay()) togglePause();
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (banner.getAlpha() > 0.5f) hideBanner.run();
                else showBanner();
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_MENU:
                toggleSubtitles();
                return true;
            case KeyEvent.KEYCODE_GUIDE:
                showFullGuide(nums[index]); // what's on later, over the video
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isOk(keyCode) && okTracking) {
            okHeld = true;
            toggleSubtitles();
            return true;
        }
        if (!plexMode && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            leftHeld = true;
            lastChannel();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isOk(keyCode) && okTracking) {
            okTracking = false;
            if (!okHeld && !event.isCanceled()) {
                if (plexMode) onPlexKey(KeyEvent.KEYCODE_DPAD_CENTER);
                else togglePause();
            }
            okHeld = false;
            return true;
        }
        if (!plexMode && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!leftHeld && !event.isCanceled()) seekBy(-10_000);
            leftHeld = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
