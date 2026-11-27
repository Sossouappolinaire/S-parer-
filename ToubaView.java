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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Touba (paire de congas) illustrée : un Touba grave à gauche, un Touba aigu à droite, peints en orange,
 * blanc et vert sur un support noir. Deux mains frappent les peaux au moment où elles sonnent ;
 * la peau frappée s'illumine. Ce n'est plus un élément de la batterie : le Touba a sa propre vue.
 */
final class ToubaView extends View {

    private static final int LOW = 0, HIGH = 1;
    private static final int COUNT = 2;
    private static final String[] NAMES = {"Touba grave", "Touba aigu"};
    // centre de la peau de chaque Touba (fraction de la largeur / de la hauteur)
    private static final float[] PX = {0.29f, 0.71f};
    private static final float[] PY = {0.30f, 0.32f};
    private static final float[] RX = {0.155f, 0.135f}; // rayon de la peau (fraction de la largeur)

    private static final int C_ORANGE = 0xFFF26B1D;
    private static final int C_WHITE = 0xFFF6F1E7;
    private static final int C_RED = 0xFF9E1B3A;
    private static final int C_GREEN = 0xFF2FB344;
    private static final int SKIN = 0xFFF0C09A;
    private static final int SKIN_LINE = 0xFF9C6B4B;
    private static final int[] BAND_COLOR = {C_ORANGE, C_RED, C_WHITE, C_RED, C_ORANGE, C_GREEN};
    private static final float[] BAND_END = {0.14f, 0.20f, 0.58f, 0.64f, 0.82f, 1.30f};
    private static final int[] HAND_COLOR = {0xFF1C7ED6, 0xFFE8590C};

    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private final double[][] hitT = new double[COUNT][];
    private final double[][] hT = new double[2][];  // coups de chaque main (gauche, droite)
    private final int[][] hD = new int[2][];
    private boolean empty;
    private double time;
    private final float[] tip = new float[3]; // x, y, hauteur

