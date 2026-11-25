package com.stemnotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Icônes vectorielles dessinées à la main (pas de ressources). */
final class IconView extends View {

    static final int HOME = 0;
    static final int BARS = 1;
    static final int HELP = 2;
    static final int USER = 3;
    static final int NOTE = 4;
    static final int LOCK = 5;
    static final int CHEVRON = 6;
    static final int ARROW = 7;
    static final int CHECK = 8;
    static final int PLUS = 9;
    static final int FLAME = 10;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int type;
    private int color;

    IconView(Context c, int type, int color) {
        super(c);
        this.type = type;
        this.color = color;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int c) {
        color = c;
        invalidate();
    }

    private void stroke(Canvas canvas) {
        p.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, p);
        path.reset();
    }

    private void fill(Canvas canvas) {
        p.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, p);
        path.reset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        canvas.save();
        canvas.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        canvas.scale(s, s);
        p.setColor(color);
        p.setStrokeWidth(2f);
        path.reset();

        switch (type) {
            case HOME:
                path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11);
                stroke(canvas);
                path.moveTo(5, 9.5f); path.lineTo(5, 20); path.lineTo(19, 20); path.lineTo(19, 9.5f);
                stroke(canvas);
                path.moveTo(10, 20); path.lineTo(10, 14); path.lineTo(14, 14); path.lineTo(14, 20);
                stroke(canvas);
                break;
            case BARS:
                path.addRoundRect(4, 12, 8, 20, 1, 1, Path.Direction.CW);
                path.addRoundRect(10, 5, 14, 20, 1, 1, Path.Direction.CW);
                path.addRoundRect(16, 9, 20, 20, 1, 1, Path.Direction.CW);
                stroke(canvas);
                break;
            case HELP:
                p.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(12, 12, 9, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(13f);
                p.setTextAlign(Paint.Align.CENTER);
                p.setFakeBoldText(true);
                canvas.drawText("?", 12, 16.8f, p);
                p.setFakeBoldText(false);
                break;
            case USER:
                p.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(12, 8, 4, p);
                path.moveTo(4, 21); path.cubicTo(4, 13.5f, 20, 13.5f, 20, 21);
                stroke(canvas);
                break;
            case NOTE:
                p.setStyle(Paint.Style.FILL);
                canvas.drawCircle(8, 18, 3, p);
                path.moveTo(11, 18); path.lineTo(11, 4);
                stroke(canvas);
                path.moveTo(11, 4); path.lineTo(18, 6.5f); path.lineTo(18, 10.5f); path.lineTo(11, 8);
                path.close();
                fill(canvas);
                break;
            case LOCK:
                path.addRoundRect(5, 11, 19, 21, 2, 2, Path.Direction.CW);
                stroke(canvas);
                path.moveTo(8, 11); path.lineTo(8, 8); path.cubicTo(8, 2.5f, 16, 2.5f, 16, 8); path.lineTo(16, 11);
                stroke(canvas);
                break;
            case CHEVRON:
                path.moveTo(6, 9); path.lineTo(12, 15); path.lineTo(18, 9);
                stroke(canvas);
                break;
            case ARROW:
                path.moveTo(5, 12); path.lineTo(19, 12);
                path.moveTo(13, 6); path.lineTo(19, 12); path.lineTo(13, 18);
                stroke(canvas);
                break;
            case CHECK:
                path.moveTo(5, 12.5f); path.lineTo(10, 17.5f); path.lineTo(19, 7);
                stroke(canvas);
                break;
            case PLUS:
                path.moveTo(12, 5); path.lineTo(12, 19);
                path.moveTo(5, 12); path.lineTo(19, 12);
                stroke(canvas);
                break;
            case FLAME:
                path.moveTo(12, 2);
                path.cubicTo(13, 6, 18, 8, 18, 15);
                path.cubicTo(18, 19, 15, 22, 12, 22);
                path.cubicTo(9, 22, 6, 19, 6, 15);
                path.cubicTo(6, 12, 8, 10, 9, 7);
                path.cubicTo(10.5f, 9, 11, 8.5f, 12, 2);
                path.close();
                fill(canvas);
                break;
            default:
                break;
        }
        canvas.restore();
    }
}
