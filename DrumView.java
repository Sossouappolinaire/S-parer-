package com.stemnotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Batterie illustrée : grosse caisse, caisse claire, charleston, deux toms, tom basse, crash et ride.
 * Deux baguettes (avec leurs mains) frappent les éléments au moment où ils sonnent, et un pied
 * appuie sur la pédale de la grosse caisse. L'élément frappé s'illumine, les cymbales vibrent.
 */
final class DrumView extends View {

    private static final int HIHAT = 0, SNARE = 1, TOM_HI = 2, TOM_MID = 3, FLOOR = 4, CRASH = 5, RIDE = 6, KICK = 7;
    private static final int COUNT = 8;
    private static final String[] NAMES = {"Charleston", "Caisse claire", "Tom aigu", "Tom médium",
            "Tom basse", "Crash", "Ride", "Grosse caisse"};
    // centre de chaque élément (fraction de la largeur / de la hauteur)
    private static final float[] PX = {0.10f, 0.27f, 0.40f, 0.60f, 0.80f, 0.25f, 0.86f, 0.50f};
    private static final float[] PY = {0.40f, 0.57f, 0.31f, 0.31f, 0.58f, 0.14f, 0.23f, 0.60f};

    private static final int SHELL = 0xFFA32A22;
    private static final int CHROME = 0xFFD0D4DA;
    private static final int HEAD = 0xFFF3EEE2;
    private static final int SKIN = 0xFFF0C09A;
    private static final int SKIN_LINE = 0xFF9C6B4B;
    private static final int[] STICK_COLOR = {0xFF1C7ED6, 0xFFE8590C};

    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private final double[][] hitT = new double[COUNT][];
    private final double[][] sT = new double[2][];   // coups de chaque baguette (gauche, droite)
    private final int[][] sD = new int[2][];
    private double time;
    private final float[] tip = new float[3]; // x, y, hauteur

