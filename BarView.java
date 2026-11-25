package com.stemnotes;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Barre de progression arrondie, animée. */
final class BarView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int track;
    private final int fill;
    private float shown = 0f;
    private ValueAnimator anim;

    BarView(Context c, int trackColor, int fillColor) {
        super(c);
        track = trackColor;
        fill = fillColor;
    }

    void setProgress(float v, boolean animate) {
        final float target = Math.max(0f, Math.min(1f, v));
        if (anim != null) anim.cancel();
        if (!animate) {
            shown = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(350);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
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
        r.set(0, 0, w, h);
        p.setColor(track);
        canvas.drawRoundRect(r, h / 2f, h / 2f, p);
        if (shown > 0f) {
            r.set(0, 0, Math.max(h, w * shown), h);
            p.setColor(fill);
            canvas.drawRoundRect(r, h / 2f, h / 2f, p);
        }
    }
}
