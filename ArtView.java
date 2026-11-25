package com.stemnotes;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Illustration originale : disque + barres de forme d'onde. Les barres dansent quand une tâche tourne. */
final class ArtView extends View {

    private static final float[] BASE = {0.35f, 0.6f, 0.9f, 0.5f, 1.0f, 0.65f, 0.4f};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int disc;
    private final int bars;
    private float phase = 0f;
    private boolean active = false;
    private ValueAnimator anim;

    ArtView(Context c, int discColor, int barColor) {
        super(c);
        disc = discColor;
        bars = barColor;
    }

    void setActive(boolean a) {
        if (a == active) return;
        active = a;
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        if (a) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(900);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator x) {
                    phase = (Float) x.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        } else {
            phase = 0f;
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h * 0.5f;
        float radius = Math.min(w, h) * 0.46f;

        // sol
        p.setColor(0x22000000);
        r.set(w * 0.08f, h * 0.86f, w * 0.92f, h * 0.99f);
        canvas.drawOval(r, p);

        // disque
        p.setColor(disc);
        canvas.drawCircle(cx, cy, radius, p);
        p.setColor(0x33FFFFFF);
        canvas.drawCircle(cx, cy, radius * 0.84f, p);

        // barres
        int n = BASE.length;
        float bw = radius * 0.17f;
        float gap = radius * 0.06f;
        float total = n * bw + (n - 1) * gap;
        float x0 = cx - total / 2f;
        p.setColor(bars);
        for (int i = 0; i < n; i++) {
            float k = active
                    ? 0.65f + 0.35f * (float) Math.sin(phase * 2 * Math.PI + i * 0.9)
                    : 1f;
            float bh = radius * 1.05f * BASE[i] * k;
            float left = x0 + i * (bw + gap);
            r.set(left, cy - bh / 2f, left + bw, cy + bh / 2f);
            canvas.drawRoundRect(r, bw / 2f, bw / 2f, p);
        }

        // petite note en haut à droite
        float nx = w * 0.84f;
        float ny = h * 0.2f;
        float nr = radius * 0.11f;
        canvas.drawCircle(nx - nr * 1.6f, ny + nr * 2.2f, nr, p);
        r.set(nx - nr * 0.7f, ny - nr * 2f, nx - nr * 0.1f, ny + nr * 2.2f);
        canvas.drawRect(r, p);
        r.set(nx - nr * 0.1f, ny - nr * 2f, nx + nr * 1.4f, ny - nr * 1f);
        canvas.drawRect(r, p);
    }
}
