package com.dave.livetvlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** The Full Guide full screen (from the home screen). The player shows the same grid over live video. */
public final class GridActivity extends Activity {
    private GuideGrid grid;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(Ui.pageBackground());
        grid = new GuideGrid(this, false, ch -> Apps.play(this, ch, ChannelStore.get().visible(new Prefs(this))));
        setContentView(grid);
    }

    @Override protected void onStart() {
        super.onStart();
        grid.start();
    }

    @Override protected void onStop() {
        grid.stop();
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return grid.handleKey(e) || super.dispatchKeyEvent(e);
    }
}
