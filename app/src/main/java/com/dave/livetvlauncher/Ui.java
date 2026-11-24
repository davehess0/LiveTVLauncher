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
    // Apple TV-inspired: near-black ground, frosted-glass tiles, a soft white glow for focus, accents used sparingly.
    public static final int BG = 0xFF0A0A0C;
    public static final int CARD = 0x24FFFFFF;        // frosted glass
    public static final int CARD_FOCUS = 0x38FFFFFF;  // focused tile: slightly brighter glass...
    public static final int FOCUS_GLOW = 0xFFFFFFFF;  // ...with a white glow fading out around it
    public static final int ACCENT = 0xFF64B5F6;      // live TV, used for small details only
    public static final int PLEX = 0xFFE5A00D;
    public static final int LIVE_RED = 0xFFE5484D;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int SUB = 0xFFA1A1A6;
    public static final int DIM = 0xFF8E8E93;

    /** Left / right page margin (dp). Scrolling rows run past it to the screen edges. */
    public static final int SIDE_DP = 28;

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

    /** Focusable card: glows, lifts and grows slightly when focused. */
    public static LinearLayout card(Context c, int orientation, int accent) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        styleFocusable(l, accent);
        return l;
    }

    /**
     * Text tiles and rows: glass at rest; when focused, a soft white glow around the edge that fades
     * out into the background, slightly brighter glass, lift and grow. Text keeps its colors.
     */
    public static void styleFocusable(View v, int accent) {
        styleFocusable(v, 1.06f);
    }

    public static void styleFocusable(View v, float focusScale) {
        Context c = v.getContext();
        float r = dp(c, 18);
        v.setBackground(glassWithGlow(c, r, CARD));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, has) -> lift(view, has, focusScale));
    }

    /**
     * Image tiles (app banners, posters): the same glow. The glow is drawn outside the tile, so the
     * tile itself doesn't clip; call {@link #roundCorners} on its picture instead.
     */
    public static void styleImageFocusable(View v, float focusScale) {
        Context c = v.getContext();
        v.setBackground(glassWithGlow(c, dp(c, 16), CARD));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, has) -> lift(view, has, focusScale));
    }

    /** Clips a view (e.g. a tile's picture) to rounded corners. */
    public static void roundCorners(View v, float radiusPx) {
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** Glass background that also glows while its view is focused. */
    public static StateListDrawable glassWithGlow(Context c, float radiusPx, int restFill) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focusedGlass(c, radiusPx));
        s.addState(new int[]{}, rounded(restFill, radiusPx, 0, 0));
        return s;
    }

    /** The focused look on its own: slightly brighter glass with the white glow around it. */
    public static android.graphics.drawable.Drawable focusedGlass(Context c, float radiusPx) {
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new Glow(radiusPx, dp(c, 22)), rounded(CARD_FOCUS, radiusPx, 0, 0)});
    }

    /**
     * A soft white halo just outside its bounds: rings that fade from bright at the edge to nothing
     * {@code spreadPx} further out. Drawn as plain rings, which every Android version draws quickly.
     */
    public static final class Glow extends android.graphics.drawable.Drawable {
        private static final int EDGE_ALPHA = 0x8C;
        private final float radius, spread;
        private final int steps;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Glow(float radiusPx, float spreadPx) {
            radius = radiusPx;
            spread = spreadPx;
            // One ring per pixel, edge to edge without overlapping, so the fade is smooth (no bands).
            steps = Math.max(8, Math.round(spreadPx));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(spreadPx / steps);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            for (int i = 0; i < steps; i++) {
                float t = (i + 0.5f) / steps, out = spread * t, fade = (1 - t) * (1 - t) * (1 - t);
                paint.setColor(withAlpha(FOCUS_GLOW, Math.round(EDGE_ALPHA * fade)));
                r.set(b.left - out, b.top - out, b.right + out, b.bottom + out);
                canvas.drawRoundRect(r, radius + out, radius + out, paint);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    public static void lift(View view, boolean has, float focusScale) {
        if (has) unclipPadding(view);
        float scale = has ? focusScale : 1f;
        view.animate().scaleX(scale).scaleY(scale).translationZ(has ? dp(view.getContext(), 18) : 0)
                .setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    /**
     * Lets a focused tile's growth and glow show past its row. Its own row stops clipping it, and
     * every container above it stops cutting children off at its padding (they do that even when they
     * let children draw outside their bounds). Done the first time the tile is focused. Containers
     * further up that must clip, like the Guide's channel list, keep doing so.
     */
    private static void unclipPadding(View v) {
        if (Boolean.TRUE.equals(v.getTag(R_UNCLIPPED))) return;
        v.setTag(R_UNCLIPPED, Boolean.TRUE);
        if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).setClipChildren(false);
        for (android.view.ViewParent p = v.getParent(); p instanceof android.view.ViewGroup; p = p.getParent()) {
            android.view.ViewGroup g = (android.view.ViewGroup) p;
            if (g.getId() == android.R.id.content) break;
            g.setClipToPadding(false);
        }
    }

    private static final int R_UNCLIPPED = R.id.padding_unclipped;

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
