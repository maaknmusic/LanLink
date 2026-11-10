package com.lanlink.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building screens in code (the app has no XML layouts). */
final class Ui {
    private Ui() {}

    static final int BLUE = 0xFF2563EB, TEXT = 0xFF1F2937, MUTED = 0xFF6B7280, OK = 0xFF15803D, WARN = 0xFFB45309;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static LinearLayout card(Context c, String title) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(c, 14));
        bg.setStroke(dp(c, 1), 0xFFE5E7EB);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 14));
        box.setLayoutParams(lp);
        TextView t = text(c, title, 18, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        return box;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return t;
    }

    static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    static Button primary(Context c, String label, View.OnClickListener l) {
        Button b = button(c, label, l);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BLUE);
        bg.setCornerRadius(dp(c, 10));
        b.setBackground(bg);
        return b;
    }

    static LinearLayout row(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, dp(c, 4), dp(c, 6), dp(c, 4));
            r.addView(v, lp);
        }
        return r;
    }
}
