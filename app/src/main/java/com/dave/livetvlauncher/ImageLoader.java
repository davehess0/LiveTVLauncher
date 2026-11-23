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