    DrumView(Context c, NoteRollView.Track tr) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.LEFT);
        build(tr.notes);
    }

    void setTime(double sec) {
        time = sec;
        invalidate();
    }

    /** Élément de la batterie correspondant à un numéro de note (General MIDI). */
    static int drumOf(int pitch) {
        if (pitch <= 36) return KICK;
        if (pitch == 37 || pitch == 38 || pitch == 39 || pitch == 40) return SNARE;
        if (pitch == 41 || pitch == 43 || pitch == 45) return FLOOR;
        if (pitch == 42 || pitch == 44 || pitch == 46) return HIHAT;
        if (pitch == 47) return TOM_MID;
        if (pitch == 48 || pitch == 50) return TOM_HI;
        if (pitch == 49 || pitch == 52 || pitch == 55 || pitch == 57) return CRASH;
        if (pitch == 51 || pitch == 53 || pitch == 59) return RIDE;
        return SNARE;
    }

    // ------------------------------------------------------------ préparation
    private void build(PitchTranscriber.Note[] notes) {
        List<List<Double>> per = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) per.add(new ArrayList<Double>());
        List<double[]> sticks = new ArrayList<>(); // {temps, élément}
        PitchTranscriber.Note[] sorted = notes.clone();
        java.util.Arrays.sort(sorted, new Comparator<PitchTranscriber.Note>() {
            @Override public int compare(PitchTranscriber.Note a, PitchTranscriber.Note b) {
                return Double.compare(a.start, b.start);
            }
        });
        for (PitchTranscriber.Note n : sorted) {
            int d = drumOf(n.pitch);
            per.get(d).add(n.start);
            if (d != KICK) sticks.add(new double[]{n.start, d});
        }
        for (int i = 0; i < COUNT; i++) {
            hitT[i] = new double[per.get(i).size()];
            for (int k = 0; k < hitT[i].length; k++) hitT[i][k] = per.get(i).get(k);
        }

        // répartition des coups entre les deux baguettes
        List<List<double[]>> each = new ArrayList<>();
        each.add(new ArrayList<double[]>());
        each.add(new ArrayList<double[]>());
        double[] lastT = {-9, -9};
        float[] lastX = {PX[SNARE], PX[TOM_MID]};
        int prevStick = 1;
        for (double[] h : sticks) {
            float dx = PX[(int) h[1]];
            double bestCost = 1e9;
            int best = 0;
            for (int s = 0; s < 2; s++) {
                double cost = Math.abs(dx - lastX[s]);
                if (h[0] - lastT[s] < 0.09) cost += 0.6;       // baguette encore occupée
                if (s == prevStick) cost += 0.03;                // alternance
                if (s == 0 && dx > lastX[1] + 0.15f) cost += 0.3; // pas de croisement inutile
                if (s == 1 && dx < lastX[0] - 0.15f) cost += 0.3;
                if (cost < bestCost) { bestCost = cost; best = s; }
            }
            each.get(best).add(h);
            lastT[best] = h[0];
            lastX[best] = dx;
            prevStick = best;
        }
        for (int s = 0; s < 2; s++) {
            List<double[]> l = each.get(s);
            sT[s] = new double[l.size()];
            sD[s] = new int[l.size()];
            for (int k = 0; k < l.size(); k++) {
                sT[s][k] = l.get(k)[0];
                sD[s][k] = (int) l.get(k)[1];
            }
        }
    }

    private static int lastAtOrBefore(double[] arr, double t) {
        int a = 0, b = arr.length;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (arr[m] <= t) a = m + 1; else b = m;
        }
        return a - 1;
    }

    /** Intensité (0..1) du dernier coup sur un élément : 1 à l'attaque, puis s'éteint. */
    private float hitAmount(int d, double decay) {
        int i = lastAtOrBefore(hitT[d], time);
        if (i < 0) return 0f;
        double dt = time - hitT[d][i];
        return dt >= decay ? 0f : (float) (1 - dt / decay);
    }

    /** Appui sur la pédale : monte juste avant le coup, retombe juste après. */
    private float kickPress() {
        double[] k = hitT[KICK];
        if (k.length == 0) return 0f;
        int i = lastAtOrBefore(k, time);
        double p = 0;
        if (i >= 0) {
            double dt = time - k[i];
            if (dt < 0.11) p = Math.max(p, 1 - dt / 0.11);
        }
        if (i + 1 < k.length) {
            double dn = k[i + 1] - time;
            if (dn < 0.06) p = Math.max(p, 1 - dn / 0.06);
        }
        return (float) p;
    }

    // ------------------------------------------------------------ baguettes
    private void tipPosition(int s, float w, float h) {
        double[] T = sT[s];
        int[] D = sD[s];
        float restX = (s == 0 ? 0.30f : 0.70f) * w;
        float restY = 0.60f * h;
        if (T.length == 0) {
            tip[0] = restX; tip[1] = restY; tip[2] = 16 * dp;
            return;
        }
        int idx = lastAtOrBefore(T, time);
        float prevX = idx >= 0 ? PX[D[idx]] * w : restX;
        float prevY = idx >= 0 ? PY[D[idx]] * h : restY;
        double tPrev = idx >= 0 ? T[idx] : T[0] - 1.0;
        double since = time - tPrev;
        float rebound = (float) (14 * dp * (1 - Math.exp(-since / 0.03)) * Math.exp(-since / 0.2)
                + 5 * dp * (1 - Math.exp(-since / 0.12)));
        if (idx + 1 >= T.length) {
            tip[0] = prevX; tip[1] = prevY; tip[2] = rebound;
            return;
        }
        float nextX = PX[D[idx + 1]] * w;
        float nextY = PY[D[idx + 1]] * h;
        double tNext = T[idx + 1];
        double moveStart = Math.max(tPrev + 0.05, tNext - 0.45);
        if (time < moveStart) {
            tip[0] = prevX; tip[1] = prevY; tip[2] = rebound;
            return;
        }
        double f = (time - moveStart) / Math.max(tNext - moveStart, 1e-3);
        f = f < 0 ? 0 : (f > 1 ? 1 : f);
        double e = f * f * (3 - 2 * f);
        float dist = (float) Math.hypot(nextX - prevX, nextY - prevY);
        float peak = Math.min(34 * dp, 12 * dp + dist * 0.10f);
        tip[0] = (float) (prevX + (nextX - prevX) * e);
        tip[1] = (float) (prevY + (nextY - prevY) * e);
        tip[2] = (float) (peak * Math.sin(Math.PI * f) + rebound * (1 - f));
    }

    // ------------------------------------------------------------ dessin
    private static int shade(int c, float f) { // f < 0 : plus sombre, f > 0 : plus clair
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        if (f < 0) {
            r = (int) (r * (1 + f)); g = (int) (g * (1 + f)); b = (int) (b * (1 + f));
        } else {
            r += (int) ((255 - r) * f); g += (int) ((255 - g) * f); b += (int) ((255 - b) * f);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int alpha(int c, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (c & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float[] amt = new float[COUNT];
        for (int d = 0; d < COUNT; d++) {
            double decay = (d == CRASH || d == RIDE) ? 0.55 : (d == HIHAT ? 0.14 : 0.22);
            amt[d] = hitAmount(d, decay);
        }
        float press = kickPress();

        // fond
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF2E2A27, 0xFF1B1917, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // tapis sous la batterie
        paint.setColor(0xFF3A2F2A);
        rect.set(w * 0.04f, h * 0.80f, w * 0.96f, h * 1.05f);
        cv.drawOval(rect, paint);

        // pieds des cymbales
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * dp);
        paint.setColor(0xFF8D9299);
        cv.drawLine(PX[CRASH] * w, PY[CRASH] * h, PX[CRASH] * w - 6 * dp, h * 0.82f, paint);
        cv.drawLine(PX[RIDE] * w, PY[RIDE] * h, PX[RIDE] * w + 5 * dp, h * 0.84f, paint);
        cv.drawLine(PX[HIHAT] * w, PY[HIHAT] * h, PX[HIHAT] * w, h * 0.86f, paint);
        paint.setStyle(Paint.Style.FILL);

        cymbal(cv, PX[CRASH] * w, PY[CRASH] * h, w * 0.105f, amt[CRASH]);
        cymbal(cv, PX[RIDE] * w, PY[RIDE] * h, w * 0.115f, amt[RIDE]);

        // jambe du batteur derrière la grosse caisse
        float kcx = PX[KICK] * w;
        float kcy = PY[KICK] * h;
        float kr = Math.min(w * 0.165f, h * 0.27f) * (1 + 0.02f * press);
        paint.setColor(0xFF263248);
        rect.set(kcx - 15 * dp, kcy + kr * 0.4f, kcx + 15 * dp, h - 20 * dp + press * 8 * dp);
        cv.drawRoundRect(rect, 8 * dp, 8 * dp, paint);

        kick(cv, kcx, kcy, kr, amt[KICK]);

        tom(cv, PX[TOM_HI] * w, PY[TOM_HI] * h, w * 0.085f, 16 * dp, SHELL, amt[TOM_HI], false);
        tom(cv, PX[TOM_MID] * w, PY[TOM_MID] * h, w * 0.092f, 17 * dp, SHELL, amt[TOM_MID], false);
        tom(cv, PX[FLOOR] * w, PY[FLOOR] * h, w * 0.105f, 30 * dp, SHELL, amt[FLOOR], false);
        tom(cv, PX[SNARE] * w, PY[SNARE] * h, w * 0.098f, 15 * dp, 0xFFB9BEC6, amt[SNARE], true);
        hiHat(cv, PX[HIHAT] * w, PY[HIHAT] * h, w * 0.078f, amt[HIHAT]);

        pedalAndFoot(cv, kcx, h, press);

        // baguettes (gauche puis droite)
        for (int s = 0; s < 2; s++) {
            tipPosition(s, w, h);
            drawStick(cv, s, tip[0], tip[1] - tip[2], w, h);
        }

        // éléments qui sonnent
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < COUNT; d++) {
            if (amt[d] > 0.35f) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(NAMES[d]);
            }
        }
        if (sb.length() > 0) {
            text.setFakeBoldText(true);
            text.setTextSize(11 * dp);
            text.setColor(0xFFFFC857);
            cv.drawText(sb.toString(), 8 * dp, 14 * dp, text);
        }
    }

    private void cymbal(Canvas cv, float cx, float cy, float rx, float amt) {
        float ry = rx * 0.30f;
        cv.save();
        cv.rotate((float) (Math.sin(time * 55) * amt * 5), cx, cy);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, rx, new int[]{0xFFF8E394, 0xFFDDB24A, 0xFF9A7020},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        cv.drawOval(rect, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f * dp);
        paint.setColor(0x40604010);
        for (int k = 1; k <= 3; k++) {
            float f = 0.30f + 0.20f * k;
            rect.set(cx - rx * f, cy - ry * f, cx + rx * f, cy + ry * f);
            cv.drawOval(rect, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF6E7A8);
        rect.set(cx - rx * 0.16f, cy - ry * 0.18f, cx + rx * 0.16f, cy + ry * 0.18f);
        cv.drawOval(rect, paint);
        if (amt > 0) {
            paint.setColor(alpha(0xFFFFFFFF, amt * 0.45f));
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
            cv.drawOval(rect, paint);
        }
        cv.restore();
    }

    private void hiHat(Canvas cv, float cx, float cy, float rx, float amt) {
        float ry = rx * 0.30f;
        float dip = amt * 2.5f * dp;
        paint.setStyle(Paint.Style.FILL);
        // cymbale du bas
        paint.setShader(new RadialGradient(cx, cy + 5 * dp, rx, new int[]{0xFFE6C766, 0xFF9A7020},
                new float[]{0.3f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx, cy + 5 * dp - ry, cx + rx, cy + 5 * dp + ry);
        cv.drawOval(rect, paint);
        // cymbale du haut
        paint.setShader(new RadialGradient(cx, cy + dip, rx, new int[]{0xFFF8E394, 0xFFC99A36},
                new float[]{0.2f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx, cy + dip - ry, cx + rx, cy + dip + ry);
        cv.drawOval(rect, paint);
        paint.setShader(null);
        paint.setColor(0xFFF6E7A8);
        rect.set(cx - rx * 0.16f, cy + dip - ry * 0.2f, cx + rx * 0.16f, cy + dip + ry * 0.2f);
        cv.drawOval(rect, paint);
        if (amt > 0) {
            paint.setColor(alpha(0xFFFFFFFF, amt * 0.5f));
            rect.set(cx - rx, cy + dip - ry, cx + rx, cy + dip + ry);
            cv.drawOval(rect, paint);
        }
    }

    /** Tom ou caisse claire vus de dessus : fût, peau, cercle chromé, tirants. */
    private void tom(Canvas cv, float cx, float cy, float rx, float depth, int shell, float amt, boolean snare) {
        float ry = rx * 0.38f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx - rx, 0, cx + rx, 0,
                new int[]{shade(shell, -0.45f), shell, shade(shell, 0.35f), shell, shade(shell, -0.5f)},
                new float[]{0f, 0.25f, 0.45f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx, cy, cx + rx, cy + depth);
        cv.drawRect(rect, paint);
        rect.set(cx - rx, cy + depth - ry, cx + rx, cy + depth + ry);
        cv.drawOval(rect, paint);
        paint.setShader(null);
        // tirants
        paint.setColor(CHROME);
        for (int i = 0; i < 5; i++) {
            float x = cx - rx + (i + 0.5f) * (2 * rx) / 5f;
            float yb = cy + depth + ry * (float) Math.sqrt(Math.max(0, 1 - Math.pow((x - cx) / rx, 2))) - 3 * dp;
            rect.set(x - 1.3f * dp, cy + ry * 0.7f, x + 1.3f * dp, yb);
            cv.drawRect(rect, paint);
        }
        // peau
        paint.setShader(new RadialGradient(cx, cy, rx, new int[]{shade(HEAD, 0.4f), HEAD, shade(HEAD, -0.12f)},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        cv.drawOval(rect, paint);
        paint.setShader(null);
        if (snare) { // timbre
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.7f * dp);
            paint.setColor(0x33000000);
            for (int i = -2; i <= 2; i++) {
                cv.drawLine(cx - rx * 0.8f, cy + i * ry * 0.22f, cx + rx * 0.8f, cy + i * ry * 0.22f, paint);
            }
        }
        if (amt > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alpha(0xFFFFC857, amt * 0.75f));
            float g = 0.55f + 0.4f * amt;
            rect.set(cx - rx * g, cy - ry * g, cx + rx * g, cy + ry * g);
            cv.drawOval(rect, paint);
        }
        // cercle chromé
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * dp);
        paint.setColor(CHROME);
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        cv.drawOval(rect, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void kick(Canvas cv, float cx, float cy, float r, float amt) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.5f,
                new int[]{shade(SHELL, 0.3f), SHELL, shade(SHELL, -0.5f)}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
        // peau avant
        paint.setShader(new RadialGradient(cx, cy, r, new int[]{shade(HEAD, 0.45f), HEAD, shade(HEAD, -0.15f)},
                new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r - 7 * dp, paint);
        paint.setShader(null);
        // anneau décoratif et trou de résonance
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * dp);
        paint.setColor(0x33000000);
        cv.drawCircle(cx, cy, (r - 7 * dp) * 0.55f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1B1917);
        cv.drawCircle(cx + r * 0.38f, cy + r * 0.38f, r * 0.12f, paint);
        if (amt > 0) {
            paint.setColor(alpha(0xFFFFC857, amt * 0.7f));
            cv.drawCircle(cx, cy, (r - 7 * dp) * (0.5f + 0.45f * amt), paint);
        }
        // cercle chromé
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f * dp);
        paint.setColor(CHROME);
        cv.drawCircle(cx, cy, r - 3 * dp, paint);
        paint.setStyle(Paint.Style.FILL);
        // tirants
        paint.setColor(CHROME);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + Math.PI / 8;
            cv.drawCircle((float) (cx + Math.cos(a) * (r + 1 * dp)), (float) (cy + Math.sin(a) * (r + 1 * dp)),
                    2f * dp, paint);
        }
    }

    /** Pédale de grosse caisse, battant et pied du batteur. */
    private void pedalAndFoot(Canvas cv, float kcx, float h, float press) {
        float y0 = h - 16 * dp;
        // plateau de la pédale
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF4A4F57);
        rect.set(kcx - 20 * dp, y0 + press * 3 * dp, kcx + 20 * dp, y0 + 8 * dp + press * 3 * dp);
        cv.drawRoundRect(rect, 3 * dp, 3 * dp, paint);
        // chaussure
        float sy = y0 - 22 * dp + press * 8 * dp;
        paint.setColor(0xFFEDEDED);
        rect.set(kcx - 13 * dp, sy, kcx + 13 * dp, sy + 26 * dp);
        cv.drawRoundRect(rect, 9 * dp, 9 * dp, paint);
        paint.setColor(0xFF2A2A2A);
        rect.set(kcx - 14 * dp, sy + 21 * dp, kcx + 14 * dp, sy + 26 * dp);
        cv.drawRoundRect(rect, 3 * dp, 3 * dp, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * dp);
        paint.setColor(0xFFB5B5B5);
        cv.drawLine(kcx - 6 * dp, sy + 6 * dp, kcx + 6 * dp, sy + 6 * dp, paint);
        cv.drawLine(kcx - 6 * dp, sy + 11 * dp, kcx + 6 * dp, sy + 11 * dp, paint);
        paint.setStyle(Paint.Style.FILL);

        // battant : la boule de feutre vient toucher la peau quand le pied appuie
        float kr = Math.min(getWidth() * 0.165f, h * 0.27f);
        float kcy = PY[KICK] * h;
        float tipY = kcy + kr * 0.55f - press * kr * 0.50f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * dp);
        paint.setColor(0xFF9AA0A8);
        cv.drawLine(kcx, y0 - 4 * dp, kcx, tipY, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF5A5A5A);
        cv.drawCircle(kcx, tipY, 4 * dp, paint);
    }

    private void drawStick(Canvas cv, int s, float tx, float ty, float w, float h) {
        // l'avant-bras part du bas de l'écran : on garde une baguette de longueur constante
        float ax = (s == 0 ? 0.14f : 0.86f) * w;
        float ay = h + 10 * dp;
        float dx = ax - tx, dy = ay - ty;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1) len = 1;
        float ux = dx / len, uy = dy / len;
        float stickLen = 92 * dp;
        float ex = tx + ux * stickLen;
        float ey = ty + uy * stickLen;

        // avant-bras
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(15 * dp);
        paint.setColor(SKIN);
        cv.drawLine(ex, ey, ax, ay, paint);
        paint.setStrokeWidth(3 * dp);
        paint.setColor(alpha(STICK_COLOR[s], 0.95f)); // bracelet : bleu = gauche, orange = droite
        float bx = ex + ux * 26 * dp, by = ey + uy * 26 * dp;
        cv.drawLine(bx - uy * 7 * dp, by + ux * 7 * dp, bx + uy * 7 * dp, by - ux * 7 * dp, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // baguette
        float nx = -uy, ny = ux;
        path.reset();
        path.moveTo(tx + nx * 1.6f * dp, ty + ny * 1.6f * dp);
        path.lineTo(ex + nx * 3.2f * dp, ey + ny * 3.2f * dp);
        path.lineTo(ex - nx * 3.2f * dp, ey - ny * 3.2f * dp);
        path.lineTo(tx - nx * 1.6f * dp, ty - ny * 1.6f * dp);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFD8B078);
        cv.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f * dp);
        paint.setColor(0xFF8A6A3E);
        cv.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF1D9A8);
        cv.drawCircle(tx, ty, 3.2f * dp, paint);

        // main qui tient la baguette
        paint.setColor(SKIN);
        rect.set(ex - 9 * dp, ey - 8 * dp, ex + 9 * dp, ey + 8 * dp);
        cv.drawOval(rect, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * dp);
        paint.setColor(SKIN_LINE);
        cv.drawOval(rect, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
