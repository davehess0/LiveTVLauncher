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

    /** Another screen is coming to the front (or this layout is being replaced): stop live previews. */
    public void onHidden() { }

    /** This layout is on screen again. */
    public void onShown() { }

    /** Back from watching TV: select / show the channel that was playing last. */
    public void showLastWatched() { tick(); }

    /** Return true if the layout consumed Back. Home screens otherwise ignore Back. */
    public boolean onBack() { return false; }

    /** Put focus somewhere sensible after (re)building. */
    public abstract void focusDefault();

    protected int dp(float v) { return Ui.dp(c, v); }

    /**
     * Whether the Recent Channels and Plex rows run to the screen edges. The Guide keeps them inside
     * its channel-list column instead.
     */
    protected boolean fullBleedRows() { return true; }

    /**
     * Lets a scrolling row run under the page margins to both screen edges (like Apple TV) while its
     * first tile still lines up with the page. Also leaves room for the edge tiles to grow on focus.
     */
    protected android.widget.HorizontalScrollView bleed(android.widget.HorizontalScrollView sv) {
        int s = dp(Ui.SIDE_DP);
        sv.setLayoutParams(Ui.margins(Ui.lp(-1, -2), -s, 0, -s, 0));
        View row = sv.getChildAt(0);
        if (row != null) row.setPadding(row.getPaddingLeft() + s, row.getPaddingTop(), row.getPaddingRight() + s, row.getPaddingBottom());
        // While scrolling, keep the focused tile this far from the screen edge (so it isn't cut when it
        // grows), and fade tiles out softly at the edges.
        sv.setHorizontalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(s + dp(16));
        return sv;
    }

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

    /**
     * Hold OK on an IPTV channel: Remind me (for the next show) and Add to / Remove from favorites. Favorites sort to the top of the
     * IPTV channels; the list is rebuilt and focus goes back to the same channel.
     */
    protected void favoriteMenu(View v, Channel ch) {
        v.setTag("ch:" + ch.number);
        if (!ch.iptv) return;
        v.setOnLongClickListener(x -> {
            java.util.List<String> labels = new java.util.ArrayList<>();
            java.util.List<Runnable> acts = new java.util.ArrayList<>();
            // Remind me for what's on next (when the channel has a guide).
            Channel.Airing next = ch.next(System.currentTimeMillis() / 1000L);
            if (next != null) {
                boolean set = Reminders.has(host, ch.number, next.start);
                labels.add(set ? "Cancel reminder: " + next.title
                        : "Remind me: " + next.title + " at " + GuideGrid.time(host, next.start));
                acts.add(() -> {
                    if (set) Reminders.remove(host, ch.number, next.start);
                    else Reminders.add(host, ch, next);
                    android.widget.Toast.makeText(host, set ? "Reminder cancelled"
                            : "We'll remind you a minute before " + next.title + " starts", android.widget.Toast.LENGTH_SHORT).show();
                    v.requestFocus();
                });
            }
            labels.add(ch.favorite ? "Remove from favorites" : "Add to favorites");
            acts.add(() -> {
                boolean on = ChannelStore.get().toggleFavorite(host, ch);
                android.widget.Toast.makeText(host, ch.number + (on ? " added to favorites" : " removed from favorites"),
                        android.widget.Toast.LENGTH_SHORT).show();
                onFavoriteChanged(ch);
                v.requestFocus();
            });
            AppMenu.show(host, v, labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
            return true;
        });
    }

    /** A channel was starred / unstarred: update its ★ in place (the order changes next time the list is built). */
    protected void onFavoriteChanged(Channel ch) { }

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
        return a != null ? a.title : ch.displayName();
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
        h.addView(weatherOut, weatherLp());
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

    /** Space between the weather label and the clock, so the selected label's lift and glow clear the clock. */
    protected LinearLayout.LayoutParams weatherLp() {
        return Ui.margins(Ui.lp(-2, -2), 0, 0, dp(16), 0);
    }

    /** Label for the current weather, placed just before the clock. Hidden until weather is set up. */
    /**
     * Left / Right stay in this row (a top bar of buttons): they move to the next item along it and
     * stop at the ends, instead of dropping into the row below when there's nothing further along.
     */
    protected void keepInRow(android.view.ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!v.isFocusable()) continue;
            v.setOnKeyListener((view, code, e) -> {
                boolean right = code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                if (!right && code != android.view.KeyEvent.KEYCODE_DPAD_LEFT) return false;
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
                int dir = right ? 1 : -1;
                for (int j = row.indexOfChild(view) + dir; j >= 0 && j < row.getChildCount(); j += dir) {
                    View next = row.getChildAt(j);
                    if (next.getVisibility() == View.VISIBLE && next.isFocusable()) { next.requestFocus(); return true; }
                }
                return true; // end of the row: stay put
            });
        }
    }

    protected TextView weatherLabel(float sp) {
        TextView t = Ui.text(c, sp, Ui.TEXT, true);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        t.setVisibility(View.GONE);
        // Select (remote) or tap it for the 10-day forecast and radar.
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> host.openWeather());
        // No background at rest; when selected, the same glass and glow as every other tile.
        t.setBackground(Ui.glassWithGlow(c, dp(18), 0x00000000));
        t.setOnFocusChangeListener((v, has) -> Ui.lift(v, has, 1.06f));
        return t;
    }

    protected void showWeather(TextView label) {
        String w = Weather.get().text(prefs); // e.g. "☁️ 37°"
        label.setVisibility(w.isEmpty() ? View.GONE : View.VISIBLE);
        if (w.isEmpty() || Weather.get().alerts(prefs).isEmpty()) {
            label.setText(w);
            return;
        }
        // An active weather alert: a small ⚠ badge on the corner of the weather icon (select it for details).
        int gap = w.indexOf(' ');
        String icon = gap > 0 ? w.substring(0, gap) : "", rest = gap > 0 ? w.substring(gap) : w;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(icon);
        int s = sb.length();
        sb.append("⚠️");
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.5f), s, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.SuperscriptSpan(), s, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(rest);
        label.setText(sb);
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
            row.addView(appTile(e), Ui.margins(Ui.lp(dp(160), dp(90)), 0, 0, dp(24), 0));
        }
        sv.addView(row);
        return bleed(sv);
    }

    /**
     * Scroller for the Recent Channels and Plex rows. Rows that run to the screen edges use it as is;
     * rows kept inside a column (the Guide) fade out at their ends instead of being cut by a hard line.
     */
    protected android.widget.HorizontalScrollView rowScroller() {
        return fullBleedRows() ? new android.widget.HorizontalScrollView(c) : new FadingRow(c);
    }

    /**
     * A row that fades its tiles out at its left and right ends (only where there's more to scroll to),
     * and keeps the selected tile clear of the fade. The fade is drawn over the row's full height plus
     * room above and below, so a selected tile's glow isn't cut.
     */
    private static final class FadingRow extends android.widget.HorizontalScrollView {
        private final android.graphics.Paint fade = new android.graphics.Paint();
        private final int len, spill;

        FadingRow(Context c) {
            super(c);
            len = Ui.dp(c, 56);
            spill = Ui.dp(c, 32);
            fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
            android.graphics.Rect roomy = new android.graphics.Rect(rect);
            roomy.inset(-(len + Ui.dp(getContext(), 8)), 0);
            return super.computeScrollDeltaToGetChildRectOnScreen(roomy);
        }

        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            int x = getScrollX(), w = getWidth(), h = getHeight();
            View child = getChildAt(0);
            boolean moreLeft = x > 0;
            boolean moreRight = child != null && x + w < child.getRight() + getPaddingRight();
            if (!moreLeft && !moreRight) { super.dispatchDraw(canvas); return; }
            int save = canvas.saveLayer(x - spill, -spill, x + w + spill, h + spill, null);
            super.dispatchDraw(canvas);
            if (moreLeft) {
                fade.setShader(new android.graphics.LinearGradient(x, 0, x + len, 0, 0xFF000000, 0x00000000,
                        android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRect(x - spill, -spill, x + len, h + spill, fade);
            }
            if (moreRight) {
                fade.setShader(new android.graphics.LinearGradient(x + w - len, 0, x + w, 0, 0x00000000, 0xFF000000,
                        android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRect(x + w - len, -spill, x + w + spill, h + spill, fade);
            }
            canvas.restoreToCount(save);
        }
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
        if (pins.isEmpty()) row.addView(emptyStreamingHint());
        for (Apps.AppEntry e : pins) {
            // Just the banner: the app's own logo says what it is.
            View t = appTile(e);
            t.setTag(e.pkg);
            // Hold OK: a menu below the app with Move and Remove.
            t.setOnLongClickListener(v -> {
                AppMenu.show(host, t, new String[]{"Move", "Remove"}, new Runnable[]{
                        () -> startMove(row, t, e), () -> removeFromRow(row, t, e)});
                return true;
            });
            row.addView(t, Ui.margins(Ui.lp(dp(wDp), dp(hDp)), 0, 0, dp(24), 0));
        }
        sv.addView(row);
        return bleed(sv);
    }

    private TextView emptyStreamingHint() {
        TextView t = Ui.text(c, 14, Ui.SUB, false);
        t.setText("No streaming apps here. Open Apps and hold OK on an app to add it.");
        return t;
    }

    private void removeFromRow(LinearLayout row, View tile, Apps.AppEntry e) {
        List<String> pins = prefs.pinnedApps();
        pins.remove(e.pkg);
        prefs.setPinnedApps(pins);
        int i = row.indexOfChild(tile);
        row.removeView(tile);
        if (row.getChildCount() == 0) {
            row.addView(emptyStreamingHint());
            focusDefault();
        } else {
            row.getChildAt(Math.min(i, row.getChildCount() - 1)).requestFocus();
        }
        android.widget.Toast.makeText(c, e.label + " removed from the Streaming row", android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Move mode: arrows on the app; Left / Right move it along the row (saved as it goes); OK or
     * Back finishes. The neighbouring app is moved rather than this one, so it keeps the focus.
     */
    private void startMove(LinearLayout row, View tile, Apps.AppEntry e) {
        if (!(tile instanceof android.widget.FrameLayout)) return;
        android.widget.FrameLayout f = (android.widget.FrameLayout) tile;
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(c);
        android.graphics.drawable.GradientDrawable scrim = new android.graphics.drawable.GradientDrawable();
        scrim.setColor(0x99000000);
        scrim.setCornerRadius(dp(16));
        scrim.setStroke(dp(3), Ui.TEXT);
        overlay.setBackground(scrim);
        TextView left = Ui.text(c, 26, Ui.TEXT, true), right = Ui.text(c, 26, Ui.TEXT, true);
        left.setText("◀");
        right.setText("▶");
        overlay.addView(left, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        overlay.addView(right, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        overlay.setPadding(dp(10), 0, dp(10), 0);
        f.addView(overlay, new android.widget.FrameLayout.LayoutParams(-1, -1));
        tile.requestFocus();
        android.widget.Toast.makeText(c, "◀ ▶ to move " + e.label + " · OK when done", android.widget.Toast.LENGTH_SHORT).show();

        tile.setOnKeyListener((v, code, ev) -> {
            boolean down = ev.getAction() == android.view.KeyEvent.ACTION_DOWN;
            switch (code) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: {
                    if (!down) return true;
                    int i = row.indexOfChild(tile);
                    int j = i + (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (j < 0 || j >= row.getChildCount()) return true;
                    View other = row.getChildAt(j);
                    row.removeView(other);
                    row.addView(other, i, other.getLayoutParams());
                    savePinnedOrder(row);
                    tile.post(() -> tile.requestRectangleOnScreen(
                            new android.graphics.Rect(-dp(40), 0, tile.getWidth() + dp(40), tile.getHeight())));
                    return true;
                }
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                case android.view.KeyEvent.KEYCODE_BACK:
                case android.view.KeyEvent.KEYCODE_ESCAPE:
                    if (!down && ev.getRepeatCount() == 0) {
                        f.removeView(overlay);
                        tile.setOnKeyListener(null);
                    }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    return true; // stay on the app while moving it
                default:
                    return false;
            }
        });
    }

    /** Saves the row's order; apps not shown in this profile keep their places at the end. */
    private void savePinnedOrder(LinearLayout row) {
        List<String> order = new java.util.ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            Object tag = row.getChildAt(i).getTag();
            if (tag instanceof String) order.add((String) tag);
        }
        for (String p : prefs.pinnedApps()) if (!order.contains(p)) order.add(p);
        prefs.setPinnedApps(order);
    }

    /** A horizontal row of Plex posters. Returns null when there's nothing to show. */
    protected View plexRow(List<PlexClient.Item> items, boolean showProgress, int posterWDp) {
        if (items == null || items.isEmpty()) return null;
        android.widget.HorizontalScrollView sv = rowScroller();
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
            Ui.roundCorners(iv, dp(16));
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
        return fullBleedRows() ? bleed(sv) : sv;
    }

    // ---- Recent channels ----

    private static final class RecentTile {
        Channel ch;
        TextView title, nm;
        Ui.ProgressLine progress;
    }
    private final java.util.List<RecentTile> recentTiles = new java.util.ArrayList<>();
    private String recentKey = "";

    /** A channel tile in the Recent Channels row got focus (the Guide shows it in its preview). */
    protected void onRecentFocused(Channel ch) {
        Channel.Airing a = ch.now(now());
        host.setBackdrop(a != null && a.imageUrl != null ? a.imageUrl : ch.logoUrl);
    }

    /**
     * Fills {@code box} with a "Recent Channels" heading and a row of the channels watched lately
     * (most recent first). Rebuilds only when the list changed, so focus isn't lost on a refresh.
     */
    protected void fillRecent(LinearLayout box, int tileWDp) {
        if (box == null) return;
        java.util.List<Channel> chs = new java.util.ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (String n : prefs.recentChannels()) {
            Channel ch = ChannelStore.get().byNumber(n);
            if (ch == null) continue;
            chs.add(ch);
            key.append(n).append('|').append(System.identityHashCode(ch)).append(',');
        }
        if (key.toString().equals(recentKey) && box.getChildCount() > 0) { refreshRecent(); return; }
        recentKey = key.toString();
        View focused = box.findFocus();
        String focusedNum = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        box.removeAllViews();
        recentTiles.clear();
        if (chs.isEmpty()) return;
        box.addView(section("Recent Channels"));
        android.widget.HorizontalScrollView sv = rowScroller();
        sv.setHorizontalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        LinearLayout row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        View refocus = null;
        for (Channel ch : chs) {
            RecentTile t = new RecentTile();
            t.ch = ch;
            LinearLayout v = Ui.card(c, LinearLayout.VERTICAL, Ui.ACCENT);
            v.setTag(ch.number);
            v.setPadding(dp(12), dp(10), dp(12), dp(10));
            v.addView(logo(ch, tileWDp - 24, 52, 22), Ui.lp(-1, dp(52)));
            t.nm = Ui.text(c, 12, Ui.ACCENT, true);
            t.nm.setPadding(0, dp(8), 0, 0);
            v.addView(t.nm);
            t.title = Ui.text(c, 14, Ui.TEXT, true);
            t.title.setPadding(0, dp(2), 0, 0);
            v.addView(t.title);
            t.progress = new Ui.ProgressLine(c, Ui.ACCENT);
            v.addView(t.progress, Ui.margins(Ui.lp(-1, dp(4)), 0, dp(8), 0, 0));
            v.setOnClickListener(x -> host.play(ch));
            v.setOnFocusChangeListener((x, has) -> {
                if (has) onRecentFocused(ch);
                Ui.lift(x, has, 1.06f);
            });
            row.addView(v, Ui.margins(Ui.lp(dp(tileWDp), -2), 0, 0, dp(16), 0));
            recentTiles.add(t);
            if (ch.number.equals(focusedNum)) refocus = v;
        }
        sv.addView(row);
        box.addView(fullBleedRows() ? bleed(sv) : sv);
        refreshRecent();
        if (refocus != null) refocus.requestFocus();
    }

    /** Moves the recent tiles' titles and progress bars along (called from tick). */
    protected void refreshRecent() {
        long now = now();
        for (RecentTile t : recentTiles) {
            Channel.Airing a = t.ch.now(now);
            // No guide: the name once (as the title), with just the number above it.
            t.nm.setText(a != null ? t.ch.number + "  " + t.ch.displayName() : t.ch.number);
            t.title.setText(a != null ? a.title : t.ch.displayName());
            t.progress.setProgress(a != null ? a.progress(now) : 0);
            t.progress.setVisibility(a != null ? View.VISIBLE : View.INVISIBLE);
        }
    }

    protected LinearLayout recentBox() {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setClipChildren(false);
        return b;
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
