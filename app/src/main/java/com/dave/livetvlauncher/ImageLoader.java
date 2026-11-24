package com.dave.livetvlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny in-memory image loader for channel logos and show art (no third-party library). */
public final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {}

    /** Loads url into view; maxPx caps decoded size to keep memory low on the Shield. */
    public static void load(final ImageView view, final String url, final int maxPx) {
        view.setTag(url);
        if (url == null) { view.setImageDrawable(null); return; }
        Bitmap hit = CACHE.get(url);
        if (hit != null) { view.setImageBitmap(hit); return; }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap b = fetch(url, maxPx);
            if (b == null) return;
            CACHE.put(url, b);
            MAIN.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(b); });
        });
    }

    // ---- Full-screen background art ----

    /** Kept apart from the tile cache: a full-screen picture is large, so only the last few are kept. */
    private static final LruCache<String, Bitmap> BACKDROPS = new LruCache<>(3);

    /** Art at least this wide (px) is shown sharp; anything smaller would look pixelated when stretched. */
    private static final int SHARP_MIN_WIDTH = 1000;

    /**
     * Loads background art for the whole screen. Large pictures are shown as they are (sharp); small
     * ones (most guide stills and channel logos) become a smooth blur of their colors instead of a
     * blown-up, pixelated copy.
     */
    public static void loadBackdrop(final ImageView view, final String url, final int screenPx) {
        view.setTag(url);
        if (url == null) { view.setImageDrawable(null); return; }
        Bitmap hit = BACKDROPS.get(url);
        if (hit != null) { view.setImageBitmap(hit); return; }
        POOL.execute(() -> {
            Bitmap b = fetch(url, screenPx);
            if (b == null) return;
            Bitmap shown = b.getWidth() >= SHARP_MIN_WIDTH ? b : blurred(b);
            if (shown != b) b.recycle();
            BACKDROPS.put(url, shown);
            MAIN.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(shown); });
        });
    }

    /**
     * A soft, out-of-focus version: shrink to a few dozen pixels, blur that, then enlarge with
     * smoothing. Enlarging a tiny blurred picture gives clean gradients instead of visible pixels.
     */
    private static Bitmap blurred(Bitmap src) {
        int w = 48, h = Math.max(1, Math.round(48f * src.getHeight() / Math.max(1, src.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        for (int pass = 0; pass < 3; pass++) boxBlur(px, w, h, 2);
        Bitmap blur = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        Bitmap big = Bitmap.createScaledBitmap(blur, 480, Math.max(1, Math.round(480f * h / w)), true);
        blur.recycle();
        return big;
    }

    /** One horizontal and one vertical box-blur pass of the given radius, in place. */
    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 2; pass++) {
            boolean horiz = pass == 0;
            int lines = horiz ? h : w, len = horiz ? w : h;
            for (int line = 0; line < lines; line++) {
                for (int i = 0; i < len; i++) {
                    int a = 0, rr = 0, g = 0, bb = 0, n = 0;
                    for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                        int p = horiz ? px[line * w + k] : px[k * w + line];
                        a += p >>> 24; rr += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; bb += p & 0xFF; n++;
                    }
                    int idx = horiz ? line * w + i : i * w + line;
                    tmp[idx] = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (bb / n);
                }
            }
            System.arraycopy(tmp, 0, px, 0, px.length);
        }
    }

    private static Bitmap fetch(String url, int maxPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
