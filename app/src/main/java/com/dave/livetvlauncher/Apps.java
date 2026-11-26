package com.dave.livetvlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Launching other apps and playing channels. */
public final class Apps {
    public static final String HDHR = "com.silicondust.view";
    public static final String PLEX = "com.plexapp.android";

    private Apps() {}

    public static final class AppEntry {
        public String pkg;
        public String label;
        public Drawable banner; // TV banner if the app has one, else its icon
        public boolean isBanner;
    }

    public static boolean installed(Context c, String pkg) {
        return launchIntent(c, pkg) != null;
    }

    private static Intent launchIntent(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    public static void launch(Activity a, String pkg) {
        Intent i = launchIntent(a, pkg);
        if (i == null) {
            Toast.makeText(a, "That app isn't installed or isn't allowed in this profile.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    public static void openSystemSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "System Settings isn't available in this profile.", Toast.LENGTH_LONG).show();
        }
    }

    /** Every launchable app in the current profile (a restricted profile only sees its allowed apps). */
    public static List<AppEntry> list(Context c) {
        PackageManager pm = c.getPackageManager();
        List<AppEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(c.getPackageName());
        addAll(pm, out, seen, Intent.CATEGORY_LEANBACK_LAUNCHER);
        addAll(pm, out, seen, Intent.CATEGORY_LAUNCHER);
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }

    private static void addAll(PackageManager pm, List<AppEntry> out, Set<String> seen, String category) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = String.valueOf(ri.loadLabel(pm));
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            e.isBanner = banner != null;
            e.banner = banner != null ? banner : ri.loadIcon(pm);
            out.add(e);
        }
    }

    /** Play a channel the way Settings says to. Channel list is passed so CH+/CH- work in the player. */
    public static void play(Activity a, Channel ch, List<Channel> lineup) {
        play(a, ch, lineup, 0);
    }

    /** As above; extraFlags are added to the built-in player's intent (e.g. to replace a player already open). */
    public static void play(Activity a, Channel ch, List<Channel> lineup, int extraFlags) {
        Prefs p = new Prefs(a);
        p.setLastChannel(ch.number);
        switch (p.player()) {
            case Prefs.PLAYER_HDHR_APP:
                // The HDHomeRun app has no "tune to channel" link, so this opens it on its own guide.
                launch(a, HDHR);
                return;
            case Prefs.PLAYER_EXTERNAL:
                try {
                    Intent v = new Intent(Intent.ACTION_VIEW);
                    v.setDataAndType(Uri.parse(ch.streamUrl), IptvClient.isDirect(ch.streamUrl) && ch.streamUrl.contains(".m3u8")
                            ? "application/x-mpegURL" : "video/mp2t");
                    v.putExtra("title", ch.number + " " + ch.name);
                    a.startActivity(v);
                    return;
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(a, "No video player app is allowed in this profile. Using the built-in player.",
                            Toast.LENGTH_LONG).show();
                }
                // fall through to built-in
            default:
                a.startActivity(PlayerActivity.intent(a, lineup, ch).addFlags(extraFlags));
        }
    }

    /** Focusable app tile: TV banner if the app has one, else icon + label. */
    public static android.view.View tile(Activity a, AppEntry e) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(a);
        Ui.styleImageFocusable(f, 1.1f);
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageDrawable(e.banner);
        iv.setScaleType(e.isBanner ? android.widget.ImageView.ScaleType.CENTER_CROP
                : android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = e.isBanner ? 0 : Ui.dp(a, 14);
        iv.setPadding(pad, pad, pad, e.isBanner ? 0 : Ui.dp(a, 24));
        Ui.roundCorners(iv, Ui.dp(a, 16));
        f.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        if (!e.isBanner) {
            android.widget.TextView l = Ui.text(a, 11, Ui.TEXT, false);
            l.setText(e.label);
            l.setGravity(android.view.Gravity.CENTER);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    -1, -2, android.view.Gravity.BOTTOM);
            lp.bottomMargin = Ui.dp(a, 6);
            f.addView(l, lp);
        }
        f.setContentDescription(e.label);
        f.setOnClickListener(v -> launch(a, e.pkg));
        // Hold OK: a menu to add the app to (or remove it from) the Streaming row.
        f.setOnLongClickListener(v -> {
            boolean pinned = new Prefs(a).pinnedApps().contains(e.pkg);
            AppMenu.show(a, f, new String[]{pinned ? "Remove from Streaming row" : "Add to Streaming row"},
                    new Runnable[]{() -> {
                        boolean now = new Prefs(a).togglePin(e.pkg);
                        Toast.makeText(a, e.label + (now ? " added to" : " removed from") + " the Streaming row",
                                Toast.LENGTH_SHORT).show();
                    }});
            return true;
        });
        return f;
    }

    /** One app's entry (label + TV banner), or null if it isn't installed/allowed in this profile. */
    public static AppEntry entry(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = launchIntent(c, pkg);
        if (i == null) return null;
        ResolveInfo ri = pm.resolveActivity(i, 0);
        if (ri == null) return null;
        AppEntry e = new AppEntry();
        e.pkg = pkg;
        e.label = String.valueOf(ri.loadLabel(pm));
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
        e.isBanner = banner != null;
        e.banner = banner != null ? banner : ri.loadIcon(pm);
        return e;
    }

    /** Pinned streaming apps that are actually available in this profile, in pinned order. */
    public static List<AppEntry> pinned(Context c) {
        List<AppEntry> out = new ArrayList<>();
        for (String pkg : new Prefs(c).pinnedApps()) {
            AppEntry e = entry(c, pkg);
            if (e != null) out.add(e);
        }
        return out;
    }

}
