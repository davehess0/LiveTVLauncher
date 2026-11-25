package com.dave.livetvlauncher;

import java.util.ArrayList;
import java.util.List;

/** One channel from the HDHomeRun lineup, plus whatever guide data we could fetch for it. */
public final class Channel {
    public String number = "";     // GuideNumber, e.g. "5.1"
    public String name = "";       // GuideName, e.g. "KCTV-DT"
    public String streamUrl = "";  // e.g. http://192.168.1.50:5004/auto/v5.1
    public String logoUrl;         // from the guide API, may be null
    public boolean hd;
    public boolean favorite;
    public boolean tunarr;         // from the Tunarr server rather than the antenna tuner
    public boolean iptv;           // from an IPTV (M3U) playlist
    public String group = "";      // IPTV group-title
    public String guideId = "";    // IPTV tvg-id, to match the XMLTV guide
    public String guideName = "";  // IPTV tvg-name (or the channel name)
    public final List<Airing> airings = new ArrayList<>();

    /**
     * The name to show. IPTV playlists often prefix every name with a country tag ("US: BBC America",
     * "UK | Dave"); that's dropped, since the whole list shares it.
     */
    public String displayName() {
        if (!iptv) return name;
        String n = name.replaceFirst("^\\s*[A-Za-z]{2,3}\\s*[:|]\\s*", "").trim();
        return n.isEmpty() ? name : n;
    }

    public static final class Airing {
        public long start;  // unix seconds
        public long end;    // unix seconds
        public String title = "";
        public String episodeTitle = "";
        public String episodeNumber = "";
        public String synopsis = "";
        public String imageUrl;

        /** 0..1 progress through this airing at the given time. */
        public float progress(long nowSec) {
            if (end <= start) return 0f;
            float p = (nowSec - start) / (float) (end - start);
            return Math.max(0f, Math.min(1f, p));
        }
    }

    public Airing now(long nowSec) {
        for (Airing a : airings) if (a.start <= nowSec && nowSec < a.end) return a;
        return null;
    }

    public Airing next(long nowSec) {
        Airing best = null;
        for (Airing a : airings) {
            if (a.start > nowSec && (best == null || a.start < best.start)) best = a;
        }
        return best;
    }

    /** Digits only, so a remote with no "." key can still type 51 for 5.1. */
    public String digits() {
        return number.replaceAll("[^0-9]", "");
    }

    /** Sort key so 2.1 < 4.1 < 10.1 < 41.3 (string sort would get this wrong); Tunarr, then IPTV, follow the antenna ones. */
    public double sortKey() {
        try {
            String[] p = number.split("[.\\-]");
            double major = Double.parseDouble(p[0]);
            double minor = p.length > 1 ? Double.parseDouble(p[1]) : 0;
            return (iptv ? 2e9 : tunarr ? 1e9 : 0) + major * 1000 + minor;
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }
}
