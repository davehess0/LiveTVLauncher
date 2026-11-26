package com.dave.livetvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The reminder pop-up: a card in the bottom corner over whatever is on screen, with Watch and
 * Dismiss. Several reminders due at once stack in the same card. Closes itself after a while.
 */
public final class ReminderActivity extends Activity {
    private static final String X_KEY = "key";
    private static final long AUTO_CLOSE_MS = 2 * 60_000L;

    static Intent intent(Context c, String key) {
        return new Intent(c, ReminderActivity.class).putExtra(X_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> keys = new ArrayList<>();
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        int m = Ui.dp(this, 40);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 460), -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(m, m, m, m);
        root.addView(list, lp);
        setContentView(root);
        add(getIntent());
        handler.postDelayed(this::finish, AUTO_CLOSE_MS);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        add(i);
    }

    private void add(Intent i) {
        String key = i.getStringExtra(X_KEY);
        Reminders.Reminder r = key != null ? Reminders.find(this, key) : null;
        if (r == null || r.shown || keys.contains(key)) { // cancelled, or already shown / dismissed
            if (keys.isEmpty()) finish();
            return;
        }
        keys.add(key);
        Reminders.markShown(this, key);
        AppLog.i("Reminders", "Showing the reminder for " + r.title + " on " + r.channel);
        list.addView(card(r), 0);
        list.getChildAt(0).findViewWithTag("watch").requestFocus();
    }

    private View card(Reminders.Reminder r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        int p = Ui.dp(this, 22);
        card.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(Ui.BG | 0xFF000000); // solid, so what's behind doesn't show through
        fill.setCornerRadius(Ui.dp(this, 24));
        card.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                fill, Ui.glass(this, Ui.CARD, Ui.dp(this, 24), false)}));
        card.setElevation(Ui.dp(this, 12));

        long now = System.currentTimeMillis() / 1000L;
        TextView when = Ui.text(this, 13, Ui.ACCENT, true);
        long mins = (r.start - now + 30) / 60;
        when.setText("🔔  " + (mins >= 1 ? "Starts in " + mins + " min" : now < r.start + 120 ? "Starting now"
                : "Started " + ((now - r.start) / 60) + " min ago"));
        card.addView(when);
        TextView title = Ui.text(this, 24, Ui.TEXT, true);
        title.setText(r.title);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(title);
        TextView meta = Ui.text(this, 15, Ui.SUB, false);
        meta.setText(r.channel + "  " + r.channelName + "  ·  " + GridActivity.time(this, r.start)
                + (r.episode.isEmpty() ? "" : "  ·  " + r.episode));
        meta.setMaxLines(2);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        card.addView(meta);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        TextView watch = button("Watch " + r.channel), dismiss = button("Dismiss");
        watch.setTag("watch");
        watch.setOnClickListener(v -> watch(r));
        dismiss.setOnClickListener(v -> dismiss(card, r.key()));
        buttons.addView(watch, Ui.margins(Ui.lp(-2, -2), 0, 0, Ui.dp(this, 14), 0));
        buttons.addView(dismiss, Ui.lp(-2, -2));
        card.addView(buttons);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView button(String label) {
        TextView t = Ui.text(this, 17, Ui.TEXT, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12));
        Ui.styleFocusable(t, 1.05f);
        return t;
    }

    private void watch(Reminders.Reminder r) {
        AppLog.i("Reminders", "Watch " + r.channel + " from the reminder");
        Prefs prefs = new Prefs(this);
        List<Channel> lineup = ChannelStore.get().visible(prefs);
        Channel ch = ChannelStore.get().byNumber(r.channel);
        if (ch == null) {
            // Channels not loaded yet (the app was asleep): play from what the reminder remembered.
            ch = new Channel();
            ch.number = r.channel;
            ch.name = r.channelName;
            ch.streamUrl = r.streamUrl;
            lineup = java.util.Collections.singletonList(ch);
        } else if (!lineup.contains(ch)) {
            lineup = ChannelStore.get().all();
        }
        // Into the app's main task, replacing a player that's already open (e.g. live TV on another
        // channel), so Back goes home rather than to the old channel.
        Apps.play(this, ch, lineup, Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        finish();
    }

    private void dismiss(View card, String key) {
        keys.remove(key);
        list.removeView(card);
        if (list.getChildCount() == 0) finish();
        else list.getChildAt(0).findViewWithTag("watch").requestFocus();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(keyCode, e);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
