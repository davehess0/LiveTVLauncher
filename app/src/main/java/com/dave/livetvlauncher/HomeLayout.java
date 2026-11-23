package com.dave.livetvlauncher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** One of the selectable home-screen designs. */
public abstract class HomeLayout {
    protected final LauncherActivity host;
    protected final Context c;
    protected final Prefs prefs;

    protected HomeLayout(LauncherActivity host) {
        this.host = host;
        this.c = host;
        this.prefs = new Prefs(host);
    }

    /** Build the view tree once. */
    public abstract View create();

    /** New channel/guide data arrived. */
    public abstract void bind(List<Channel> channels, String status);

    /** Called every 30 s to move clocks and progress bars. */
    public abstract void tick();

    /** Back from watching TV: select / show the channel that was playing last. */
    public void showLastWatched() { tick(); }

    /** Return true if the layout consumed Back. Home screens otherwise ignore Back. */
    public boolean onBack() { return false; }

    /** Put focus somewhere sensible after (re)building. */
    public abstract void focusDefault();

    protected int dp(float v) { return Ui.dp(c, v); }

    protected long now() { return System.currentTimeMillis() / 1000L; }

    // ---- shared building blocks -------------------------------------------------------------

    /** Big source tile: HDHomeRun, Plex Live TV, Apps, Settings… */
    protected LinearLayout sourceTile(String title, String subtitle, int accent, String glyph,
                                      float titleSp, View.OnClickListener click) {
        LinearLayout t = Ui.card(c, LinearLayout.VERTICAL, accent);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        t.setGravity(Gravity.CENTER_VERTICAL);

        TextView g = Ui.text(c, titleSp * 1.4f, accent, true);
        g.setText(glyph);
        t.addView(g);

        TextView ti = Ui.text(c, titleSp, Ui.TEXT, true);
        ti.setText(title);
        ti.setPadding(0, dp(8), 0, 0);
        t.addView(ti);

        if (subtitle != null) {
            TextView s = Ui.text(c, titleSp * 0.62f, Ui.SUB, false);
            s.setText(subtitle);
            s.setPadding(0, dp(4), 0, 0);
            s.setTag("subtitle");
            t.addView(s);
        }
        t.setOnClickListener(click);
        return t;
    }

    protected static void setSubtitle(LinearLayout tile, String s) {
        View v = tile.findViewWithTag("subtitle");
        if (v instanceof TextView) ((TextView) v).setText(s);
    }

