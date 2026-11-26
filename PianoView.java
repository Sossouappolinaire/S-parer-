package com.stemnotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Arrays;

/**
 * Clavier de piano avec ses octaves. Un doigt se déplace au-dessus des touches, descend et
 * appuie sur la touche au moment où la note est jouée (la touche s'enfonce et prend la couleur
 * de la piste). Plusieurs notes en même temps (accords) = plusieurs doigts.
 */
final class PianoView extends View {

    private static final int[] WHITE_INDEX = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
    private static final boolean[] BLACK = {false, true, false, true, false, false, true, false, true, false, true, false};
    private static final int MAX_FINGERS = 6;
    private static final double MIN_PRESS = 0.08; // une note très courte reste visible au moins 80 ms

    private final PitchTranscriber.Note[] notes;
    private final int color;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] pressed = new int[MAX_FINGERS];
    private double maxLen;
    private double time;
    private int lo = 48;   // premier Do affiché
    private int hi = 83;   // dernier Si affiché
    private int whites = 21;

    PianoView(Context c, NoteRollView.Track tr) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        notes = tr.notes;
        color = tr.color;
        maxLen = tr.maxLen + MIN_PRESS;
        text.setTextAlign(Paint.Align.CENTER);
        computeRange();
    }

    void setTime(double sec) {
        time = sec;
        invalidate();
    }

    /** Étendue du clavier : des octaves complètes (Do à Si), au moins 3 et au plus 5. */
    private void computeRange() {
        if (notes.length == 0) return;
        int[] ps = new int[notes.length];
        for (int i = 0; i < ps.length; i++) ps[i] = notes[i].pitch;
        Arrays.sort(ps);
        int a = ps[0];
        int b = ps[ps.length - 1];
        if (b - a > 59) { // on ignore les rares notes aberrantes
            a = ps[(int) (ps.length * 0.03)];
            b = ps[(int) ((ps.length - 1) * 0.97)];
        }
        int l = (Math.max(a, 0) / 12) * 12;
        int h = (Math.max(b, 0) / 12) * 12 + 11;
        boolean up = true;
        while (h - l + 1 < 36) {
            if (up && h + 12 <= 119) h += 12;
            else if (l - 12 >= 0) l -= 12;
            else if (h + 12 <= 119) h += 12;
            else break;
            up = !up;
        }
        if (h - l + 1 > 60) h = l + 59;
        lo = l;
        hi = h;
        whites = ((hi - lo + 1) / 12) * 7;
    }

    private int whiteIdx(int p) {
        return (p / 12) * 7 + WHITE_INDEX[p % 12] - (lo / 12) * 7;
    }

    private float keyX(int p, float kw) {
        int wi = whiteIdx(p);
        return BLACK[p % 12] ? (wi + 1) * kw : (wi + 0.5f) * kw;
    }

    private int firstIndex(double start) {
        int a = 0, b = notes.length;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (notes[m].start < start) a = m + 1; else b = m;
        }
        return a;
    }

    private boolean inRange(int p) {
        return p >= lo && p <= hi;
    }

    private int collectPressed() {
        int n = 0;
        for (int i = firstIndex(time - maxLen); i < notes.length && n < MAX_FINGERS; i++) {
            PitchTranscriber.Note x = notes[i];
            if (x.start > time) break;
            double end = Math.max(x.end, x.start + MIN_PRESS);
            if (end > time && inRange(x.pitch)) pressed[n++] = x.pitch;
        }
        return n;
    }

    private static int blend(int c, int with, float f) {
        int r = (int) (((c >> 16) & 255) * (1 - f) + ((with >> 16) & 255) * f);
        int g = (int) (((c >> 8) & 255) * (1 - f) + ((with >> 8) & 255) * f);
        int b = (int) ((c & 255) * (1 - f) + (with & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int alpha(int c, float a) {
        return ((int) (a * 255) << 24) | (c & 0xFFFFFF);
    }

    private static boolean has(int[] arr, int n, int p) {
        for (int i = 0; i < n; i++) if (arr[i] == p) return true;
        return false;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float handH = h * 0.36f;
        float ky = handH;
        float kh = h - ky - 1 * dp;
        float kw = w / whites;
        float bw = kw * 0.6f;
        float bh = kh * 0.62f;
        int np = collectPressed();

        // fond du clavier (visible entre les touches)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF222222);
        cv.drawRect(0, ky - 3 * dp, w, h, paint);

        // touches blanches
        text.setTextSize(8 * dp);
        for (int p = lo; p <= hi; p++) {
            if (BLACK[p % 12]) continue;
            float x0 = whiteIdx(p) * kw;
            boolean on = has(pressed, np, p);
            rect.set(x0 + 0.6f * dp, ky + (on ? 2 * dp : 0), x0 + kw - 0.6f * dp, ky + kh);
            paint.setColor(on ? blend(color, 0xFFFFFFFF, 0.25f) : 0xFFFFFFFF);
            cv.drawRoundRect(rect, 2 * dp, 2 * dp, paint);
            if (on) {
                paint.setColor(0x33000000);
                cv.drawRect(rect.left, rect.top, rect.right, rect.top + 5 * dp, paint);
            }
            if (p % 12 == 0) {
                text.setColor(on ? 0xFFFFFFFF : 0xFF888888);
                cv.drawText(PitchTranscriber.noteName(p), x0 + kw / 2, ky + kh - 3 * dp, text);
            }
        }

        // touches noires
        for (int p = lo; p <= hi; p++) {
            if (!BLACK[p % 12]) continue;
            float cx = (whiteIdx(p) + 1) * kw;
            boolean on = has(pressed, np, p);
            float bottom = ky + bh - (on ? 3 * dp : 0);
            rect.set(cx - bw / 2, ky, cx + bw / 2, bottom);
            paint.setColor(on ? color : 0xFF1A1A1A);
            cv.drawRoundRect(rect, 2 * dp, 2 * dp, paint);
            if (!on) {
                paint.setColor(0xFF3A3A3A);
                cv.drawRect(cx - bw / 2 + 1 * dp, bottom - 5 * dp, cx + bw / 2 - 1 * dp, bottom - 1 * dp, paint);
            }
        }

        // doigts : un par note qui sonne
        float tipWhite = ky + bh + (kh - bh) * 0.45f;
        float tipBlack = ky + bh * 0.5f;
        for (int i = 0; i < np; i++) {
            int p = pressed[i];
            drawFinger(cv, keyX(p, kw), BLACK[p % 12] ? tipBlack : tipWhite, 0f, 1f, kw, true);
        }

        // sinon, un doigt se déplace et se prépare au-dessus de la prochaine note
        if (np == 0) {
            int idx = firstIndex(time + 1e-9);
            PitchTranscriber.Note next = null;
            for (int i = idx; i < notes.length; i++) {
                if (inRange(notes[i].pitch)) { next = notes[i]; break; }
            }
            PitchTranscriber.Note prev = null;
            for (int i = idx - 1; i >= 0 && i > idx - 300; i--) {
                if (inRange(notes[i].pitch)) { prev = notes[i]; break; }
            }
            if (next != null) {
                float nx = keyX(next.pitch, kw);
                float nTip = BLACK[next.pitch % 12] ? tipBlack : tipWhite;
                float x = nx, tip = nTip;
                double lift = clamp((next.start - time) / 0.30);
                if (prev != null) {
                    double endP = Math.max(prev.end, prev.start + MIN_PRESS);
                    double span = Math.max(next.start - endP, 0.05);
                    double f = clamp((time - endP) / (span * 0.7));
                    f = f * f * (3 - 2 * f);
                    float px = keyX(prev.pitch, kw);
                    float pTip = BLACK[prev.pitch % 12] ? tipBlack : tipWhite;
                    x = (float) (px + (nx - px) * f);
                    tip = (float) (pTip + (nTip - pTip) * f);
                    lift *= clamp((time - endP) / 0.12); // la main se relève doucement
                }
                drawFinger(cv, x, tip, (float) lift, 0.85f, kw, false);
            } else if (prev != null) {
                double endP = Math.max(prev.end, prev.start + MIN_PRESS);
                if (time - endP < 1.0) {
                    float px = keyX(prev.pitch, kw);
                    float pTip = BLACK[prev.pitch % 12] ? tipBlack : tipWhite;
                    drawFinger(cv, px, pTip, (float) clamp((time - endP) / 0.12), 0.85f, kw, false);
                }
            }
        }
    }

    private void drawFinger(Canvas cv, float x, float tipY, float lift, float a, float kw, boolean press) {
        float fw = Math.max(6 * dp, Math.min(kw * 0.8f, 13 * dp));
        float tip = tipY - lift * 28 * dp;
        float top = tip - 90 * dp;

        // petit halo de couleur là où le doigt touche la touche
        if (press) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alpha(color, 0.35f));
            cv.drawCircle(x, tip + fw * 0.2f, fw * 0.95f, paint);
        }

        rect.set(x - fw / 2, top, x + fw / 2, tip + fw * 0.35f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(0xFFF0C09A, a));
        cv.drawRoundRect(rect, fw / 2, fw / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * dp);
        paint.setColor(alpha(0xFF9C6B4B, a));
        cv.drawRoundRect(rect, fw / 2, fw / 2, paint);

        // plis des phalanges
        paint.setStrokeWidth(0.8f * dp);
        paint.setColor(alpha(0xFF9C6B4B, a * 0.6f));
        cv.drawLine(x - fw * 0.28f, tip - 20 * dp, x + fw * 0.28f, tip - 20 * dp, paint);
        cv.drawLine(x - fw * 0.28f, tip - 36 * dp, x + fw * 0.28f, tip - 36 * dp, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
