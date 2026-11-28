package com.dave.livetvlauncher;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tells the two kinds of Dolby Vision profile 7 (UHD Blu-ray) apart by looking at the file itself.
 *
 * Profile 7 carries a second "enhancement layer" (EL) next to the HDR10 base layer:
 *  - MEL (minimal EL): the EL is all but empty, a few hundred bytes a frame. The Shield's Dolby Vision
 *    decoder plays these fine.
 *  - FEL (full EL): the EL holds real picture detail, tens of KB a frame. The Shield can't use it, and the
 *    Dolby Vision processing meant for base + EL together makes bright areas flicker. These play best as HDR10.
 *
 * In the file (MKV / MP4, one video track) each frame is a run of length-prefixed HEVC NAL units: the base
 * layer's, then the EL's wrapped in NAL type 63 (header bytes 7E 01), then the Dolby Vision RPU in NAL type
 * 62 (7C 01). Two short reads from the middle of the file are enough to measure EL bytes per frame.
 */
final class DvScan {
    private DvScan() {}

    enum Kind { FEL, MEL, UNKNOWN }

    static final class Result {
        Kind kind = Kind.UNKNOWN;
        long elBytes;
        int frames;
        String why = "";
    }

    /** EL bytes per frame above this is a full enhancement layer (MEL is ~100-500, FEL ~10,000+). */
    static final int FEL_BYTES_PER_FRAME = 2000;
    private static final int CHUNK = 6 * 1024 * 1024;
    private static final int MAX_NAL = 4 * 1024 * 1024;

    /** Reads two 6 MB pieces of the file (at 25 % and 55 %) over HTTP range requests. */
    static Result scan(String url) {
        Result r = new Result();
        try {
            long size = totalSize(url);
            if (size < 4L * CHUNK) { r.why = "file too small to sample"; return r; }
            for (double at : new double[]{0.25, 0.55}) {
                long off = (long) (size * at);
                byte[] buf = read(url, off, CHUNK);
                count(buf, buf.length, r);
            }
            classify(r);
        } catch (Exception e) {
            r.kind = Kind.UNKNOWN;
            r.why = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    static void classify(Result r) {
        if (r.frames < 20) { r.kind = Kind.UNKNOWN; r.why = "only " + r.frames + " frames found"; return; }
        long perFrame = r.elBytes / r.frames;
        r.kind = perFrame > FEL_BYTES_PER_FRAME ? Kind.FEL : Kind.MEL;
        r.why = perFrame + " EL bytes a frame over " + r.frames + " frames";
    }

    /**
     * Adds up EL NAL bytes and counts RPUs (one per frame). A NAL is only counted when the bytes after it
     * start another plausible NAL (EL NALs are followed by more EL or the RPU), so stray byte patterns in
     * the container don't count.
     */
    static void count(byte[] b, int len, Result r) {
        for (int i = 0; i + 6 <= len; i++) {
            int h0 = b[i + 4] & 0xFF, h1 = b[i + 5] & 0xFF;
            boolean el = h0 == 0x7E && h1 == 0x01, rpu = h0 == 0x7C && h1 == 0x01;
            if (!el && !rpu) continue;
            long n = ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16) | ((b[i + 2] & 0xFFL) << 8) | (b[i + 3] & 0xFFL);
            if (n < 3 || n > MAX_NAL) continue;
            if (rpu) {
                // RPUs are small and end the frame.
                if (n <= 4096) r.frames++;
                continue;
            }
            int next = i + 4 + (int) n;
            if (next + 6 > len) continue;
            if (!looksLikeNal(b, next)) continue;
            r.elBytes += n;
            i = next - 1; // skip over this NAL's payload
        }
    }

    private static boolean looksLikeNal(byte[] b, int at) {
        long n = ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
        if (n < 2 || n > MAX_NAL) return false;
        int h0 = b[at + 4] & 0xFF, h1 = b[at + 5] & 0xFF;
        return (h0 & 0x80) == 0 && h1 >= 0x01 && h1 <= 0x07;
    }

    private static long totalSize(String url) throws Exception {
        HttpURLConnection c = open(url, "bytes=0-0");
        try {
            int code = c.getResponseCode();
            if (code != 206 && code != 200) throw new java.io.IOException("HTTP " + code);
            String cr = c.getHeaderField("Content-Range"); // "bytes 0-0/123456789"
            if (cr != null && cr.contains("/")) return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            return c.getContentLengthLong();
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(String url, long off, int n) throws Exception {
        HttpURLConnection c = open(url, "bytes=" + off + "-" + (off + n - 1));
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 206) throw new java.io.IOException("server ignored the range request");
            byte[] buf = new byte[n];
            int got = 0;
            while (got < n) {
                int k = in.read(buf, got, n - got);
                if (k < 0) break;
                got += k;
            }
            return got == n ? buf : java.util.Arrays.copyOf(buf, got);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Range", range);
        return c;
    }
}
