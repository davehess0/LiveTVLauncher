package com.dave.livetvlauncher;

import android.app.Application;

/** Starts the log before any screen opens, so crashes anywhere are recorded. */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        Ui.applyTheme(new Prefs(this).theme());
        Reminders.scheduleAll(this); // alarms don't survive a restart or an app update
    }
}
