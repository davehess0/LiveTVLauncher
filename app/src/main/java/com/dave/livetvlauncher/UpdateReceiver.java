package com.dave.livetvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Android's answer to an update install: show its confirmation screen, or explain a failure. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    try {
                        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        AppLog.w("Update", "Couldn't show the install screen (" + e.getClass().getSimpleName() + ")");
                    }
                }
                return;
            }
            case PackageInstaller.STATUS_SUCCESS:
                // Normally never seen: Android stops this app to replace it, then starts the new one.
                AppLog.i("Update", "Installed");
                return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                AppLog.i("Update", "Install cancelled");
                Toast.makeText(ctx, "Update cancelled", Toast.LENGTH_SHORT).show();
                return;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                show(ctx, "Update failed: that APK is signed with a different key than the installed app, "
                        + "or doesn't fit this device.", msg);
                return;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                show(ctx, "Update failed: not enough free space.", msg);
                return;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                show(ctx, "Update blocked: allow Live TV Home to install apps (Android settings → Security → Unknown sources).", msg);
                return;
            default:
                show(ctx, "Update failed" + (msg != null ? ": " + msg : "."), msg);
        }
    }

    private static void show(Context ctx, String text, String detail) {
        AppLog.w("Update", text + (detail != null ? " [" + detail + "]" : ""));
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
    }
}
