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
    private static final int COLUMNS = 5;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        int p = Ui.dp(this, 48);
        root.setPadding(p, p / 2, p, p);

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
                root.addView(row, Ui.margins(Ui.lp(-1, Ui.dp(this, 110)), 0, 0, 0, Ui.dp(this, 20)));
            }
            View t = Apps.tile(this, apps.get(i));
            if (first == null) first = t;
            row.addView(t, Ui.margins(Ui.lpWeight(0, -1, 1), 0, 0, Ui.dp(this, 20), 0));
        }
        if (row != null) {
            for (int i = apps.size() % COLUMNS; i != 0 && i < COLUMNS; i++) {
                row.addView(new View(this), Ui.margins(Ui.lpWeight(0, 1, 1), 0, 0, Ui.dp(this, 20), 0));
            }
        }
        sv.addView(root);
        setContentView(sv);
        if (first != null) first.requestFocus();
    }
}
