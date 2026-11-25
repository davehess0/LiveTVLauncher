package com.dave.livetvlauncher;

import android.app.Activity;
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
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Live security cameras (RTSP, e.g. Eufy, or anything a go2rtc / Home Assistant relay serves).
 *
 * Grid of up to four cameras, muted. OK on one opens it full screen with sound;
 * there Left / Right switch cameras and Back returns to the grid (Back again: home).
 * A dropped stream reconnects by itself every few seconds.
 */
@OptIn(markerClass = UnstableApi.class)
public final class CamerasActivity extends Activity {
    private static final int GRID_MAX = 4;
    private static final long RECONNECT_MS = 5000;
    /** Grid cameras start this far apart, so a HomeBase isn't asked for several logins at once. */
    private static final long STAGGER_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String[]> cams;
    private FrameLayout root;
    private final List<Tile> tiles = new ArrayList<>();
    private int fullIndex = -1; // >= 0 while one camera is full screen

    private final class Tile {
        int cam;
        FrameLayout view;
        PlayerView pv;
        ExoPlayer player;
        TextView label, status;
        ProgressBar spinner;
        Runnable reconnect;
        boolean udp;   // false = RTP over TCP via the repair relay (tried first), true = RTP over UDP, direct
        RtspRelay relay;
        int fails;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cams = new Prefs(this).cameras();
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        if (cams.isEmpty()) {
            TextView t = Ui.text(this, 20, Ui.TEXT, false);
            t.setSingleLine(false);
            t.setGravity(Gravity.CENTER);
            t.setText("No cameras yet.\nAdd one in ⚙ Settings → Cameras.");
            root.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (cams.isEmpty()) return;
        if (cams.size() == 1) showFull(0); else showGrid();
    }

    @Override protected void onStop() {
        releaseAll();
        super.onStop();
    }

