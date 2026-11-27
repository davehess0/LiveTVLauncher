package com.dave.livetvlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Which app the Home button opens. Android only lets the system pick another app as the home screen,
 * so to hand Home back, Live TV Home switches off its own home-screen entry (the ".HomeAlias" in the
 * manifest) and opens the launcher you chose; Android then falls back to it, or to the original
 * launcher. Live TV Home stays installed and in the app row, so it can always be switched back on.
 */
final class HomeApps {
    private HomeApps() {}

    static final class Entry {
        final String pkg, cls, label;
        final boolean ours;
        Entry(String pkg, String cls, String label, boolean ours) {
            this.pkg = pkg; this.cls = cls; this.label = label; this.ours = ours;
        }
    }

    private static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    private static ComponentName alias(Context c) {
        return new ComponentName(c, c.getPackageName() + ".HomeAlias");
    }

    /** Whether Live TV Home is offered as a home screen at all (switched off after choosing another). */
    static boolean oursEnabled(Context c) {
        int s = c.getPackageManager().getComponentEnabledSetting(alias(c));
        return s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    static void setOursEnabled(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(alias(c),
                on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        AppLog.i("Home", "Live TV Home as a home screen: " + (on ? "on" : "off"));
    }

    /** Every installed home screen (launcher), Live TV Home first. */
    static List<Entry> list(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(c.getPackageName(), alias(c).getClassName(), "Live TV Home", true));
        for (ResolveInfo ri : pm.queryIntentActivities(homeIntent(), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            // The system's "which app?" chooser and Settings' fallback home aren't launchers.
            if (pkg.equals("android") || pkg.equals("com.android.settings")) continue;
            out.add(new Entry(pkg, ri.activityInfo.name, String.valueOf(ri.loadLabel(pm)), false));
        }
        return out;
    }

    /** The package the Home button opens now, or null if Android would ask. */
    static String currentPackage(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        String pkg = ri.activityInfo.packageName;
        return pkg.equals("android") ? null : pkg; // "android" = the chooser
    }

    /** "Live TV Home", another launcher's name, or "Not set". */
    static String currentLabel(Context c) {
        String pkg = currentPackage(c);
        if (pkg == null) return "Not set";
        for (Entry e : list(c)) if (e.pkg.equals(pkg)) return e.label;
        return pkg;
    }

    /** Hand Home to another launcher: switch our home entry off, then open the chosen one. */
    static void switchTo(Activity a, Entry e) {
        AppLog.i("Home", "Switching the home screen to " + e.label + " (" + e.pkg + ")");
        setOursEnabled(a, false);
        try {
            a.startActivity(homeIntent().setClassName(e.pkg, e.cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(a, "Home is now " + e.label + ". If Android asks which app to use for Home, pick "
                    + e.label + " and choose Always. Live TV Home is still in your apps.", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(a, "Couldn't open " + e.label + ". Press Home to choose a home screen.", Toast.LENGTH_LONG).show();
        }
    }

    /** Make Live TV Home the home screen again (Android shows its own confirmation). */
    static void useOurs(Activity a) {
        setOursEnabled(a, true);
        requestHome(a);
    }

    /** Ask Android to make this the home app (system dialog on Android 10+), or open Home settings. */
    static void requestHome(Activity a) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.app.role.RoleManager rm = (android.app.role.RoleManager) a.getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)
                        && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    // Must be "for result": Android's prompt needs to know which app is asking, or it
                    // silently cancels.
                    a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 4242);
                    return;
                }
            } catch (Exception ignored) {
                // Fall back to the settings screen below.
            }
        }
        try {
            a.startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(a, "Press Home and choose Live TV Home (Always). A restricted profile can't change "
                    + "this; use the main profile.", Toast.LENGTH_LONG).show();
        }
    }
}
