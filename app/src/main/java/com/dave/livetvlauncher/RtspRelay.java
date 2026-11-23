package com.dave.livetvlauncher;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny local RTSP relay that repairs Eufy keyframes.
 *
 * Eufy cameras send each keyframe as one oversized H.264 unit labelled "SPS" that actually holds
 * SPS + PPS + IDR joined by Annex-B start codes (00 00 00 01). Players go by the label, never see
 * an IDR (keyframe), and wait forever. This relay sits between the player and the camera
 * (player → 127.0.0.1:port → camera), passes everything through unchanged, and only rewrites those
 * units into three proper NAL units (re-fragmented, with RTP sequence numbers renumbered).
 *
 * Works for RTP-over-TCP (interleaved), which is what the player is told to use through the relay.
 */
final class RtspRelay {
    private static final int MAX_PAYLOAD = 1400;

    private final String camHost;
    private final int camPort;
    private final ServerSocket server;
    private final List<Socket> open = new ArrayList<>();
    private volatile boolean closed;
    private volatile int repaired; // keyframes fixed, for the log

    /** Starts listening; {@link #localUrl} gives the address to hand to the player. */
    RtspRelay(String camHost, int camPort) throws IOException {
        this.camHost = camHost;
        this.camPort = camPort;
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        Thread t = new Thread(this::acceptLoop, "rtsp-relay");
        t.setDaemon(true);
        t.start();
    }

    /** Same address as the camera's (user info, path, query) but pointing at this relay. */
    static String localUrl(String cameraUrl, int localPort) {
        int scheme = cameraUrl.indexOf("://") + 3;
        int at = cameraUrl.lastIndexOf('@');
        int hostStart = at >= scheme ? at + 1 : scheme;
        int pathStart = cameraUrl.indexOf('/', hostStart);
        String rest = pathStart >= 0 ? cameraUrl.substring(pathStart) : "/";
        return cameraUrl.substring(0, hostStart) + "127.0.0.1:" + localPort + rest;
    }

    int port() { return server.getLocalPort(); }
    int repaired() { return repaired; }

