package com.stemnotes;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Typeface;

/** Couleurs et petits utilitaires de style (thème chaud : crème, jaune, olive, vert profond). */
final class Ui {

    static final int CREAM = 0xFFFFF8E6;
    static final int YELLOW = 0xFFF7D66B;
    static final int YELLOW_SOFT = 0xFFFBE9A8;
    static final int YELLOW_PILL = 0xFFFFE28A;
    static final int YELLOW_BORDER = 0xFFF1CB57;
    static final int OLIVE = 0xFFA6B04A;
    static final int OLIVE_DARK = 0xFF7F8C2E;
    static final int GREEN_DARK = 0xFF2F6B4F;
    static final int ORANGE = 0xFFE9772E;
    static final int INK = 0xFF262626;
    static final int MUTED = 0xFF7A7466;
    static final int WHITE = 0xFFFFFFFF;
    static final int GREEN_OK = 0xFF4CAF50;
    static final int RED = 0xFFD93A3A;
    static final int LINE = 0xFFE9E1CC;
    static final int TRACK = 0xFFF1EADA;

    static float density = 3f;

    private Ui() {}

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static GradientDrawable box(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    static GradientDrawable oval(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Paramètres de layout : w/h en pixels ou constantes, marges en dp. */
    static LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /** Léger retour visuel au toucher. */
    static void pressable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View x, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) x.setAlpha(0.75f);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) x.setAlpha(1f);
                return false;
            }
        });
    }

    /** Petit effet « pop » quand une étape se termine. */
    static void pop(View v) {
        v.setScaleX(0f);
        v.setScaleY(0f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }
}
