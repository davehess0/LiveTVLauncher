package com.dave.livetvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small view helpers so every layout shares the same look and D-pad focus behaviour. */
public final class Ui {
    // Apple TV-inspired: near-black ground, frosted-glass tiles, white focus, accents used sparingly.
    public static final int BG = 0xFF0A0A0C;
    public static final int CARD = 0x24FFFFFF;        // frosted glass
    public static final int CARD_FOCUS = 0xFFF5F5F7;  // focused tile turns white
    public static final int ON_FOCUS = 0xFF1C1C1E;    // text on a focused (white) tile
    public static final int ON_FOCUS_SUB = 0xFF6E6E73;
    public static final int ACCENT = 0xFF64B5F6;      // live TV, used for small details only
    public static final int PLEX = 0xFFE5A00D;
    public static final int LIVE_RED = 0xFFE5484D;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int SUB = 0xFFA1A1A6;
    public static final int DIM = 0xFF8E8E93;

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        // Medium/bold system sans reads closest to Apple TV's type on Android TV.
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static GradientDrawable rounded(int fill, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** Focusable card: highlighted border + gentle zoom when focused. */
    public static LinearLayout card(Context c, int orientation, int accent) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        styleFocusable(l, accent);
        return l;
    }

    /**
     * Text tiles and rows: glass at rest; when focused they turn white, lift and grow,
     * and their text flips dark (like tvOS buttons and list rows).
     */
    public static void styleFocusable(View v, int accent) {
        styleFocusable(v, 1.06f);
    }

    public static void styleFocusable(View v, float focusScale) {
        Context c = v.getContext();
        float r = dp(c, 18);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(CARD_FOCUS, r, 0, 0));
        s.addState(new int[]{}, rounded(CARD, r, 0, 0));
        v.setBackground(s);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, has) -> {
            if (has) adoptFocusColors(view);
            lift(view, has, focusScale);
        });
    }

    /** Image tiles (app banners, posters): no white plate, just lift, grow and cast a shadow. */
    public static void styleImageFocusable(View v, float focusScale) {
        Context c = v.getContext();
        v.setBackground(rounded(CARD, dp(c, 16), 0, 0));
        v.setClipToOutline(true);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, has) -> lift(view, has, focusScale));
    }

    public static void lift(View view, boolean has, float focusScale) {
        refreshStates(view);
        float scale = has ? focusScale : 1f;
        view.animate().scaleX(scale).scaleY(scale).translationZ(has ? dp(view.getContext(), 18) : 0)
                .setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    /**
     * Pushes the tile's focused / unfocused state to its labels. Android only does this by itself
     * for children that copied the parent's state before they were added, and adoptFocusColors()
     * turns that on afterwards, so the labels would otherwise keep their old colors.
     */
    private static void refreshStates(View v) {
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child.isDuplicateParentStateEnabled()) {
                child.refreshDrawableState();
                refreshStates(child);
            }
        }
    }

    /** Makes every label inside a tile switch to dark text while the tile is focused (white). */
    public static void adoptFocusColors(View root) {
        if (Boolean.TRUE.equals(root.getTag(R_TAG))) return;
        root.setTag(R_TAG, Boolean.TRUE);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) adopt(g.getChildAt(i));
        }
        root.refreshDrawableState();
    }

    private static void adopt(View child) {
        if (child.getBackground() != null) return; // badges, pills, logos keep their own look
        child.setDuplicateParentStateEnabled(true);
        if (child instanceof TextView) {
            TextView t = (TextView) child;
            int rest = t.getCurrentTextColor();
            int focused = (rest == TEXT) ? ON_FOCUS : (rest == SUB || rest == DIM) ? ON_FOCUS_SUB : darken(rest);
            t.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, rest}));
        } else if (child instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) child;
            for (int i = 0; i < g.getChildCount(); i++) adopt(g.getChildAt(i));
        }
    }

    private static int darken(int c) {
        return Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10);
    }

    private static final int R_TAG = R.id.focus_colors_applied;

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    public static String time(long unixSec, boolean h24) {
        return new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.getDefault())
                .format(new Date(unixSec * 1000L));
    }

    public static String clock(boolean h24) {
        return time(System.currentTimeMillis() / 1000L, h24);
    }

    public static String range(Channel.Airing a, boolean h24) {
        return time(a.start, h24) + " – " + time(a.end, h24);
    }

    /** "45 min left", "2 hr left", "1 hr 47 min left". */
    public static String timeLeft(Channel.Airing a, long now) {
        long m = Math.max(0, (a.end - now) / 60);
        if (m < 60) return m + " min left";
        long h = m / 60, rest = m % 60;
        return h + " hr" + (rest > 0 ? " " + rest + " min" : "") + " left";
    }

    /** Big rounded "5.1" channel-number badge, used when the guide has no logo. */
    public static TextView numberBadge(Context c, String number, float sp) {
        TextView t = text(c, sp, TEXT, true);
        t.setText(number);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(0x33FFFFFF, dp(c, 12), 0, 0));
        return t;
    }

    /** A thin progress line for "how far into the show are we". */
    public static final class ProgressLine extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float progress;

        public ProgressLine(Context c, int color) {
            super(c);
            track.setColor(0x4D8E8E93); // visible on glass and on a white focused tile
            fill.setColor(color);
        }

        public void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), w = getWidth(), rad = h / 2f;
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, rad, rad, track);
            r.set(0, 0, w * progress, h);
            canvas.drawRoundRect(r, rad, rad, fill);
        }
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
