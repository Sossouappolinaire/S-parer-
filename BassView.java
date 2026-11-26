package com.stemnotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Image d'une guitare basse 4 cordes (mi la ré sol) : tête avec mécaniques, manche à 12 frettes,
 * corps avec micros et chevalet. Les notes jouées s'allument en temps réel sur la bonne corde et
 * la bonne case, la corde vibre, et la prochaine note apparaît en contour juste avant d'être jouée.
 */
final class BassView extends View {

    private static final int[] OPEN = {43, 38, 33, 28}; // corde du haut vers le bas : Sol, Ré, La, Mi
    private static final int FRETS = 12;
    private static final double PREVIEW = 0.40;

    private final PitchTranscriber.Note[] notes;
    private final int[] strOf;
    private final int[] fretOf;
    private final int[] shown; // hauteur réellement affichée (ramenée dans la tessiture de la basse)
    private final int color;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private double maxLen;
    private double time;

    BassView(Context c, NoteRollView.Track tr) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        notes = tr.notes;
        color = tr.color;
        maxLen = tr.maxLen;
        strOf = new int[notes.length];
        fretOf = new int[notes.length];
        shown = new int[notes.length];
        text.setTextAlign(Paint.Align.CENTER);
        assign();
    }

    void setTime(double sec) {
        time = sec;
        invalidate();
    }

    /** Choisit pour chaque note la corde et la case, en restant près de la position précédente. */
    private void assign() {
        int lastStr = 2;
        int lastFret = 3;
        for (int i = 0; i < notes.length; i++) {
            int p = notes[i].pitch;
            while (p < OPEN[3]) p += 12;
            while (p > OPEN[0] + FRETS) p -= 12;
            int bestS = -1;
            int bestF = 0;
            double best = 1e9;
            for (int s = 0; s < 4; s++) {
                int f = p - OPEN[s];
                if (f < 0 || f > FRETS) continue;
                double cost = Math.abs(f - lastFret) + (s != lastStr ? 0.6 : 0) + f * 0.15;
                if (cost < best) { best = cost; bestS = s; bestF = f; }
            }
            if (bestS < 0) { bestS = 3; bestF = Math.max(0, Math.min(FRETS, p - OPEN[3])); }
            strOf[i] = bestS;
            fretOf[i] = bestF;
            shown[i] = p;
            lastStr = bestS;
            lastFret = bestF;
        }
    }

    private int firstIndex(double start) {
        int a = 0, b = notes.length;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (notes[m].start < start) a = m + 1; else b = m;
        }
        return a;
    }

    private static int alpha(int c, float a) {
        return ((int) (a * 255) << 24) | (c & 0xFFFFFF);
    }

    // géométrie
    private float nutX, neckEnd, neckTop, neckH, bridgeX;

    private float fretX(int n) {
        double full = 1 - Math.pow(2, -12.5 / 12.0);
        return nutX + (neckEnd - nutX) * (float) ((1 - Math.pow(2, -n / 12.0)) / full);
    }

    private float noteX(int fret) {
        if (fret == 0) return nutX - 11 * dp;
        return (fretX(fret - 1) + fretX(fret)) / 2f;
    }

    private float stringY(int s) {
        return neckTop + neckH * (0.125f + 0.25f * s);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        neckH = h * 0.62f;
        neckTop = (h - neckH) / 2f - 4 * dp;
        float headW = w * 0.10f;
        nutX = headW;
        neckEnd = w * 0.72f;
        bridgeX = w - w * 0.07f;

        // corps (rouge) derrière le manche
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFB5381C);
        rect.set(neckEnd - 6 * dp, neckTop - 8 * dp, w - 2 * dp, neckTop + neckH + 8 * dp);
        cv.drawRoundRect(rect, 22 * dp, 22 * dp, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * dp);
        paint.setColor(0xFF6E1F0E);
        cv.drawRoundRect(rect, 22 * dp, 22 * dp, paint);

        // micros et chevalet
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1E1E1E);
        float pk = neckEnd + (bridgeX - neckEnd) * 0.22f;
        rect.set(pk, neckTop - 3 * dp, pk + 11 * dp, neckTop + neckH + 3 * dp);
        cv.drawRoundRect(rect, 2 * dp, 2 * dp, paint);
        pk = neckEnd + (bridgeX - neckEnd) * 0.55f;
        rect.set(pk, neckTop - 3 * dp, pk + 11 * dp, neckTop + neckH + 3 * dp);
        cv.drawRoundRect(rect, 2 * dp, 2 * dp, paint);
        paint.setColor(0xFFB0B0B0);
        rect.set(bridgeX - 3 * dp, neckTop - 2 * dp, bridgeX + 3 * dp, neckTop + neckH + 2 * dp);
        cv.drawRoundRect(rect, 2 * dp, 2 * dp, paint);

        // tête de manche + mécaniques
        paint.setColor(0xFF3B2416);
        rect.set(2 * dp, neckTop + neckH * 0.05f, nutX + 2 * dp, neckTop + neckH * 0.95f);
        cv.drawRoundRect(rect, 7 * dp, 7 * dp, paint);
        paint.setColor(0xFFC8C8C8);
        for (int i = 0; i < 4; i++) {
            float cy = i < 2 ? neckTop + neckH * 0.14f : neckTop + neckH * 0.86f;
            float cx = 7 * dp + (i % 2) * 11 * dp;
            cv.drawCircle(cx, cy, 2.6f * dp, paint);
        }

        // touche (palissandre)
        paint.setColor(0xFF5A3A22);
        rect.set(nutX, neckTop, neckEnd, neckTop + neckH);
        cv.drawRect(rect, paint);

        // repères de position
        paint.setColor(0xFFE6D9B8);
        int[] marks = {3, 5, 7, 9};
        for (int m : marks) {
            cv.drawCircle((fretX(m - 1) + fretX(m)) / 2f, neckTop + neckH / 2f, 2.2f * dp, paint);
        }
        float x12 = (fretX(11) + fretX(12)) / 2f;
        cv.drawCircle(x12, neckTop + neckH * 0.3f, 2.2f * dp, paint);
        cv.drawCircle(x12, neckTop + neckH * 0.7f, 2.2f * dp, paint);

        // sillet et frettes
        paint.setColor(0xFFF2EAD3);
        cv.drawRect(nutX - 1.5f * dp, neckTop, nutX + 1.5f * dp, neckTop + neckH, paint);
        paint.setColor(0xFFB8B8B8);
        for (int n = 1; n <= FRETS; n++) {
            float x = fretX(n);
            cv.drawRect(x - 0.8f * dp, neckTop, x + 0.8f * dp, neckTop + neckH, paint);
        }

        // numéros de cases
        text.setTextSize(8 * dp);
        text.setColor(0xFF888888);
        int[] nums = {3, 5, 7, 9, 12};
        for (int m : nums) {
            cv.drawText(String.valueOf(m), (fretX(m - 1) + fretX(m)) / 2f, neckTop + neckH + 15 * dp, text);
        }

        // notes en cours / à venir
        int cnt = 0;
        int[] actS = new int[8];
        int[] actI = new int[8];
        for (int i = firstIndex(time - maxLen); i < notes.length; i++) {
            PitchTranscriber.Note x = notes[i];
            if (x.start > time + PREVIEW) break;
            if (x.start <= time && x.end > time && cnt < 8) {
                actI[cnt] = i;
                actS[cnt] = strOf[i];
                cnt++;
            }
        }

        // cordes (celles qui sonnent vibrent)
        for (int s = 0; s < 4; s++) {
            float y = stringY(s);
            float th = (1.1f + 0.5f * s) * dp;
            int vib = -1;
            for (int k = 0; k < cnt; k++) if (actS[k] == s) vib = actI[k];
            if (vib < 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFD9D9D9);
                cv.drawRect(nutX, y - th / 2, bridgeX, y + th / 2, paint);
            } else {
                double age = time - notes[vib].start;
                float amp = (float) (2.6 * dp * Math.exp(-age * 2.0));
                path.reset();
                int steps = 36;
                for (int k = 0; k <= steps; k++) {
                    float fx = nutX + (bridgeX - nutX) * k / steps;
                    float env = (float) Math.sin(Math.PI * k / steps);
                    float fy = y + amp * env * (float) Math.sin(time * 90.0);
                    if (k == 0) path.moveTo(fx, fy); else path.lineTo(fx, fy);
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(th * 1.2f);
                paint.setColor(0xFFFFFFFF);
                cv.drawPath(path, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);

        // notes qui vont arriver : cercle en contour
        text.setTextSize(7 * dp);
        for (int i = firstIndex(time); i < notes.length; i++) {
            PitchTranscriber.Note x = notes[i];
            if (x.start > time + PREVIEW) break;
            if (x.start <= time) continue;
            float f = (float) (1 - (x.start - time) / PREVIEW);
            float cx = noteX(fretOf[i]);
            float cy = stringY(strOf[i]);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * dp);
            paint.setColor(alpha(color, 0.35f + 0.5f * f));
            cv.drawCircle(cx, cy, radius(fretOf[i]) * (1.25f - 0.25f * f), paint);
        }

        // notes qui sonnent : cercle plein avec le nom de la note
        for (int k = 0; k < cnt; k++) {
            int i = actI[k];
            float cx = noteX(fretOf[i]);
            float cy = stringY(strOf[i]);
            float r = radius(fretOf[i]);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alpha(color, 0.30f));
            cv.drawCircle(cx, cy, r * 1.35f, paint);
            paint.setColor(color);
            cv.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * dp);
            paint.setColor(0xFFFFFFFF);
            cv.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.FILL);
            if (r >= 6.5f * dp) {
                text.setTextSize(7 * dp);
                text.setColor(0xFFFFFFFF);
                cv.drawText(PitchTranscriber.noteName(shown[i]), cx, cy + 2.5f * dp, text);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private float radius(int fret) {
        float span = fret == 0 ? 18 * dp : fretX(fret) - fretX(fret - 1);
        return Math.max(5 * dp, Math.min(span * 0.42f, 9 * dp));
    }
}
