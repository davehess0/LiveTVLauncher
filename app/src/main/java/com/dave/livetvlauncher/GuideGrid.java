package com.dave.livetvlauncher;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full Guide: a cable-box style program grid, used full screen ({@link GridActivity}) and over live
 * video in the player. Channels down the left, two hours of time across
 * the top, up to 24 hours ahead. Left / Right move between shows (the time window follows), Up /
 * Down change channel, fast-forward / rewind jump two hours, channel up / down page. OK on a show
 * that's on now tunes it; on a later show it offers Remind me (and Watch the channel now).
 */
public final class GuideGrid extends FrameLayout implements ChannelStore.Listener {
    /** OK on a show that's on now (or "Watch now"): play that channel. */
    public interface Host { void onWatch(Channel ch); }

    private static final int WINDOW_MIN = 120;
    private static final long SLOT_SEC = 30 * 60;
    private static final long JUMP_SEC = 2 * 3600;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Data: one row per channel, each a list of shows in time order.
    private final List<Channel> channels = new ArrayList<>();
    private final List<List<Channel.Airing>> rows = new ArrayList<>();
    private final Set<Channel.Airing> noGuide = Collections.newSetFromMap(new IdentityHashMap<>());

    // Selection and the visible time window (unix seconds).
    private long winStart;
    private int sel = -1;
    private Channel.Airing selShow;
    /** The time Up / Down try to stay at, so moving across channels doesn't drift. */
    private long anchor;