    private void showGrid() {
        releaseAll();
        fullIndex = -1;
        root.removeAllViews();
        int n = Math.min(cams.size(), GRID_MAX);
        int pad = Ui.dp(this, 12);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(pad, pad, pad, pad);
        int rows = n <= 2 ? 1 : 2;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                if (i >= n) { row.addView(new View(this), Ui.lpWeight(0, -1, 1)); continue; }
                Tile t = makeTile(i, false, i * STAGGER_MS);
                LinearLayout.LayoutParams lp = Ui.lpWeight(0, -1, 1);
                lp.setMargins(pad / 2, pad / 2, pad / 2, pad / 2);
                row.addView(t.view, lp);
            }
            grid.addView(row, Ui.lpWeight(-1, 0, 1));
        }
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        if (!tiles.isEmpty()) tiles.get(0).view.requestFocus();
        AppLog.i("Cameras", "Grid with " + n + " camera(s)");
    }

    private void showFull(int i) {
        releaseAll();
        fullIndex = (i + cams.size()) % cams.size();
        root.removeAllViews();
        Tile t = makeTile(fullIndex, true, 0);
        root.addView(t.view, new FrameLayout.LayoutParams(-1, -1));
        t.view.requestFocus();
        AppLog.i("Cameras", "Full screen: " + cams.get(fullIndex)[0]);
    }

    private Tile makeTile(int camIndex, boolean full, long startDelayMs) {
        Tile t = new Tile();
        t.cam = camIndex;
        t.view = new FrameLayout(this);
        t.view.setFocusable(true);
        t.view.setBackground(Ui.rounded(0xFF111114, Ui.dp(this, full ? 0 : 14), 0, 0));
        t.view.setClipToOutline(true);
        t.view.setPadding(full ? 0 : Ui.dp(this, 3), full ? 0 : Ui.dp(this, 3), full ? 0 : Ui.dp(this, 3), full ? 0 : Ui.dp(this, 3));
        if (!full) {
            t.view.setOnFocusChangeListener((v, has) -> v.setBackground(Ui.rounded(has ? Ui.TEXT : 0xFF111114, Ui.dp(this, 14), 0, 0)));
            t.view.setOnClickListener(v -> showFull(camIndex));
        }

        t.pv = new PlayerView(this);
        t.pv.setUseController(false);
        t.pv.setKeepContentOnPlayerReset(true);
        t.pv.setShutterBackgroundColor(0xFF111114);
        t.pv.setFocusable(false);
        t.view.addView(t.pv, new FrameLayout.LayoutParams(-1, -1));

        t.label = Ui.text(this, full ? 18 : 15, Ui.TEXT, true);
        t.label.setText(cams.get(camIndex)[0] + (full && cams.size() > 1 ? "     ◀ ▶ other cameras" : ""));
        t.label.setShadowLayer(6, 0, 1, 0xFF000000);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        llp.setMargins(Ui.dp(this, 16), Ui.dp(this, 12), 0, 0);
        t.view.addView(t.label, llp);

        t.status = Ui.text(this, 14, Ui.SUB, false);
        t.status.setSingleLine(false);
        t.status.setGravity(Gravity.CENTER);
        t.status.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        slp.topMargin = Ui.dp(this, 60);
        slp.leftMargin = slp.rightMargin = Ui.dp(this, 32); // wrap neatly inside the tile
        t.view.addView(t.status, slp);

        t.spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        t.spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        int s = Ui.dp(this, full ? 64 : 44);
        t.view.addView(t.spinner, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));

        tiles.add(t);
        if (startDelayMs <= 0) start(t, full);
        else handler.postDelayed(() -> { if (tiles.contains(t)) start(t, full); }, startDelayMs);
        return t;
    }

    private void start(Tile t, boolean withSound) {
        String url = encodeUserInfo(cams.get(t.cam)[1].trim());
        t.player = new ExoPlayer.Builder(this).build();
        t.player.setVolume(withSound ? 1f : 0f);
        t.player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    t.spinner.setVisibility(View.GONE);
                    t.status.setVisibility(View.GONE);
                    t.fails = 0;
                    AppLog.i("Cameras", cams.get(t.cam)[0] + " streaming (" + (t.udp ? "UDP" : "TCP via relay") + ")");
                    if (t.relay != null) {
                        RtspRelay r = t.relay;
                        handler.postDelayed(() -> { if (r.repaired() > 0) AppLog.i("Cameras",
                                cams.get(t.cam)[0] + ": repaired " + r.repaired() + " Eufy keyframes so far"); }, 10_000);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    t.spinner.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED) {
                    scheduleReconnect(t, "Stream ended");
                }
            }

            @Override public void onPlayerError(PlaybackException e) {
                if (!tiles.contains(t)) return; // closing a stream that was still connecting: not a real problem
                int status = rtspStatus(e);
                AppLog.e("Cameras", cams.get(t.cam)[0] + " error " + e.getErrorCodeName()
                        + (status > 0 ? " · camera replied " + status : "") + " · " + (t.udp ? "UDP" : "TCP") + " · " + url,
                        e.getCause() != null ? e.getCause() : e);
                // Cameras differ on how they send video: if one way fails, try the other next time
                // (a wrong password won't be fixed by that, so keep the transport then).
                if (status != 401) t.udp = !t.udp;
                t.fails++;
                scheduleReconnect(t, friendly(e, status));
            }
        });
        t.pv.setPlayer(t.player);
        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        if (url.toLowerCase(java.util.Locale.US).startsWith("rtsp")) {
            // TCP (tried first) goes through a local relay that repairs Eufy keyframes; UDP connects directly.
            if (!t.udp) {
                try {
                    Uri cu = Uri.parse(url);
                    t.relay = new RtspRelay(cu.getHost(), cu.getPort() > 0 ? cu.getPort() : 554);
                    item = MediaItem.fromUri(Uri.parse(RtspRelay.localUrl(url, t.relay.port())));
                } catch (Exception e) {
                    AppLog.e("Cameras", "Relay didn't start; connecting directly", e);
                    t.relay = null;
                }
            }
            RtspMediaSource.Factory f = new RtspMediaSource.Factory().setForceUseRtpTcp(!t.udp).setTimeoutMs(8000);
            t.player.setMediaSource(f.createMediaSource(item));
        } else {
            t.player.setMediaItem(item);
        }
        t.player.prepare();
        t.player.setPlayWhenReady(true);
    }

    private void scheduleReconnect(Tile t, String why) {
        t.spinner.setVisibility(View.GONE);
        t.status.setText(why + "\nReconnecting…");
        t.status.setVisibility(View.VISIBLE);
        if (t.reconnect != null) handler.removeCallbacks(t.reconnect);
        t.reconnect = () -> {
            if (!tiles.contains(t)) return;
            if (t.player != null) t.player.release();
            if (t.relay != null) { t.relay.close(); t.relay = null; }
            t.spinner.setVisibility(View.VISIBLE);
            start(t, fullIndex >= 0);
        };
        handler.postDelayed(t.reconnect, RECONNECT_MS);
    }

    /** The camera's RTSP reply code (e.g. 401, 454, 461) from errors like "SETUP 461", or 0. */
    private static int rtspStatus(PlaybackException e) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\b(OPTIONS|DESCRIBE|SETUP|PLAY|PAUSE|TEARDOWN|GET_PARAMETER|SET_PARAMETER|ANNOUNCE|RECORD)\\s+(\\d{3})\\b");
        for (Throwable c = e; c != null; c = c.getCause()) {
            java.util.regex.Matcher m = p.matcher(String.valueOf(c.getMessage()));
            if (m.find()) return Integer.parseInt(m.group(2));
        }
        return 0;
    }

    private static String friendly(PlaybackException e, int status) {
        switch (status) {
            case 401:
            case 403:
                return "The camera turned down the user name / password (" + status + ")."
                        + "\nRe-copy them from the camera's RTSP settings in the Eufy app.";
            case 404:
                return "No stream right now (404). The camera may be asleep (open its live view in the Eufy app "
                        + "to wake it), RTSP may be off for it, or the /liveN number may be off by one (the first "
                        + "camera on a HomeBase is often /live0).";
            case 461:
                return "The camera didn't accept that connection type (461); trying the other one";
            case 453:
            case 503:
                return "The camera is busy (" + status + "). It may already be streaming to something else.";
            case 0:
                break;
            default:
                return "The camera replied with error " + status;
        }
        // Otherwise the real reason is usually underneath an "unspecified" error.
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            if (c instanceof java.net.ConnectException || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.UnknownHostException || c instanceof java.net.NoRouteToHostException)
                return "Can't reach this camera (is RTSP turned on, and is the address right?)";
            if (c.getClass().getSimpleName().contains("UdpUnsupported"))
                return "The camera only streams over TCP; switching";
        }
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Can't reach this camera (is RTSP turned on, and is the address right?)";
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "This device can't decode the camera's video";
            default:
                return "Camera stream problem (" + e.getErrorCodeName() + ")";
        }
    }

    /**
     * Percent-encodes the user name and password in rtsp://user:pass@host/... so characters such as
     * @ # : / ? % in a password don't break the address. Already-encoded values are left as they are.
     */
    static String encodeUserInfo(String url) {
        int scheme = url.indexOf("://");
        int at = url.lastIndexOf('@');
        if (scheme < 0 || at < scheme) return url;
        String info = url.substring(scheme + 3, at);
        int colon = info.indexOf(':');
        String user = colon >= 0 ? info.substring(0, colon) : info;
        String pass = colon >= 0 ? info.substring(colon + 1) : null;
        String enc = Uri.encode(Uri.decode(user)) + (pass != null ? ":" + Uri.encode(Uri.decode(pass)) : "");
        return url.substring(0, scheme + 3) + enc + url.substring(at);
    }

    private void releaseAll() {
        handler.removeCallbacksAndMessages(null);
        for (Tile t : tiles) {
            t.pv.setPlayer(null);
            if (t.player != null) t.player.release();
            if (t.relay != null) { t.relay.close(); t.relay = null; }
        }
        tiles.clear();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (fullIndex >= 0 && cams.size() > 1) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) { showFull(fullIndex - 1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) { showFull(fullIndex + 1); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        // Full screen → back to the grid (when there is one); grid → home.
        if (fullIndex >= 0 && cams.size() > 1) { int was = fullIndex; showGrid(); focusTile(was); return; }
        super.onBackPressed();
    }

    private void focusTile(int cam) {
        for (Tile t : tiles) if (t.cam == cam) { t.view.requestFocus(); return; }
    }
}
