package com.dave.livetvlauncher;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Pause / rewind for live TV.
 *
 * The tuner's stream can't be paused or seeked, so a background thread copies it to disk as it
 * arrives (in fixed-size segment files, oldest deleted once the buffer is full) and the player
 * reads from that copy instead. The writer keeps going while you're paused, so you can resume,
 * rewind, or skip forward up to the live point. One session per channel; stop() releases the tuner.
 */
@OptIn(markerClass = UnstableApi.class)
final class Timeshift {
    private static final long SEG = 16L * 1024 * 1024;
    private static final int TS_PACKET = 188;

    private final File dir;
    private final String url;
    private final long maxBytes;
    private final Thread writer;

    private volatile long written;        // bytes received since the session started
    private volatile long oldest;         // first byte still on disk (segment aligned)
    private volatile boolean stopped;
    private volatile IOException failure;
    private volatile HttpURLConnection conn;
    /** (wall-clock ms, byte offset) samples so times and positions can be converted. */
    private final ArrayList<long[]> index = new ArrayList<>();

    Timeshift(File cacheDir, String url) {
        // Each session has its own folder: the previous channel's clean-up (which runs a moment after a
        // channel change) must not delete this one's files. Folders left behind by a crash are removed here.
        File root = new File(cacheDir, "timeshift");
        File[] old = root.listFiles();
        if (old != null) for (File f : old) {
            if (f.isDirectory()) deleteAll(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        this.dir = new File(root, "s" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // Up to 2 GB (about 15-20 min of HD), but never more than a third of the free space.
        this.maxBytes = Math.max(4 * SEG, Math.min(2048L * 1024 * 1024, dir.getUsableSpace() / 3));
        this.url = url;
        this.writer = new Thread(this::run, "timeshift-writer");
        writer.start();
    }

    /** Stop recording, close the tuner connection and delete the buffer. */
    void stop() {
        stopped = true;
        synchronized (this) {
            if (http != null) try { http.close(); } catch (IOException ignored) { }
            http = null;
        }
        HttpURLConnection c = conn;
        if (c != null) new Thread(c::disconnect).start(); // disconnect can block; keep it off the UI thread
        writer.interrupt();
        new Thread(() -> {
            try { writer.join(3000); } catch (InterruptedException ignored) { }
            deleteAll(dir);
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }).start();
    }

    /** Oldest moment still in the buffer, or -1 before any data has arrived. */
    synchronized long oldestTimeMs() {
        for (long[] e : index) if (e[1] >= oldest) return e[0];
        return -1;
    }

    /** Wall-clock time the given byte offset was received. */
    synchronized long timeAt(long offset) {
        long t = -1;
        // offsetAt() rounds down to a packet boundary, so allow for that when matching it back.
        for (long[] e : index) { if (e[1] >= offset + TS_PACKET) break; t = e[0]; }
        return t >= 0 ? t : (index.isEmpty() ? System.currentTimeMillis() : index.get(0)[0]);
    }

    /** Byte offset (packet aligned, still on disk) received at or just before the given time. */
    synchronized long offsetAt(long timeMs) {
        long off = -1;
        for (long[] e : index) {
            if (e[1] < oldest) continue;
            if (e[0] > timeMs && off >= 0) break;
            off = e[1];
            if (e[0] > timeMs) break;
        }
        if (off < 0) off = oldest;
        return off - (off % TS_PACKET);
    }

    /** Player data source that reads this buffer starting at {@code startOffset}. */
    DataSource.Factory sourceFrom(long startOffset) {
        return () -> new Reader(startOffset);
    }

    // ---- Local HTTP view of the buffer, for VLC (which can't use the player data source above) ----

    private java.net.ServerSocket http;

    /** http://127.0.0.1:port/ts?o=offset — streams the buffer from {@code offset}, following the live point. */
    synchronized String httpUrl(long offset) throws IOException {
        if (http == null) {
            http = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this::serve, "timeshift-http");
            t.setDaemon(true);
            t.start();
        }
        return "http://127.0.0.1:" + http.getLocalPort() + "/ts?o=" + offset;
    }

    private void serve() {
        java.net.ServerSocket s = http;
        while (s != null && !s.isClosed()) {
            try {
                java.net.Socket c = s.accept();
                Thread t = new Thread(() -> serveOne(c), "timeshift-http-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void serveOne(java.net.Socket c) {
        Reader r = null;
        try (java.net.Socket sock = c) {
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream()));
            String first = in.readLine();
            long offset = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]o=(\\d+)").matcher(first == null ? "" : first);
            if (m.find()) offset = Long.parseLong(m.group(1));
            java.io.OutputStream out = sock.getOutputStream();
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            r = new Reader(offset);
            r.open(new DataSpec(Uri.parse("timeshift://buffer")));
            byte[] buf = new byte[64 * 1024];
            while (true) {
                int n = r.read(buf, 0, buf.length);
                if (n == C.RESULT_END_OF_INPUT) break;
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // VLC hung up (seek, pause long enough to time out, or closed): fine.
        } finally {
            if (r != null) r.close();
        }
    }

    private void run() {
        FileOutputStream out = null;
        long outSeg = -1, lastIndex = 0;
        byte[] buf = new byte[64 * 1024];
        try {
            URL u = new URL(url);
            if (!"http".equalsIgnoreCase(u.getProtocol()) && !"https".equalsIgnoreCase(u.getProtocol()))
                throw new IOException("not a web address (" + u.getProtocol() + ")");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            conn = c;
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000); // Tunarr can take several seconds to start transcoding
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Tuner said HTTP " + code + (code == 503 ? " (all tuners busy)" : ""));
            try (InputStream in = c.getInputStream()) {
                while (!stopped) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    int done = 0;
                    while (done < n) {
                        long pos = written;
                        long seg = pos / SEG;
                        if (seg != outSeg) {
                            if (out != null) out.close();
                            out = new FileOutputStream(segFile(seg));
                            outSeg = seg;
                        }
                        int chunk = (int) Math.min(n - done, SEG - (pos % SEG));
                        out.write(buf, done, chunk);
                        done += chunk;
                        written = pos + chunk;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastIndex >= 250) {
                        synchronized (this) { index.add(new long[]{now, written}); }
                        lastIndex = now;
                    }
                    trim();
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!stopped) {
                failure = e instanceof IOException ? (IOException) e : new IOException(e);
                AppLog.e("Timeshift", "Stream stopped after " + written / 1024 + " KB", e);
            }
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            stopped = true;
        }
    }

    /** Drop whole segments from the front once the buffer is over its size limit. */
    private void trim() {
        while (written - oldest > maxBytes + SEG) {
            long seg = oldest / SEG;
            // Move the start past the segment first, so a reader never opens a file that's being deleted.
            synchronized (this) {
                oldest += SEG;
                while (index.size() > 1 && index.get(1)[1] <= oldest) index.remove(0);
            }
            //noinspection ResultOfMethodCallIgnored
            segFile(seg).delete();
        }
    }

    private File segFile(long seg) { return new File(dir, "seg" + seg + ".ts"); }

    private static void deleteAll(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Reads the buffer like a never-ending file, waiting at the live point for more data. */
    private final class Reader extends BaseDataSource {
        private final long start;
        private long pos;
        private long openSeg = -1;
        private RandomAccessFile file;
        private Uri uri;
        private volatile boolean closed;

        Reader(long start) { super(false); this.start = start; }

        @Override public long open(DataSpec spec) throws IOException {
            uri = spec.uri;
            transferInitializing(spec);
            pos = start + spec.position;
            closed = false;
            transferStarted(spec);
            return C.LENGTH_UNSET;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                if (closed) return C.RESULT_END_OF_INPUT;
                if (pos < oldest) { pos = oldest; closeFile(); } // paused past the buffer: skip ahead
                long avail = written - pos;
                if (avail > 0) {
                    long seg = pos / SEG;
                    try {
                        if (seg != openSeg) {
                            closeFile();
                            file = new RandomAccessFile(segFile(seg), "r");
                            openSeg = seg;
                        }
                    } catch (FileNotFoundException e) {
                        if (pos < oldest) continue; // trimmed underneath us
                        throw e;
                    }
                    int n = (int) Math.min(length, Math.min(avail, SEG - (pos % SEG)));
                    file.seek(pos % SEG);
                    int r = file.read(buffer, offset, n);
                    if (r > 0) {
                        pos += r;
                        bytesTransferred(r);
                        return r;
                    }
                }
                if (stopped) {
                    IOException f = failure;
                    if (f != null && written == 0) throw f;
                    return C.RESULT_END_OF_INPUT;
                }
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        }

        @Override public Uri getUri() { return uri; }

        @Override public void close() {
            closed = true;
            closeFile();
            if (uri != null) { uri = null; transferEnded(); }
        }

        private void closeFile() {
            if (file != null) try { file.close(); } catch (IOException ignored) { }
            file = null;
            openSeg = -1;
        }
    }
}
