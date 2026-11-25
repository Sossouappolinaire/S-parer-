package com.stemnotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Rangée de petits points (étapes terminées en vert), comme sous le titre d'une unité. */
final class DotsView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int done = 0;
    private int count = 0;

    DotsView(Context c) {
        super(c);
    }

    void set(int done, int count) {
        this.done = done;
        this.count = count;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int d = Ui.dp(12);
        int gap = Ui.dp(5);
        setMeasuredDimension(count * d + Math.max(0, count - 1) * gap, d + Ui.dp(2));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = Ui.dp(6);
        float gap = Ui.dp(5);
        float cy = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            float cx = r + i * (2 * r + gap);
            boolean ok = i < done;
            p.setStyle(Paint.Style.FILL);
            p.setColor(ok ? Ui.GREEN_OK : Ui.WHITE);
            canvas.drawCircle(cx, cy, r - 1f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(1.5f));
            p.setColor(ok ? Ui.WHITE : Ui.LINE);
            canvas.drawCircle(cx, cy, r - 1f, p);
        }
    }
}