    // Views.
    private ScrollView scroller;
    private LinearLayout rowsBox;
    private FrameLayout ruler;
    private TextView dayLabel, clock, dTitle, dMeta, dStatus, dDesc, dHint, empty;
    private final List<LinearLayout> rowViews = new ArrayList<>();
    private final List<FrameLayout> strips = new ArrayList<>();
    private final Map<Channel.Airing, View> cellOf = new IdentityHashMap<>();
    private int chanW, stripW, rowH, rowGap;
    private float pxPerSec;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            clock.setText(Ui.clock(prefs.clock24h()));
            // A new half hour: shows that ended drop off and the window moves on.
            if (minStart() > winStart) rebuild(true);
            else if (sel >= 0) { drawRuler(); showDetails(); }
            handler.postDelayed(this, 30_000);
        }
    };

    private final Activity host;
    private final Host cb;

    /** @param overlay over live video: details on a fade at the top left, the grid on a dark panel below. */
    public GuideGrid(Activity host, boolean overlay, Host cb) {
        super(host);
        this.host = host;
        this.cb = cb;
        prefs = new Prefs(host);

        int side = Ui.dp(host, Ui.SIDE_DP);
        android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
        chanW = Ui.dp(host, 190);
        stripW = dm.widthPixels - 2 * side - chanW;
        pxPerSec = stripW / (WINDOW_MIN * 60f);
        rowH = Ui.dp(host, 54);
        rowGap = Ui.dp(host, 6);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);

        // Details of the selected show.
        LinearLayout details = new LinearLayout(host);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, Ui.dp(host, 10), 0, Ui.dp(host, 10));
        dTitle = Ui.text(host, 24, Ui.TEXT, true);
        dTitle.setSingleLine(true);
        dTitle.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(dTitle);
        dMeta = Ui.text(host, 15, Ui.ACCENT, true);
        dMeta.setSingleLine(true);
        dMeta.setEllipsize(TextUtils.TruncateAt.END);
        dMeta.setPadding(0, Ui.dp(host, 4), 0, 0);
        details.addView(dMeta);
        dStatus = Ui.text(host, 14, Ui.TEXT, false);
        dStatus.setPadding(0, Ui.dp(host, 2), 0, 0);
        details.addView(dStatus);
        dDesc = Ui.text(host, 14, Ui.SUB, false);
        dDesc.setMaxLines(overlay ? 3 : 2);
        dDesc.setEllipsize(TextUtils.TruncateAt.END);
        dDesc.setPadding(0, Ui.dp(host, 6), 0, 0);
        details.addView(dDesc);

        LinearLayout panel;
        if (!overlay) {
            root.setPadding(side, Ui.dp(host, 24), side, 0);
            LinearLayout header = new LinearLayout(host);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = Ui.text(host, 24, Ui.TEXT, true);
            title.setText("Full Guide");
            header.addView(title);
            header.addView(new View(host), Ui.lpWeight(0, 1, 1));
            clock = Ui.text(host, 20, Ui.TEXT, true);
            header.addView(clock);
            root.addView(header);
            root.addView(details, Ui.lp(-1, Ui.dp(host, 132)));
            panel = root;
        } else {
            // Over live video: details at the top left on a fade, so the picture still shows top right.
            FrameLayout top = new FrameLayout(host);
            top.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xF20A0A0C, 0xB30A0A0C, 0x000A0A0C}));
            LinearLayout box = new LinearLayout(host);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(side, Ui.dp(host, 22), side, 0);
            LinearLayout head = new LinearLayout(host);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = Ui.text(host, 15, Ui.TEXT, true);
            label.setText("Guide");
            head.addView(label);
            box.addView(head);
            box.addView(details);
            top.addView(box, new FrameLayout.LayoutParams(Math.round(dm.widthPixels * 0.62f), -1));
            // The time, top right like the full-screen guide; on a dark pill so it reads over any picture.
            clock = Ui.text(host, 20, Ui.TEXT, true);
            clock.setPadding(Ui.dp(host, 16), Ui.dp(host, 6), Ui.dp(host, 16), Ui.dp(host, 6));
            clock.setBackground(Ui.rounded(0x990A0A0C, Ui.dp(host, 18), 0, 0));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            clp.setMargins(0, Ui.dp(host, 18), side, 0);
            top.addView(clock, clp);
            root.addView(top, Ui.lp(-1, Math.round(dm.heightPixels * 0.36f)));

            panel = new LinearLayout(host);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(0xFF0A0A0C); // solid: the grid stays readable over any picture
            panel.setPadding(side, Ui.dp(host, 10), side, 0);
            root.addView(panel, Ui.lpWeight(-1, 0, 1));
        }

        // Time ruler.
        LinearLayout rulerRow = new LinearLayout(host);
        rulerRow.setGravity(Gravity.CENTER_VERTICAL);
        dayLabel = Ui.text(host, 14, Ui.TEXT, true);
        rulerRow.addView(dayLabel, Ui.lp(chanW, -2));
        ruler = new FrameLayout(host);
        rulerRow.addView(ruler, Ui.lp(stripW, Ui.dp(host, 28)));
        panel.addView(rulerRow);

        // The grid.
        scroller = new ScrollView(host);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFocusable(false);
        // Clips rows to the grid area (top and bottom padding leaves room for the selection glow).
        scroller.setDescendantFocusability(ScrollView.FOCUS_BLOCK_DESCENDANTS); // keys come through handleKey
        rowsBox = new LinearLayout(host);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setClipChildren(false);
        rowsBox.setPadding(0, Ui.dp(host, 11), 0, Ui.dp(host, 11));
        scroller.addView(rowsBox);
        panel.addView(scroller, Ui.lpWeight(-1, 0, 1));

        dHint = Ui.text(host, 13, Ui.SUB, false);
        dHint.setPadding(0, Ui.dp(host, 6), 0, Ui.dp(host, 14));
        panel.addView(dHint);

        addView(root, new FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(host, 18, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        addView(empty, new FrameLayout.LayoutParams(-1, -1));
        clock.setText(Ui.clock(prefs.clock24h()));
    }

    /** Call when shown: loads channels and keeps the clock and the "now" line moving. */
    public void start() {
        ChannelStore.get().addListener(this);
        ChannelStore.get().refreshIfStale(host, 30 * 60_000L);
        rebuild(true);
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 30_000);
    }

    /** Call when hidden. */
    public void stop() {
        ChannelStore.get().removeListener(this);
        handler.removeCallbacks(ticker);
    }

    /** Opens on host channel at the current time (the player's guide opens on what you're watching). */
    public void selectChannel(String number) {
        if (rows.isEmpty()) return;
        int row = 0;
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).number.equals(number)) { row = i; break; }
        winStart = minStart();
        anchor = nowSec();
        drawWindow();
        select(row, showAt(row, anchor), false);
        final int r = row;
        scroller.post(() -> scrollToRow(r, false));
    }

    @Override public void onChannelsChanged() { handler.post(() -> rebuild(true)); }

    // ---- Data ----

    private static long nowSec() { return System.currentTimeMillis() / 1000L; }
    private static long floorSlot(long t) { return t - Math.floorMod(t, SLOT_SEC); }
    private long minStart() { return floorSlot(nowSec()); }
    private long winEnd() { return winStart + WINDOW_MIN * 60L; }

    /** Reloads channels and shows, keeping the selection (same channel, same time) when it can. */
    private void rebuild(boolean keepSelection) { rebuild(keepSelection, null); }

    /** order: the channels to show in this order (null = the current list from ChannelStore). */
    private void rebuild(boolean keepSelection, List<Channel> order) {
        String selChannel = sel >= 0 && sel < channels.size() ? channels.get(sel).number : null;
        long selTime = selShow != null ? Math.max(selShow.start, minStart()) : 0;

        channels.clear();
        rows.clear();
        noGuide.clear();
        long min = minStart();
        for (Channel ch : order != null ? order : ChannelStore.get().visible(prefs)) {
            List<Channel.Airing> shows = new ArrayList<>();
            for (Channel.Airing a : ch.airings) if (a.end > min && a.end > a.start) shows.add(a);
            shows.sort((x, y) -> Long.compare(x.start, y.start));
            if (shows.isEmpty()) {
                Channel.Airing none = new Channel.Airing();
                none.start = min;
                none.end = min + 24 * 3600;
                none.title = ch.displayName();
                none.synopsis = "No guide information for host channel.";
                noGuide.add(none);
                shows.add(none);
            }
            channels.add(ch);
            rows.add(shows);
        }

        if (channels.isEmpty()) {
            empty.setText(ChannelStore.get().isLoaded() ? "No channels to show." : "Loading channels…");
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);

        winStart = Math.max(min, winStart);
        int row = 0;
        if (keepSelection && selChannel != null) {
            for (int i = 0; i < channels.size(); i++) if (channels.get(i).number.equals(selChannel)) { row = i; break; }
        } else {
            // Start on the channel last watched.
            String last = prefs.lastChannel();
            for (int i = 0; i < channels.size(); i++) if (channels.get(i).number.equals(last)) { row = i; break; }
        }
        long at = selTime > 0 ? selTime : nowSec();
        if (selTime == 0) winStart = min;

        buildRows();
        anchor = at;
        select(row, showAt(row, at), false);
        scroller.post(() -> scrollToRow(sel, false));
    }

    /** The show in host row on at the given time, else the next one, else the last. */
    private Channel.Airing showAt(int row, long t) {
        List<Channel.Airing> shows = rows.get(row);
        for (Channel.Airing a : shows) if (a.start <= t && t < a.end) return a;
        for (Channel.Airing a : shows) if (a.start >= t) return a;
        return shows.get(shows.size() - 1);
    }

    // ---- Views ----

    private void buildRows() {
        rowsBox.removeAllViews();
        rowViews.clear();
        strips.clear();
        for (int i = 0; i < channels.size(); i++) {
            Channel ch = channels.get(i);
            LinearLayout row = new LinearLayout(host);
            row.setClipChildren(false);

            LinearLayout chan = new LinearLayout(host);
            chan.setOrientation(LinearLayout.VERTICAL);
            chan.setGravity(Gravity.CENTER_VERTICAL);
            chan.setPadding(Ui.dp(host, 14), 0, Ui.dp(host, 10), 0);
            chan.setBackground(Ui.glass(host, Ui.CARD, Ui.dp(host, 12), false));
            TextView num = Ui.text(host, 15, Ui.TEXT, true);
            num.setText(ch.number);
            num.setSingleLine(true);
            TextView name = Ui.text(host, 12, Ui.SUB, false);
            name.setText(ch.displayName());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            chan.addView(num);
            chan.addView(name);
            row.addView(chan, Ui.margins(Ui.lp(chanW - Ui.dp(host, 8), rowH), 0, 0, Ui.dp(host, 8), 0));

            FrameLayout strip = new FrameLayout(host);
            strip.setClipChildren(false);
            row.addView(strip, Ui.lp(stripW, rowH));
            strips.add(strip);
            rowViews.add(row);
            rowsBox.addView(row, Ui.margins(Ui.lp(-1, rowH), 0, 0, 0, rowGap));
        }
        drawWindow();
    }

    /** (Re)draws every row's shows for the current time window, and the ruler. */
    private void drawWindow() {
        cellOf.clear();
        loadReminders();
        for (int i = 0; i < strips.size(); i++) drawStrip(i);
        drawRuler();
        highlight();
    }

    private void drawStrip(int row) {
        FrameLayout strip = strips.get(row);
        strip.removeAllViews();
        long ws = winStart, we = winEnd(), now = nowSec();
        Channel ch = channels.get(row);
        for (Channel.Airing a : rows.get(row)) {
            if (a.end <= ws || a.start >= we) continue;
            int l = Math.round((Math.max(a.start, ws) - ws) * pxPerSec);
            int r = Math.round((Math.min(a.end, we) - ws) * pxPerSec);
            int w = r - l - Ui.dp(host, 4);
            if (w <= 0) continue;
            LinearLayout cell = new LinearLayout(host);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(Ui.dp(host, 10), 0, Ui.dp(host, 8), 0);
            boolean reminder = !noGuide.contains(a) && reminderKeys.contains(Reminders.Reminder.key(ch.number, a.start));
            TextView t = Ui.text(host, 14, Ui.TEXT, true);
            t.setText((a.start < ws ? "‹ " : "") + (reminder ? "🔔 " : "") + a.title);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(t);
            if (w > Ui.dp(host, 80) && !noGuide.contains(a)) {
                TextView tm = Ui.text(host, 12, a.start <= now && now < a.end ? Ui.ACCENT : Ui.SUB, false);
                tm.setText(time(host, a.start) + " – " + time(host, a.end));
                tm.setSingleLine(true);
                tm.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(tm);
            }
            cell.setBackground(cellBackground(false));
            // Touch: tap a show to select it, tap it again for OK.
            cell.setOnClickListener(v -> {
                if (row == sel && a == selShow) choose();
                else { anchor = Math.max(a.start, minStart()); select(row, a, true); }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -1);
            lp.leftMargin = l;
            strip.addView(cell, lp);
            cellOf.put(a, cell);
        }
        // Now: a thin red line through the row.
        if (now >= ws && now < we) {
            View line = new View(host);
            line.setBackgroundColor(Ui.LIVE_RED);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(host, 2), -1);
            lp.leftMargin = Math.round((now - ws) * pxPerSec) - Ui.dp(host, 3);
            strip.addView(line, lp);
        }
    }

    /** Reminders set, read once per redraw rather than once per show. */
    private final java.util.Set<String> reminderKeys = new java.util.HashSet<>();
    private void loadReminders() {
        reminderKeys.clear();
        for (Reminders.Reminder r : Reminders.all(host)) reminderKeys.add(r.key());
    }

    private android.graphics.drawable.Drawable cellBackground(boolean selected) {
        float r = Ui.dp(host, 12);
        if (!selected) return Ui.glass(host, Ui.CARD, r, false);
        // The usual focus glow, kept tight so it doesn't spill over the neighbouring shows.
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new Ui.Glow(r, Ui.dp(host, 9)), Ui.glass(host, Ui.CARD_FOCUS, r, true)});
    }

    private void drawRuler() {
        ruler.removeAllViews();
        dayLabel.setText(day(winStart));
        for (long t = winStart; t < winEnd(); t += SLOT_SEC) {
            TextView lab = Ui.text(host, 13, Ui.SUB, true);
            // Midnight inside the window: say which day it is.
            lab.setText(t != winStart && Math.floorMod(t + tzOffset(t), 86400) == 0 ? day(t) + " " + time(host, t) : time(host, t));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -1);
            lp.leftMargin = Math.round((t - winStart) * pxPerSec) + Ui.dp(host, 4);
            lab.setGravity(Gravity.CENTER_VERTICAL);
            ruler.addView(lab, lp);
            View tick = new View(host);
            tick.setBackgroundColor(Ui.SUB);
            tick.setAlpha(0.5f);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Ui.dp(host, 1), Ui.dp(host, 12), Gravity.BOTTOM);
            tl.leftMargin = Math.round((t - winStart) * pxPerSec);
            ruler.addView(tick, tl);
        }
        long now = nowSec();
        if (now >= winStart && now < winEnd()) {
            View mark = new View(host);
            mark.setBackground(Ui.rounded(Ui.LIVE_RED, Ui.dp(host, 4), 0, 0));
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(Ui.dp(host, 8), Ui.dp(host, 8), Gravity.BOTTOM);
            ml.leftMargin = Math.round((now - winStart) * pxPerSec) - Ui.dp(host, 6);
            ruler.addView(mark, ml);
        }
    }

    // ---- Selection ----

    private View selCell;

    private void select(int row, Channel.Airing show, boolean animate) {
        if (row < 0 || row >= rows.size() || show == null) return;
        int oldRow = sel;
        sel = row;
        selShow = show;
        // Keep the selected show on screen: move the window to it.
        long ws = winStart;
        if (show.start >= winEnd()) ws = floorSlot(show.start) - SLOT_SEC; // half an hour of context on the left
        else if (show.end <= winStart || (show.start < winStart && show.start >= minStart())) ws = floorSlot(Math.max(show.start, minStart()));
        ws = Math.max(minStart(), ws);
        if (ws != winStart) { winStart = ws; drawWindow(); }
        else highlight();
        if (oldRow != row) {
            if (oldRow >= 0 && oldRow < rowViews.size()) markRow(oldRow, false);
            markRow(row, true);
            scrollToRow(row, animate);
        }
        showDetails();
    }

    private void markRow(int row, boolean on) {
        LinearLayout r = rowViews.get(row);
        r.setTranslationZ(on ? 1 : 0); // the selected show's glow draws over the rows around it
        View chan = r.getChildAt(0);
        chan.setBackground(on ? Ui.glass(host, Ui.CARD_FOCUS, Ui.dp(host, 12), true)
                : Ui.glass(host, Ui.CARD, Ui.dp(host, 12), false));
    }

    private void highlight() {
        if (selCell != null) selCell.setBackground(cellBackground(false));
        selCell = selShow != null ? cellOf.get(selShow) : null;
        if (selCell != null) {
            selCell.setBackground(cellBackground(true));
            selCell.bringToFront();
        }
        if (sel >= 0 && sel < rowViews.size()) markRow(sel, true);
    }

    private void scrollToRow(int row, boolean animate) {
        int step = rowH + rowGap;
        int top = rowsBox.getPaddingTop() + row * step;
        int view = scroller.getHeight();
        if (view <= 0) return;
        int y = scroller.getScrollY();
        int margin = step; // keep a row visible above and below the selection
        int target = y;
        if (top - margin < y) target = top - margin;
        else if (top + rowH + margin > y + view) target = top + rowH + margin - view;
        target = Math.max(0, target);
        if (target == y) return;
        if (animate) scroller.smoothScrollTo(0, target); else scroller.scrollTo(0, target);
    }

    private void moveTime(int dir) {
        List<Channel.Airing> shows = rows.get(sel);
        int i = shows.indexOf(selShow);
        int j = i + dir;
        if (j < 0 || j >= shows.size()) return;
        Channel.Airing next = shows.get(j);
        if (next.end <= minStart()) return;
        anchor = Math.max(next.start, minStart());
        select(sel, next, true);
    }

    private void moveChannel(int delta) {
        int row = Math.max(0, Math.min(rows.size() - 1, sel + delta));
        if (row == sel) return;
        long t = Math.max(anchor, winStart);
        select(row, showAt(row, t), true);
    }

    private void jump(long sec) {
        long t = Math.max(minStart(), Math.max(anchor, winStart) + sec);
        Channel.Airing show = showAt(sel, t);
        anchor = t;
        winStart = Math.max(minStart(), floorSlot(t));
        drawWindow();
        select(sel, show, true);
    }

    // ---- Details ----

    private void showDetails() {
        if (sel < 0 || selShow == null) return;
        Channel ch = channels.get(sel);
        Channel.Airing a = selShow;
        long now = nowSec();
        boolean none = noGuide.contains(a);
        boolean onNow = none || (a.start <= now && now < a.end);
        boolean reminder = !none && Reminders.has(host, ch.number, a.start);
        dTitle.setText((reminder ? "🔔  " : "") + a.title);
        StringBuilder meta = new StringBuilder(ch.number + "  " + ch.displayName());
        if (!none) meta.append("  ·  ").append(day(a.start)).append(" ").append(time(host, a.start))
                .append(" – ").append(time(host, a.end));
        if (!none && !a.episodeNumber.isEmpty()) meta.append("  ·  ").append(a.episodeNumber);
        if (!none && !a.episodeTitle.isEmpty()) meta.append("  ·  “").append(a.episodeTitle).append("”");
        dMeta.setText(meta);
        if (none) {
            dStatus.setText("");
        } else if (onNow) {
            dStatus.setText("On now  ·  " + span(a.end - now) + " left");
            dStatus.setTextColor(Ui.LIVE_RED);
        } else {
            dStatus.setText((reminder ? "Reminder set  ·  " : "") + "Starts in " + span(a.start - now));
            dStatus.setTextColor(reminder ? Ui.ACCENT : Ui.TEXT);
        }
        dDesc.setText(a.synopsis);
        dHint.setText((onNow ? "OK  Watch now" : reminder ? "OK  Cancel reminder or watch" : "OK  Remind me or watch")
                + "      ◀ ▶  Time      ▲ ▼  Channels      Rewind / Fast-forward  2 hours      CH ±  Page");
    }

    /** "2 h 15 min", "45 min". */
    private static String span(long sec) {
        long min = Math.max(1, (sec + 59) / 60);
        if (min < 60) return min + " min";
        return (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "");
    }

    private static long tzOffset(long t) {
        return java.util.TimeZone.getDefault().getOffset(t * 1000L) / 1000L;
    }

    /** "Today", "Tomorrow", or the weekday. */
    private static String day(long t) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(t * 1000L);
        int days = (int) ((startOfDay(then) - startOfDay(now)) / 86_400_000L);
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        return new java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(then.getTime());
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** A show time, "8:30 PM" or "20:30" per the clock setting. */
    static String time(Context c, long t) {
        boolean h24 = new Prefs(c).clock24h();
        return new java.text.SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(new java.util.Date(t * 1000L));
    }

    // ---- Keys ----

    /** Remote keys while the grid is shown; false for keys it doesn't use. */
    public boolean handleKey(KeyEvent e) {
        if (sel < 0 || rows.isEmpty()) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) moveTime(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) moveTime(1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) moveChannel(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) moveChannel(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP: if (down) moveChannel(-pageRows()); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN: if (down) moveChannel(pageRows()); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: if (down) jump(JUMP_SEC); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: if (down) jump(-JUMP_SEC); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Press and release: watch / remind. Hold: favorites (IPTV channels).
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                    else if (okDown && (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) { okDown = false; holdOk(); }
                } else if (okDown) {
                    okDown = false;
                    choose();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean okDown;

    /** Hold OK on an IPTV channel: Add to / Remove from favorites (they sort to the top of the IPTV channels). */
    private void holdOk() {
        Channel ch = channels.get(sel);
        if (!ch.iptv) { choose(); return; }
        Channel.Airing a = selShow;
        long now = nowSec();
        boolean later = a != null && !noGuide.contains(a) && a.start > now;
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (later) {
            // A show that hasn't started: Remind me / Watch now, as with a short press.
            boolean reminder = Reminders.has(host, ch.number, a.start);
            labels.add(reminder ? "Cancel reminder" : "Remind me");
            acts.add(() -> toggleReminder(ch, a));
            labels.add("Watch " + ch.number + " now");
            acts.add(() -> watch(ch));
        }
        labels.add(ch.favorite ? "Remove from favorites" : "Add to favorites");
        acts.add(() -> {
            boolean on = ChannelStore.get().toggleFavorite(host, ch);
            android.widget.Toast.makeText(host, ch.number + (on ? " added to favorites" : " removed from favorites"),
                    android.widget.Toast.LENGTH_SHORT).show();
            rebuild(true, new ArrayList<>(channels)); // same order: just the ★ changes
        });
        View anchorView = selCell != null ? selCell : rowViews.get(sel);
        AppMenu.show(host, anchorView, labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    private int pageRows() {
        return Math.max(1, scroller.getHeight() / (rowH + rowGap) - 1);
    }

    /** OK: watch a show that's on now; for a later show, a menu with Remind me and Watch. */
    private void choose() {
        Channel ch = channels.get(sel);
        Channel.Airing a = selShow;
        long now = nowSec();
        if (noGuide.contains(a) || (a.start <= now && now < a.end)) { watch(ch); return; }
        boolean reminder = Reminders.has(host, ch.number, a.start);
        View anchorView = selCell != null ? selCell : rowViews.get(sel);
        AppMenu.show(host, anchorView,
                new String[]{reminder ? "Cancel reminder" : "Remind me", "Watch " + ch.number + " now"},
                new Runnable[]{() -> toggleReminder(ch, a), () -> watch(ch)});
    }

    private void toggleReminder(Channel ch, Channel.Airing a) {
        boolean reminder = Reminders.has(host, ch.number, a.start);
        if (reminder) Reminders.remove(host, ch.number, a.start);
        else Reminders.add(host, ch, a);
        android.widget.Toast.makeText(host, reminder ? "Reminder cancelled"
                : "We'll remind you a minute before " + a.title + " starts", android.widget.Toast.LENGTH_SHORT).show();
        loadReminders();
        drawStrip(sel);
        highlight();
        showDetails();
    }

    private void watch(Channel ch) {
        cb.onWatch(ch);
    }
}
