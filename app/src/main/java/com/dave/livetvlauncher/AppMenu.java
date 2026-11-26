package com.dave.livetvlauncher;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** A small menu that pops up just below a tile (hold OK on an app). Back closes it. */
final class AppMenu {
    private AppMenu() {}

    static PopupWindow show(Activity a, View anchor, String[] labels, Runnable[] actions) {
        int pad = Ui.dp(a, 14);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(pad, pad, pad, pad);
        // Nearly opaque, so the menu stays readable over the tiles behind it; glass on top for the theme.
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor((Ui.BG & 0x00FFFFFF) | 0xF2000000);
        fill.setCornerRadius(Ui.dp(a, 22));
        box.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                fill, Ui.glass(a, Ui.CARD, Ui.dp(a, 22), false)}));

        PopupWindow pw = new PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); // Back / outside closes it
        pw.setElevation(Ui.dp(a, 12));

        View first = null;
        for (int i = 0; i < labels.length; i++) {
            TextView item = Ui.text(a, 17, Ui.TEXT, true);
            item.setText(labels[i]);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(Ui.dp(a, 22), Ui.dp(a, 12), Ui.dp(a, 22), Ui.dp(a, 12));
            item.setMinWidth(Ui.dp(a, 210));
            Ui.styleFocusable(item, 1.04f);
            final Runnable act = actions[i];
            item.setOnClickListener(v -> { pw.dismiss(); act.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) lp.topMargin = Ui.dp(a, 8);
            box.addView(item, lp);
            if (first == null) first = item;
        }

        // Just below the tile, or above it when there isn't room below (near the bottom of the screen).
        // Placed by hand: with a wrap-content height, Android squashes the menu instead of flipping it.
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = box.getMeasuredWidth(), h = box.getMeasuredHeight();
        View screen = a.getWindow().getDecorView();
        int[] at = new int[2];
        anchor.getLocationOnScreen(at); // includes the focused tile's lift / grow
        int tileH = Math.round(anchor.getHeight() * anchor.getScaleY());
        int gap = Ui.dp(a, 12), edge = Ui.dp(a, 16);
        int below = at[1] + tileH + gap;
        int y = below + h <= screen.getHeight() - edge ? below : Math.max(edge, at[1] - gap - h);
        int x = Math.max(edge, Math.min(at[0], screen.getWidth() - edge - w));
        pw.setWidth(w);
        pw.setHeight(h);
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        final View f = first;
        if (f != null) box.post(f::requestFocus);
        return pw;
    }
}
