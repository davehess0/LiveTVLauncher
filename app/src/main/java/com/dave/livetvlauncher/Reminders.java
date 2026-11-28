package com.dave.livetvlauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Remind me" for shows in the Full Guide. Each reminder is kept on this device and fires an alarm
 * a minute before the show starts, which pops up {@link ReminderActivity} (Watch / Dismiss) on top
 * of whatever is on screen. If Android ever blocks that, the reminder shows when you get back to the
 * home screen instead.
 */
final class Reminders {
    private Reminders() {}

    /** How long before the start the reminder pops up. */
    static final long LEAD_SEC = 60;
    /** A reminder that couldn't pop up on time still shows if you get home within this long after the start. */
    static final long LATE_SEC = 15 * 60;

    static final class Reminder {
        String channel = "", channelName = "", streamUrl = "";
        long start, end; // unix seconds
        String title = "", episode = "";
        boolean shown;

        String key() { return key(channel, start); }
        static String key(String channel, long start) { return channel + "@" + start; }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("ch", channel).put("name", channelName).put("url", streamUrl)
                    .put("start", start).put("end", end).put("title", title).put("ep", episode).put("shown", shown);
        }

        static Reminder from(JSONObject o) {
            Reminder r = new Reminder();
            r.channel = o.optString("ch");
            r.channelName = o.optString("name");
            r.streamUrl = o.optString("url");
            r.start = o.optLong("start");
            r.end = o.optLong("end");
            r.title = o.optString("title");
            r.episode = o.optString("ep");
            r.shown = o.optBoolean("shown");
            return r;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("reminders", Context.MODE_PRIVATE);
    }

    private static long nowSec() { return System.currentTimeMillis() / 1000L; }

    /** Reminders for shows that haven't ended yet, soonest first. */
    static synchronized List<Reminder> all(Context c) {
        List<Reminder> out = new ArrayList<>();
        long now = nowSec();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Reminder r = Reminder.from(a.getJSONObject(i));
                if (r.end > now) out.add(r);
            }
        } catch (Exception ignored) { }
        out.sort((x, y) -> Long.compare(x.start, y.start));
        return out;
    }

    private static synchronized void save(Context c, List<Reminder> list) {
        JSONArray a = new JSONArray();
        try { for (Reminder r : list) a.put(r.toJson()); } catch (Exception ignored) { }
        sp(c).edit().putString("list", a.toString()).apply();
    }

    static boolean has(Context c, String channel, long start) {
        for (Reminder r : all(c)) if (r.channel.equals(channel) && r.start == start) return true;
        return false;
    }

    static void add(Context c, Channel ch, Channel.Airing a) {
        List<Reminder> list = all(c);
        for (Reminder r : list) if (r.channel.equals(ch.number) && r.start == a.start) return;
        Reminder r = new Reminder();
        r.channel = ch.number;
        r.channelName = ch.displayName();
        r.streamUrl = ch.streamUrl;
        r.start = a.start;
        r.end = a.end;
        r.title = a.title;
        r.episode = a.episodeTitle;
        list.add(r);
        save(c, list);
        schedule(c, r);
        AppLog.i("Reminders", "Remind me: " + r.title + " on " + r.channel + " at " + r.start);
    }

    static void remove(Context c, String channel, long start) {
        List<Reminder> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            Reminder r = list.get(i);
            if (r.channel.equals(channel) && r.start == start) {
                list.remove(i);
                cancel(c, r);
                AppLog.i("Reminders", "Reminder cancelled: " + r.title + " on " + r.channel);
            }
        }
        save(c, list);
    }

    static Reminder find(Context c, String key) {
        for (Reminder r : all(c)) if (r.key().equals(key)) return r;
        return null;
    }

    static void markShown(Context c, String key) {
        List<Reminder> list = all(c);
        for (Reminder r : list) if (r.key().equals(key)) r.shown = true;
        save(c, list);
    }

    /** After a restart (or an app update) alarms are gone: set them again. Also drops ended reminders. */
    static void scheduleAll(Context c) {
        List<Reminder> list = all(c);
        save(c, list); // prunes ended ones
        // Not for shows that started long ago (e.g. the app restarted mid-show): those would pop up at once.
        long now = nowSec();
        for (Reminder r : list) if (!r.shown && now < Math.min(r.end, r.start + LATE_SEC)) schedule(c, r);
    }

    /**
     * Reminders that should be on screen now but haven't been (the alarm couldn't open the popup).
     * Called when the home screen comes back.
     */
    static List<Reminder> missed(Context c) {
        List<Reminder> out = new ArrayList<>();
        long now = nowSec();
        for (Reminder r : all(c))
            if (!r.shown && now >= r.start - LEAD_SEC && now < Math.min(r.end, r.start + LATE_SEC)) out.add(r);
        return out;
    }

    /**
     * The alarm opens the pop-up itself. Android lets an exact alarm start its own screen even while
     * another app is in front; a receiver asking to open it afterwards is blocked on newer versions.
     */
    private static PendingIntent alarmIntent(Context c, Reminder r) {
        return PendingIntent.getActivity(c, r.key().hashCode(), ReminderActivity.intent(c, r.key()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context c, Reminder r) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = Math.max(System.currentTimeMillis() + 1000, (r.start - LEAD_SEC) * 1000L);
        PendingIntent pi = alarmIntent(c, r);
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
        }
    }

    private static void cancel(Context c, Reminder r) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarmIntent(c, r));
    }
}