    void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        synchronized (open) {
            for (Socket s : open) try { s.close(); } catch (IOException ignored) { }
            open.clear();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                Socket cam = new Socket();
                cam.connect(new InetSocketAddress(camHost, camPort), 8000);
                cam.setTcpNoDelay(true);
                client.setTcpNoDelay(true);
                synchronized (open) { open.add(client); open.add(cam); }
                pump("relay-up", () -> copy(client.getInputStream(), cam.getOutputStream()), client, cam);
                pump("relay-down", () -> repairStream(cam.getInputStream(), client.getOutputStream()), client, cam);
            } catch (IOException e) {
                if (!closed) AppLog.w("Cameras", "Relay: couldn't connect to camera " + camHost + ":" + camPort
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    private interface Io { void run() throws IOException; }

    private void pump(String name, Io io, Socket a, Socket b) {
        Thread t = new Thread(() -> {
            try { io.run(); } catch (IOException ignored) { }
            try { a.close(); } catch (IOException ignored) { }
            try { b.close(); } catch (IOException ignored) { }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
    }

    // ---- camera → player: RTSP text passes through; interleaved RTP is checked for Eufy keyframes ----

    /** Per interleaved channel: a keyframe unit being collected, and how far sequence numbers have shifted. */
    private static final class Chan {
        ByteArrayOutputStream unit;  // reassembled NAL (header byte + payload) while collecting
        byte[] firstHeader;          // RTP header of the unit's first fragment
        int fragments;               // packets swallowed for this unit
        int seqShift;                // added to every outgoing sequence number
        int lastSeq = -1;
    }

    private void repairStream(InputStream in, OutputStream out) throws IOException {
        Map<Integer, Chan> chans = new HashMap<>();
        java.io.BufferedInputStream bin = new java.io.BufferedInputStream(in, 64 * 1024);
        while (true) {
            int b = bin.read();
            if (b < 0) throw new EOFException();
            if (b != '$') {
                // An RTSP reply: copy its header lines, then exactly Content-Length bytes of body.
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                head.write(b);
                int c0 = 0, c1 = 0, c2 = 0, c3 = b;
                while (!(c0 == '\r' && c1 == '\n' && c2 == '\r' && c3 == '\n')) {
                    int x = readByte(bin);
                    head.write(x);
                    c0 = c1; c1 = c2; c2 = c3; c3 = x;
                }
                byte[] h = head.toByteArray();
                out.write(h);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)content-length:\\s*(\\d+)")
                        .matcher(new String(h, java.nio.charset.StandardCharsets.ISO_8859_1));
                if (m.find()) out.write(readFully(bin, Integer.parseInt(m.group(1))));
                out.flush();
                continue;
            }
            int ch = readByte(bin);
            int len = (readByte(bin) << 8) | readByte(bin);
            byte[] pkt = readFully(bin, len);
            for (byte[] p : rewrite(chans, ch, pkt)) {
                out.write('$');
                out.write(ch);
                out.write((p.length >> 8) & 0xFF);
                out.write(p.length & 0xFF);
                out.write(p);
            }
            if (bin.available() == 0) out.flush();
        }
    }

    /** Returns the packets to send for one incoming RTP packet (usually just itself, renumbered). */
    private List<byte[]> rewrite(Map<Integer, Chan> chans, int ch, byte[] pkt) {
        List<byte[]> outList = new ArrayList<>(1);
        if ((ch & 1) == 1 || pkt.length < 14 || (pkt[0] & 0xC0) != 0x80) { // RTCP or not RTP v2
            outList.add(pkt);
            return outList;
        }
        Chan c = chans.get(ch);
        if (c == null) { c = new Chan(); chans.put(ch, c); }
        int off = 12 + 4 * (pkt[0] & 0x0F);
        if ((pkt[0] & 0x10) != 0 && off + 4 <= pkt.length)
            off += 4 + 4 * (((pkt[off + 2] & 0xFF) << 8) | (pkt[off + 3] & 0xFF));
        if (off + 2 > pkt.length) { outList.add(renumber(c, pkt)); return outList; }
        int nalType = pkt[off] & 0x1F;

        if (nalType == 28) { // FU-A fragment
            int fu = pkt[off + 1] & 0xFF;
            boolean start = (fu & 0x80) != 0, end = (fu & 0x40) != 0;
            int innerType = fu & 0x1F;
            if (start && innerType == 7) {
                // Possibly a Eufy "SPS" unit hiding PPS + IDR: collect it.
                c.unit = new ByteArrayOutputStream(128 * 1024);
                c.unit.write((pkt[off] & 0xE0) | innerType);
                c.unit.write(pkt, off + 2, pkt.length - off - 2);
                c.firstHeader = java.util.Arrays.copyOf(pkt, off);
                c.fragments = 1;
                if (!end) return outList;
            } else if (c.unit != null && !start) {
                c.unit.write(pkt, off + 2, pkt.length - off - 2);
                c.fragments++;
                if (!end) return outList;
            } else {
                outList.add(renumber(c, pkt));
                return outList;
            }
            // Unit complete: split it and send the pieces.
            byte[] unit = c.unit.toByteArray();
            boolean marker = (pkt[1] & 0x80) != 0;
            List<byte[]> nals = splitAnnexB(unit);
            byte[] hdr = c.firstHeader;
            c.unit = null;
            if (nals.size() > 1) repaired++;
            List<byte[]> pieces = new ArrayList<>();
            for (int i = 0; i < nals.size(); i++) packetize(hdr, nals.get(i), marker && i == nals.size() - 1, pieces);
            // The original used c.fragments sequence numbers; we use pieces.size(). Renumber from the first.
            int firstSeq = ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
            for (int i = 0; i < pieces.size(); i++) {
                byte[] p = pieces.get(i);
                int seq = (firstSeq + c.seqShift + i) & 0xFFFF;
                p[2] = (byte) (seq >> 8);
                p[3] = (byte) seq;
                c.lastSeq = seq;
                outList.add(p);
            }
            c.seqShift += pieces.size() - c.fragments;
            return outList;
        }
        outList.add(renumber(c, pkt));
        return outList;
    }

    private static byte[] renumber(Chan c, byte[] pkt) {
        if (c.seqShift != 0) {
            int seq = ((((pkt[2] & 0xFF) << 8) | (pkt[3] & 0xFF)) + c.seqShift) & 0xFFFF;
            pkt[2] = (byte) (seq >> 8);
            pkt[3] = (byte) seq;
        }
        return pkt;
    }

    /** Splits "NAL [00 00 00 01] NAL [00 00 01] NAL" into NAL units. */
    static List<byte[]> splitAnnexB(byte[] u) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i + 3 <= u.length) {
            if (u[i] == 0 && u[i + 1] == 0 && (u[i + 2] == 1 || (i + 3 < u.length && u[i + 2] == 0 && u[i + 3] == 1))) {
                int scLen = u[i + 2] == 1 ? 3 : 4;
                if (i > start) out.add(java.util.Arrays.copyOfRange(u, start, i));
                start = i + scLen;
                i = start;
                continue;
            }
            i++;
        }
        if (start < u.length) out.add(java.util.Arrays.copyOfRange(u, start, u.length));
        return out;
    }

    /** One NAL → a single-NAL RTP packet, or FU-A fragments if it's large. Sequence numbers are set by the caller. */
    private static void packetize(byte[] hdr, byte[] nal, boolean marker, List<byte[]> out) {
        if (nal.length <= MAX_PAYLOAD) {
            out.add(withHeader(hdr, marker, nal, 0, nal.length, null));
            return;
        }
        byte indicator = (byte) ((nal[0] & 0xE0) | 28);
        int type = nal[0] & 0x1F;
        for (int p = 1; p < nal.length; p += MAX_PAYLOAD - 2) {
            int n = Math.min(MAX_PAYLOAD - 2, nal.length - p);
            boolean first = p == 1, last = p + n >= nal.length;
            byte fuHeader = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | type);
            out.add(withHeader(hdr, marker && last, nal, p, n, new byte[]{indicator, fuHeader}));
        }
    }

    private static byte[] withHeader(byte[] hdr, boolean marker, byte[] data, int from, int n, byte[] prefix) {
        int pre = prefix == null ? 0 : prefix.length;
        byte[] p = new byte[hdr.length + pre + n];
        System.arraycopy(hdr, 0, p, 0, hdr.length);
        p[1] = (byte) (marker ? (p[1] | 0x80) : (p[1] & 0x7F));
        if (prefix != null) System.arraycopy(prefix, 0, p, hdr.length, pre);
        System.arraycopy(data, from, p, hdr.length + pre, n);
        return p;
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new EOFException();
            got += r;
        }
        return b;
    }
}
