package com.dave.livetvlauncher;

import android.content.Context;
import android.net.Uri;
import android.widget.FrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * VLC's playback engine, used when this device can't decode a channel's (or file's) video or audio
 * itself — e.g. MPEG-2 video / AC-3 audio on phones and tablets. VLC brings its own decoders.
 */
final class VlcEngine {
    interface Listener {
        void onPlaying();
        void onBuffering(boolean buffering);
        void onError(String why);
        void onEnded();
    }

    private final LibVLC vlc;
    private final MediaPlayer mp;
    private final VLCVideoLayout layout;
    private boolean wantPlay = true;

    VlcEngine(Context c, FrameLayout parent, int indexInParent, Listener l) {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=1200"); // ms of buffer for live streams over the network
        opts.add("--no-stats");
        opts.add("--audio-time-stretch");
        vlc = new LibVLC(c, opts);
        mp = new MediaPlayer(vlc);
        layout = new VLCVideoLayout(c);
        parent.addView(layout, indexInParent, new FrameLayout.LayoutParams(-1, -1));
        mp.attachViews(layout, null, false, false);
        mp.setEventListener(e -> {
            switch (e.type) {
                case MediaPlayer.Event.Playing: l.onPlaying(); break;
                case MediaPlayer.Event.Buffering: l.onBuffering(e.getBuffering() < 100f); break;
                case MediaPlayer.Event.EncounteredError: l.onError("VLC could not play this stream"); break;
                case MediaPlayer.Event.EndReached: l.onEnded(); break;
                default: break;
            }
        });
    }

    /** Play a URL, optionally starting at a position (files only). */
    void play(String url, long startMs) {
        Media m = new Media(vlc, Uri.parse(url));
        if (startMs > 0) m.addOption(":start-time=" + (startMs / 1000.0));
        mp.setMedia(m);
        m.release();
        wantPlay = true;
        mp.play();
    }

    /** Silence (for previews). */
    void setMuted(boolean muted) { mp.setVolume(muted ? 0 : 100); }

    boolean wantPlay() { return wantPlay; }
    void setWantPlay(boolean play) {
        wantPlay = play;
        if (play) mp.play(); else mp.pause();
    }
    boolean isPlaying() { return mp.isPlaying(); }
    long timeMs() { return Math.max(0, mp.getTime()); }
    long lengthMs() { return mp.getLength(); }
    void seekTo(long ms) { mp.setTime(Math.max(0, ms)); }

    void release() {
        try { mp.stop(); } catch (Exception ignored) { }
        mp.detachViews();
        mp.release();
        vlc.release();
        if (layout.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) layout.getParent()).removeView(layout);
    }
}