    ToubaView(Context c, NoteRollView.Track tr) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.LEFT);
        build(tr.notes);
    }

    void setTime(double sec) {
        time = sec;
        invalidate();
    }

    private static int drumOf(int pitch) {
        return pitch == DrumTranscriber.TOUBA_LOW ? LOW : HIGH;
    }

    // ------------------------------------------------------------ préparation
    private void build(PitchTranscriber.Note[] notes) {
        empty = notes.length == 0;
        List<List<Double>> per = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) per.add(new ArrayList<Double>());
        List<double[]> hits = new ArrayList<>(); // {temps, peau}
        PitchTranscriber.Note[] sorted = notes.clone();
        Arrays.sort(sorted, new Comparator<PitchTranscriber.Note>() {
            @Override public int compare(PitchTranscriber.Note a, PitchTranscriber.Note b) {
                return Double.compare(a.start, b.start);
            }
        });
        for (PitchTranscriber.Note n : sorted) {
            int d = drumOf(n.pitch);
            per.get(d).add(n.start);
            hits.add(new double[]{n.start, d});
        }
        for (int i = 0; i < COUNT; i++) {
            hitT[i] = new double[per.get(i).size()];
            for (int k = 0; k < hitT[i].length; k++) hitT[i][k] = per.get(i).get(k);
        }

        // répartition des coups entre les deux mains
        List<List<double[]>> each = new ArrayList<>();
        each.add(new ArrayList<double[]>());
        each.add(new ArrayList<double[]>());
        double[] lastT = {-9, -9};
        float[] lastX = {PX[LOW], PX[HIGH]};
        int prevHand = 1;
        for (double[] h : hits) {
            float dx = PX[(int) h[1]];
            double bestCost = 1e9;
            int best = 0;
            for (int s = 0; s < 2; s++) {
                double cost = Math.abs(dx - lastX[s]);
                if (h[0] - lastT[s] < 0.12) cost += 0.6;          // main encore occupée
                if (s == prevHand) cost += 0.03;                   // alternance
                if (s == 0 && dx > lastX[1] + 0.15f) cost += 0.3;  // pas de croisement inutile
                if (s == 1 && dx < lastX[0] - 0.15f) cost += 0.3;
                if (cost < bestCost) { bestCost = cost; best = s; }
            }
            each.get(best).add(h);
            lastT[best] = h[0];
            lastX[best] = dx;
            prevHand = best;
        }
        for (int s = 0; s < 2; s++) {
            List<double[]> l = each.get(s);
            hT[s] = new double[l.size()];
            hD[s] = new int[l.size()];
            for (int k = 0; k < l.size(); k++) {
                hT[s][k] = l.get(k)[0];
                hD[s][k] = (int) l.get(k)[1];
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

    /** Intensité (0..1) du dernier coup sur une peau : 1 à l'attaque, puis s'éteint. */
    private float hitAmount(int d, double decay) {
        int i = lastAtOrBefore(hitT[d], time);
        if (i < 0) return 0f;
        double dt = time - hitT[d][i];
        return dt >= decay ? 0f : (float) (1 - dt / decay);
    }

    // ------------------------------------------------------------ mains
    private void tipPosition(int s, float w, float h) {
        double[] T = hT[s];
        int[] D = hD[s];
        float restX = PX[s] * w;
        float restY = PY[s] * h;
        if (T.length == 0) {
            tip[0] = restX; tip[1] = restY; tip[2] = 16 * dp;
            return;
        }
        int idx = lastAtOrBefore(T, time);
        float prevX = idx >= 0 ? PX[D[idx]] * w : restX;
        float prevY = idx >= 0 ? PY[D[idx]] * h : restY;
        double tPrev = idx >= 0 ? T[idx] : T[0] - 1.0;
        double since = time - tPrev;
        float rebound = (float) (12 * dp * (1 - Math.exp(-since / 0.03)) * Math.exp(-since / 0.2)
                + 5 * dp * (1 - Math.exp(-since / 0.12)));
        if (idx + 1 >= T.length) {
            tip[0] = prevX; tip[1] = prevY; tip[2] = rebound;
            return;
        }
        float nextX = PX[D[idx + 1]] * w;
        float nextY = PY[D[idx + 1]] * h;
        double tNext = T[idx + 1];
        double moveStart = Math.max(tPrev + 0.05, tNext - 0.40);
        if (time < moveStart) {
            tip[0] = prevX; tip[1] = prevY; tip[2] = rebound;
            return;
        }
        double f = (time - moveStart) / Math.max(tNext - moveStart, 1e-3);
        f = f < 0 ? 0 : (f > 1 ? 1 : f);
        double e = f * f * (3 - 2 * f);
        float dist = (float) Math.hypot(nextX - prevX, nextY - prevY);
        float peak = Math.min(32 * dp, 14 * dp + dist * 0.08f);
        tip[0] = (float) (prevX + (nextX - prevX) * e);
        tip[1] = (float) (prevY + (nextY - prevY) * e);
        tip[2] = (float) (peak * Math.sin(Math.PI * f) + rebound * (1 - f));
    }

    // ------------------------------------------------------------ dessin
    private static int alpha(int c, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (c & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float[] amt = new float[COUNT];
        for (int d = 0; d < COUNT; d++) amt[d] = hitAmount(d, 0.25);

        // fond
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF2E2A27, 0xFF1B1917, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // sol
        paint.setColor(0xFF3A2F2A);
        rect.set(w * 0.04f, h * 0.84f, w * 0.96f, h * 1.08f);
        cv.drawOval(rect, paint);

        stand(cv, w, h);
        drum(cv, LOW, w, h, amt[LOW]);
        drum(cv, HIGH, w, h, amt[HIGH]);

        // mains (gauche puis droite)
        for (int s = 0; s < 2; s++) {
            tipPosition(s, w, h);
            drawHand(cv, s, tip[0], tip[1] - tip[2], w, h);
        }

        text.setFakeBoldText(true);
        text.setTextSize(11 * dp);
        text.setColor(0xFFFFC857);
        if (empty) {
            cv.drawText("Aucun coup de Touba détecté dans cette piste", 8 * dp, 14 * dp, text);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < COUNT; d++) {
            if (amt[d] > 0.35f) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(NAMES[d]);
            }
        }
        if (sb.length() > 0) cv.drawText(sb.toString(), 8 * dp, 14 * dp, text);
    }

    /** Support noir : barre entre les deux Touba, pieds et barre au sol. */
    private void stand(Canvas cv, float w, float h) {
        float rb0 = RX[LOW] * w * 0.62f;
        float rb1 = RX[HIGH] * w * 0.62f;
        float c0 = PX[LOW] * w, c1 = PX[HIGH] * w;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(5 * dp);
        paint.setColor(0xFF111111);
        cv.drawLine(c0, 0.62f * h, c1, 0.62f * h, paint);                      // barre du milieu
        cv.drawLine(c0 - rb0 * 1.1f, 0.62f * h, c0 - rb0 * 1.1f - 6 * dp, 0.95f * h, paint); // pieds
        cv.drawLine(c1 + rb1 * 1.1f, 0.62f * h, c1 + rb1 * 1.1f + 6 * dp, 0.95f * h, paint);
        cv.drawLine(c0 + rb0 * 1.0f, 0.62f * h, c0 + rb0 * 1.0f + 4 * dp, 0.95f * h, paint);
        cv.drawLine(c1 - rb1 * 1.0f, 0.62f * h, c1 - rb1 * 1.0f - 4 * dp, 0.95f * h, paint);
        cv.drawLine(c0 - rb0 * 1.1f - 6 * dp, 0.95f * h, c1 + rb1 * 1.1f + 6 * dp, 0.95f * h, paint); // barre au sol
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Un Touba vu de face : fût peint orange / blanc / vert, peau, cercle noir, tirants. */
    private void drum(Canvas cv, int d, float w, float h, float amt) {
        float cx = PX[d] * w;
        float cy = PY[d] * h;
        float rx = RX[d] * w;
        float ry = rx * 0.32f;
        float yb = 0.86f * h;
        float rb = rx * 0.62f;
        float len = yb - cy;
        float ryb = rb * 0.30f;

        // forme du fût (cône arrondi)
        path.reset();
        path.moveTo(cx - rx, cy);
        path.cubicTo(cx - rx * 1.02f, cy + len * 0.45f, cx - rb * 1.35f, cy + len * 0.78f, cx - rb, yb);
        path.quadTo(cx, yb + ryb * 2f, cx + rb, yb);
        path.cubicTo(cx + rb * 1.35f, cy + len * 0.78f, cx + rx * 1.02f, cy + len * 0.45f, cx + rx, cy);
        path.close();

        cv.save();
        cv.clipPath(path);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        float t0 = 0f;
        for (int i = 0; i < BAND_COLOR.length; i++) {
            paint.setColor(BAND_COLOR[i]);
            rect.set(cx - rx * 1.3f, cy + len * t0, cx + rx * 1.3f, cy + len * BAND_END[i]);
            cv.drawRect(rect, paint);
            t0 = BAND_END[i];
        }
        // volume : ombre sur les côtés, reflet à gauche du centre
        paint.setShader(new LinearGradient(cx - rx, 0, cx + rx, 0,
                new int[]{0x88000000, 0x00000000, 0x30FFFFFF, 0x00000000, 0x88000000},
                new float[]{0f, 0.28f, 0.40f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx * 1.3f, cy, cx + rx * 1.3f, yb + ryb * 2f);
        cv.drawRect(rect, paint);
        paint.setShader(null);
        cv.restore();

        // tirants sous le cercle : tige noire et petite plaque
        for (int i = 0; i < 5; i++) {
            float x = cx - rx + (i + 0.5f) * (2 * rx) / 5f;
            double k = Math.max(0, 1 - Math.pow((x - cx) / rx, 2));
            float yEdge = cy + ry * (float) Math.sqrt(k);
            paint.setColor(0xFF151515);
            rect.set(x - 2f * dp, yEdge, x + 2f * dp, yEdge + 18 * dp);
            cv.drawRect(rect, paint);
            paint.setColor(0xFF3A3A3A);
            rect.set(x - 4.5f * dp, yEdge + 10 * dp, x + 4.5f * dp, yEdge + 17 * dp);
            cv.drawRoundRect(rect, 2 * dp, 2 * dp, paint);
        }

        // peau
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, rx, new int[]{0xFFF1E6CF, 0xFFD9C7A0, 0xFF9C8660},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        cv.drawOval(rect, paint);
        paint.setShader(null);
        if (amt > 0) {
            paint.setColor(alpha(0xFFFFC857, amt * 0.8f));
            float g = 0.55f + 0.4f * amt;
            rect.set(cx - rx * g, cy - ry * g, cx + rx * g, cy + ry * g);
            cv.drawOval(rect, paint);
        }
        // cercle noir
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.5f * dp);
        paint.setColor(0xFF141414);
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        cv.drawOval(rect, paint);
        paint.setStrokeWidth(1f * dp);
        paint.setColor(0xFF6A6A6A);
        rect.set(cx - rx + 3 * dp, cy - ry + 1.5f * dp, cx + rx - 3 * dp, cy + ry - 1.5f * dp);
        cv.drawOval(rect, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Main (avec son avant-bras) : bracelet bleu = main gauche, orange = main droite. */
    private void drawHand(Canvas cv, int s, float hx, float hy, float w, float h) {
        float ax = (s == 0 ? 0.10f : 0.90f) * w;
        float ay = h + 16 * dp;
        float dx = ax - hx, dy = ay - hy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1) len = 1;
        float ux = dx / len, uy = dy / len;   // du poignet vers le coude
        float nx = -uy, ny = ux;

        // avant-bras
        float wx = hx + ux * 12 * dp, wy = hy + uy * 12 * dp;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(17 * dp);
        paint.setColor(SKIN);
        cv.drawLine(wx, wy, ax, ay, paint);
        // bracelet
        paint.setStrokeWidth(3.5f * dp);
        paint.setColor(alpha(HAND_COLOR[s], 0.95f));
        float bx = wx + ux * 22 * dp, by = wy + uy * 22 * dp;
        cv.drawLine(bx - nx * 7.5f * dp, by - ny * 7.5f * dp, bx + nx * 7.5f * dp, by + ny * 7.5f * dp, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // paume
        float ang = (float) Math.toDegrees(Math.atan2(uy, ux));
        cv.save();
        cv.rotate(ang, hx, hy);
        rect.set(hx - 12 * dp, hy - 9.5f * dp, hx + 13 * dp, hy + 9.5f * dp);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SKIN);
        cv.drawOval(rect, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * dp);
        paint.setColor(SKIN_LINE);
        cv.drawOval(rect, paint);
        cv.restore();

        // doigts (côté opposé au bras)
        paint.setStyle(Paint.Style.FILL);
        for (int k = -2; k <= 1; k++) {
            float off = (k + 0.5f) * 5.2f * dp;
            float fx = hx - ux * 13 * dp + nx * off;
            float fy = hy - uy * 13 * dp + ny * off;
            paint.setColor(SKIN);
            cv.drawCircle(fx, fy, 3.3f * dp, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
