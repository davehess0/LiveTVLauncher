package com.dave.livetvlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Grid of every app allowed in the current profile. */
public final class AppsActivity extends Activity {
    private static final int COLUMNS = 4;
    private static final int GAP_DP = 24;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(Ui.pageBackground());
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false); // tiles at the edges grow into the margin instead of being cut off
        int side = Ui.dp(this, Ui.SIDE_DP), p = Ui.dp(this, 48);
        root.setPadding(side, p / 2, side, p);
        // Tiles are 16:9, the shape of Android TV app banners, so logos show whole instead of cropped.
        int gap = Ui.dp(this, GAP_DP);
        int tileW = (getResources().getDisplayMetrics().widthPixels - 2 * side - (COLUMNS - 1) * gap) / COLUMNS;
        int tileH = tileW * 9 / 16;

        TextView title = Ui.text(this, 26, Ui.TEXT, true);
        title.setText("Apps");
        title.setPadding(0, 0, 0, Ui.dp(this, 6));
        root.addView(title);
        TextView hint = Ui.text(this, 14, Ui.SUB, false);
        hint.setText("Long-press OK on an app to pin it to (or remove it from) the Streaming row on your home screen.");
        hint.setPadding(0, 0, 0, Ui.dp(this, 20));
        root.addView(hint);

        List<Apps.AppEntry> apps = Apps.list(this);
        LinearLayout row = null;
        View first = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                root.addView(row, Ui.margins(Ui.lp(-1, tileH), 0, 0, 0, gap));
            }
            View t = Apps.tile(this, apps.get(i));
            if (first == null) first = t;
            row.addView(t, Ui.margins(Ui.lp(tileW, -1), 0, 0, gap, 0));
        }
        sv.addView(root);
        setContentView(sv);
        if (first != null) first.requestFocus();
    }
}