    /** Logo image if the guide supplied one, else a number badge. */
    protected View logo(Channel ch, int wDp, int hDp, float badgeSp) {
        FrameLayout f = new FrameLayout(c);
        TextView badge = Ui.numberBadge(c, ch.number, badgeSp);
        f.addView(badge, new FrameLayout.LayoutParams(-1, -1));
        if (ch.logoUrl != null) {
            // The logo plate is see-through, so hide the number badge underneath once the logo arrives.
            ImageView iv = new ImageView(c) {
                @Override public void setImageDrawable(android.graphics.drawable.Drawable d) {
                    super.setImageDrawable(d);
                    badge.setVisibility(d != null ? View.INVISIBLE : View.VISIBLE);
                }
            };
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(6), dp(6), dp(6), dp(6));
            GradientDrawable bg = Ui.rounded(0x33FFFFFF, dp(12), 0, 0);
            iv.setBackground(bg);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            ImageLoader.load(iv, ch.logoUrl, dp(Math.max(wDp, hDp)));
        }
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return f;
    }

    protected String nowTitle(Channel ch) {
        Channel.Airing a = ch.now(now());
        return a != null ? a.title : ch.name;
    }

    /** "LIVE" pill. */
    protected TextView livePill() {
        TextView t = Ui.text(c, 11, Ui.TEXT, true);
        t.setText(" LIVE ");
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        t.setBackground(Ui.rounded(Ui.LIVE_RED, dp(4), 0, 0));
        return t;
    }

    /** Header bar: title on the left, status + weather + clock on the right. */
    protected LinearLayout header(String title, TextView statusOut, TextView weatherOut, TextView clockOut) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(c, 22, Ui.TEXT, true);
        t.setText(title);
        h.addView(t, Ui.lpWeight(0, -2, 1));
        statusOut.setPadding(0, 0, dp(20), 0);
        h.addView(statusOut);
        h.addView(weatherOut);
        h.addView(clockOut);
        return h;
    }

    /**
     * Shows the channel status line only when something needs attention (a tuner or Tunarr that
     * can't be reached, no guide data). The normal "1 tuner · 100 channels" summary stays hidden.
     */
    protected void showStatus(TextView label, String st) {
        // Keep only the parts that report a problem, e.g. "1 tuner · 100 channels · can't reach Tunarr" → "can't reach Tunarr".
        StringBuilder out = new StringBuilder();
        for (String part : (st == null ? "" : st).split(" · ")) {
            String s = part.toLowerCase(java.util.Locale.US);
            if (s.contains("can't") || s.contains("no guide") || s.contains("looking for")) {
                if (out.length() > 0) out.append(" · ");
                out.append(part.trim());
            }
        }
        boolean problem = out.length() > 0;
        label.setText(out);
        // INVISIBLE (not GONE) so neighbors like Simple's Settings button keep their place.
        label.setVisibility(problem ? View.VISIBLE : View.INVISIBLE);
    }

    /** Label for the current weather, placed just before the clock. Hidden until weather is set up. */
    protected TextView weatherLabel(float sp) {
        TextView t = Ui.text(c, sp, Ui.TEXT, true);
        t.setPadding(0, 0, dp(18), 0);
        t.setVisibility(View.GONE);
        return t;
    }

    protected void showWeather(TextView label) {
        String w = Weather.get().text(prefs);
        label.setText(w);
        label.setVisibility(w.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Horizontal row of allowed apps (banners) for the bottom of a layout. */
    protected View appRow(int max) {
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        LinearLayout row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        List<Apps.AppEntry> apps = Apps.list(c);
        int n = 0;
        for (Apps.AppEntry e : apps) {
            if (n++ >= max) break;
            row.addView(appTile(e), Ui.margins(Ui.lp(dp(160), dp(90)), 0, 0, dp(14), 0));
        }
        sv.addView(row);
        return sv;
    }

    /** Plex rows arrived or changed. Layouts that show Plex override this. */
    public void bindPlex(PlexClient.Library lib) {}

    /** Section label used above rows. */
    protected TextView section(String s) {
        // tvOS-style row titles: sentence case, bold, quiet.
        TextView t = Ui.text(c, 17, Ui.SUB, true);
        t.setText(s);
        t.setPadding(dp(2), dp(22), 0, dp(10));
        return t;
    }

    /** Big banner tiles for the pinned streaming apps (YouTube, Prime Video, Disney+, Plex…). */
    protected View streamingRow(int wDp, int hDp) {
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        LinearLayout row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        List<Apps.AppEntry> pins = Apps.pinned(c);
        if (pins.isEmpty()) {
            TextView t = Ui.text(c, 14, Ui.SUB, false);
            t.setText("No streaming apps pinned. Open Apps and long-press OK on an app to pin it here.");
            row.addView(t);
        }
        for (Apps.AppEntry e : pins) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            View tile = appTile(e);
            col.addView(tile, Ui.lp(dp(wDp), dp(hDp)));
            TextView label = Ui.text(c, 14, Ui.TEXT, true);
            label.setText(e.label);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(14), 0, 0);
            label.setVisibility(View.INVISIBLE);
            col.addView(label, Ui.lp(dp(wDp), -2));
            tile.setOnFocusChangeListener((v, has) -> {
                Ui.lift(v, has, 1.1f);
                label.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            });
            row.addView(col, Ui.margins(Ui.lp(-2, -2), 0, 0, dp(24), 0));
        }
        sv.addView(row);
        return sv;
    }

    /** A horizontal row of Plex posters. Returns null when there's nothing to show. */
    protected View plexRow(List<PlexClient.Item> items, boolean showProgress, int posterWDp) {
        if (items == null || items.isEmpty()) return null;
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        LinearLayout row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        int posterH = Math.round(posterWDp * 1.5f);
        for (PlexClient.Item it : items) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            android.widget.FrameLayout poster = new android.widget.FrameLayout(c);
            Ui.styleImageFocusable(poster, 1.08f);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
            ImageLoader.load(iv, it.posterUrl, dp(posterWDp));
            if (showProgress) {
                Ui.ProgressLine pl = new Ui.ProgressLine(c, Ui.TEXT);
                pl.setProgress(it.progress());
                android.widget.FrameLayout.LayoutParams plp =
                        new android.widget.FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
                plp.setMargins(dp(10), 0, dp(10), dp(10));
                poster.addView(pl, plp);
            }
            col.addView(poster, Ui.lp(dp(posterWDp), dp(posterH)));
            TextView t = Ui.text(c, 13, Ui.TEXT, true);
            t.setText(it.title);
            t.setPadding(0, dp(12), 0, 0);
            col.addView(t, Ui.lp(dp(posterWDp), -2));
            TextView s2 = Ui.text(c, 11, Ui.SUB, false);
            s2.setText(it.subtitle);
            col.addView(s2, Ui.lp(dp(posterWDp), -2));
            poster.setContentDescription(it.title);
            poster.setOnClickListener(v -> PlexStore.open(host, it));
            poster.setOnFocusChangeListener((v, has) -> {
                Ui.lift(v, has, 1.08f);
                if (has) host.setBackdrop(it.artUrl != null ? it.artUrl : it.posterUrl);
            });
            row.addView(col, Ui.margins(Ui.lp(-2, -2), 0, 0, dp(22), 0));
        }
        sv.addView(row);
        return sv;
    }

    /** Fills a container with "Continue watching" and "Recently added movies" rows. */
    protected void fillPlexRows(LinearLayout target, PlexClient.Library lib, int posterWDp) {
        target.removeAllViews();
        if (lib == null) return;
        View cw = plexRow(lib.continueWatching, true, posterWDp);
        if (cw != null) {
            target.addView(section("Continue Watching"));
            target.addView(cw);
        }
        View rm = plexRow(lib.recentMovies, false, posterWDp);
        if (rm != null) {
            target.addView(section("Recently Added Movies"));
            target.addView(rm);
        }
    }

    protected View appTile(Apps.AppEntry e) {
        return Apps.tile(host, e);
    }
}
