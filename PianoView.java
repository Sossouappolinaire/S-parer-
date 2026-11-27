package com.stemnotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Clavier de piano avec ses deux mains.
 * - Main gauche (bleue) : joue les accords ; son nom et ses notes s'affichent en haut (ex. « Do · Mi · Sol »).
 * - Main droite (orange) : joue la mélodie ou accompagne avec des accords ; l'indication change en direct.
 * Chaque main a un doigt par note qui sonne (jusqu'à 5) et se prépare au-dessus de la prochaine note.
 * Les notes sont réparties entre les deux mains d'après leur hauteur et leur écart.
 */
final class PianoView extends View {

    private static final int[] WHITE_INDEX = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
    private static final boolean[] BLACK = {false, true, false, true, false, false, true, false, true, false, true, false};
    private static final int MAX_FINGERS = 5;
    private static final double MIN_PRESS = 0.08; // une note très courte reste visible au moins 80 ms
    private static final double CLUSTER = 0.08;   // notes jouées « ensemble »
    private static final int LEFT_COLOR = 0xFF1C7ED6;
    private static final int RIGHT_COLOR = 0xFFE8590C;
    private static final int SKIN = 0xFFF0C09A;
    private static final int SKIN_LINE = 0xFF9C6B4B;

    private final PitchTranscriber.Note[] notes;
    private final PitchTranscriber.Note[] lh;
    private final PitchTranscriber.Note[] rh;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] pressedL = new int[MAX_FINGERS];
    private final int[] pressedR = new int[MAX_FINGERS];
    private final int[] chordBuf = new int[MAX_FINGERS + 4];
    private final float[] fx = new float[MAX_FINGERS];
    private final float[] ft = new float[MAX_FINGERS];
    private final float[] fl = new float[MAX_FINGERS];
    private double maxLenL, maxLenR;
    private double time;
    private int lo = 48;   // premier Do affiché
    private int hi = 83;   // dernier Si affiché
    private int whites = 21;

    // accords (main gauche, ou main droite si le piano ne joue presque rien à gauche)
    private double[] chStart = new double[0];
    private double[] chUntil = new double[0];
    private ChordNamer.Chord[] chords = new ChordNamer.Chord[0];
    private boolean chordsFromRight = false;
    // groupes de notes de la main droite (taille = nombre de notes jouées ensemble)
    private double[] rcStart = new double[0];
    private int[] rcSize = new int[0];

    PianoView(Context c, NoteRollView.Track tr) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        notes = tr.notes;
        text.setTextAlign(Paint.Align.LEFT);
        List<PitchTranscriber.Note> l = new ArrayList<>();
        List<PitchTranscriber.Note> r = new ArrayList<>();
        splitHands(l, r);
        lh = l.toArray(new PitchTranscriber.Note[0]);
        rh = r.toArray(new PitchTranscriber.Note[0]);
        for (PitchTranscriber.Note n : lh) maxLenL = Math.max(maxLenL, n.end - n.start);
        for (PitchTranscriber.Note n : rh) maxLenR = Math.max(maxLenR, n.end - n.start);
        maxLenL += MIN_PRESS;
        maxLenR += MIN_PRESS;
        buildChords();
        buildRightClusters();
        computeRange();
    }

    void setTime(double sec) {
        time = sec;
        invalidate();
    }

    // ------------------------------------------------------------ répartition des mains
    private void splitHands(List<PitchTranscriber.Note> l, List<PitchTranscriber.Note> r) {
        double lastL = 48, lastR = 72;
        int i = 0;
        while (i < notes.length) {
            int j = i + 1;
            while (j < notes.length && notes[j].start - notes[i].start <= CLUSTER) j++;
            PitchTranscriber.Note[] c = Arrays.copyOfRange(notes, i, j);
            Arrays.sort(c, new Comparator<PitchTranscriber.Note>() {
                @Override public int compare(PitchTranscriber.Note a, PitchTranscriber.Note b) {
                    return Integer.compare(a.pitch, b.pitch);
                }
            });
            int cut = -1, bestGap = 0;
            for (int k = 0; k + 1 < c.length; k++) {
                int gap = c[k + 1].pitch - c[k].pitch;
                if (gap >= 5 && gap > bestGap && c[k].pitch <= 67 && c[k + 1].pitch >= 53) {
                    bestGap = gap;
                    cut = k;
                }
            }
            double sumL = 0, sumR = 0;
            int nl = 0, nr = 0;
            if (cut >= 0) {
                for (int k = 0; k < c.length; k++) {
                    if (k <= cut) { l.add(c[k]); sumL += c[k].pitch; nl++; }
                    else { r.add(c[k]); sumR += c[k].pitch; nr++; }
                }
            } else {
                double avg = 0;
                for (PitchTranscriber.Note n : c) avg += n.pitch;
                avg /= c.length;
                boolean left;
                if (avg >= 62) left = false;
                else if (avg < 56) left = true;
                else left = Math.abs(avg - lastL) < Math.abs(avg - lastR);
                for (PitchTranscriber.Note n : c) {
                    if (left) { l.add(n); sumL += n.pitch; nl++; }
                    else { r.add(n); sumR += n.pitch; nr++; }
                }
            }
            if (nl > 0) lastL = sumL / nl;
            if (nr > 0) lastR = sumR / nr;
            i = j;
        }
        Comparator<PitchTranscriber.Note> byStart = new Comparator<PitchTranscriber.Note>() {
            @Override public int compare(PitchTranscriber.Note a, PitchTranscriber.Note b) {
                return Double.compare(a.start, b.start);
            }
        };
        Collections.sort(l, byStart);
        Collections.sort(r, byStart);
    }

    // ------------------------------------------------------------ accords
    private void buildChords() {
        List<double[]> times = new ArrayList<>();
        List<ChordNamer.Chord> list = new ArrayList<>();
        collectChords(lh, maxLenL, 1, times, list);
        chordsFromRight = false;
        if (list.isEmpty()) {
            collectChords(rh, maxLenR, 3, times, list);
            chordsFromRight = !list.isEmpty();
        }
        chStart = new double[list.size()];
        chUntil = new double[list.size()];
        chords = list.toArray(new ChordNamer.Chord[0]);
        for (int k = 0; k < chords.length; k++) {
            chStart[k] = times.get(k)[0];
            chUntil[k] = times.get(k)[1];
        }
    }

    private void collectChords(PitchTranscriber.Note[] arr, double maxLen, int minSize,
                               List<double[]> times, List<ChordNamer.Chord> out) {
        int i = 0;
        while (i < arr.length) {
            int j = i + 1;
            while (j < arr.length && arr[j].start - arr[i].start <= CLUSTER) j++;
            double t0 = arr[i].start;
            double until = 0;
            int n = 0;
            for (int k = i; k < j && n < chordBuf.length; k++) {
                chordBuf[n++] = arr[k].pitch;
                until = Math.max(until, arr[k].end);
            }
            // notes déjà commencées et encore tenues
            for (int k = i - 1; k >= 0 && arr[k].start >= t0 - maxLen && n < chordBuf.length; k--) {
                if (arr[k].end > t0 + 0.03) {
                    chordBuf[n++] = arr[k].pitch;
                    until = Math.max(until, arr[k].end);
                }
            }
            ChordNamer.Chord ch = ChordNamer.name(chordBuf, n);
            if (ch != null && ch.size >= minSize) {
                times.add(new double[]{t0, until + 0.5});
                out.add(ch);
            }
            i = j;
        }
    }

    private ChordNamer.Chord chordAt(double t) {
        int a = 0, b = chStart.length;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (chStart[m] <= t) a = m + 1; else b = m;
        }
        int idx = a - 1;
        if (idx < 0 || t > chUntil[idx]) return null;
        return chords[idx];
    }

    private void buildRightClusters() {
        List<double[]> list = new ArrayList<>();
        int i = 0;
        while (i < rh.length) {
            int j = i + 1;
            while (j < rh.length && rh[j].start - rh[i].start <= CLUSTER) j++;
            list.add(new double[]{rh[i].start, j - i});
            i = j;
        }
        rcStart = new double[list.size()];
        rcSize = new int[list.size()];
        for (int k = 0; k < rcStart.length; k++) {
            rcStart[k] = list.get(k)[0];
            rcSize[k] = (int) list.get(k)[1];
        }
    }

    /** « mélodie » (notes une par une) ou « accompagnement » (accords), d'après les 2 dernières secondes. */
    private String rightMode(double t) {
        int a = 0, b = rcStart.length;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (rcStart[m] < t - 2.0) a = m + 1; else b = m;
        }
        int big = 0, total = 0;
        for (int k = a; k < rcStart.length && rcStart[k] <= t + 0.2; k++) {
            total++;
            if (rcSize[k] >= 3) big++;
        }
        if (total == 0) return "repos";
        return (big > 0 && big * 2 >= total) ? "accompagnement" : "mélodie";
    }

    // ------------------------------------------------------------ clavier
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

    private static int firstIndex(PitchTranscriber.Note[] arr, double start) {
        int a = 0, b = arr.length;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (arr[m].start < start) a = m + 1; else b = m;
        }
        return a;
    }

    private boolean inRange(int p) {
        return p >= lo && p <= hi;
    }

    private int collectPressed(PitchTranscriber.Note[] arr, double maxLen, int[] out) {
        int n = 0;
        for (int i = firstIndex(arr, time - maxLen); i < arr.length && n < MAX_FINGERS; i++) {
            PitchTranscriber.Note x = arr[i];
            if (x.start > time) break;
            double end = Math.max(x.end, x.start + MIN_PRESS);
            if (end > time && inRange(x.pitch)) out[n++] = x.pitch;
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

    // ------------------------------------------------------------ dessin
    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float strip = 36 * dp;
        float ky = h * 0.44f;
        float kh = h - ky - 1 * dp;
        float kw = w / whites;
        float bw = kw * 0.6f;
        float bh = kh * 0.62f;
        int nl = collectPressed(lh, maxLenL, pressedL);
        int nr = collectPressed(rh, maxLenR, pressedR);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A2623);
        cv.drawRect(0, 0, w, h, paint);
        paint.setColor(0xFF222222);
        cv.drawRect(0, ky - 3 * dp, w, h, paint);

        // touches blanches
        text.setTextSize(8 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(false);
        for (int p = lo; p <= hi; p++) {
            if (BLACK[p % 12]) continue;
            float x0 = whiteIdx(p) * kw;
            boolean onL = has(pressedL, nl, p);
            boolean onR = has(pressedR, nr, p);
            boolean on = onL || onR;
            int c = onL ? LEFT_COLOR : RIGHT_COLOR;
            rect.set(x0 + 0.6f * dp, ky + (on ? 2 * dp : 0), x0 + kw - 0.6f * dp, ky + kh);
            paint.setColor(on ? blend(c, 0xFFFFFFFF, 0.25f) : 0xFFFFFFFF);
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
            boolean onL = has(pressedL, nl, p);
            boolean onR = has(pressedR, nr, p);
            boolean on = onL || onR;
            float bottom = ky + bh - (on ? 3 * dp : 0);
            rect.set(cx - bw / 2, ky, cx + bw / 2, bottom);
            paint.setColor(on ? (onL ? LEFT_COLOR : RIGHT_COLOR) : 0xFF1A1A1A);
            cv.drawRoundRect(rect, 2 * dp, 2 * dp, paint);
            if (!on) {
                paint.setColor(0xFF3A3A3A);
                cv.drawRect(cx - bw / 2 + 1 * dp, bottom - 5 * dp, cx + bw / 2 - 1 * dp, bottom - 1 * dp, paint);
            }
        }

        // les deux mains (dessinées sous la bande de texte)
        cv.save();
        cv.clipRect(0, strip, w, h);
        drawHand(cv, lh, maxLenL, pressedL, nl, kw, ky, kh, bh, LEFT_COLOR, "G");
        drawHand(cv, rh, maxLenR, pressedR, nr, kw, ky, kh, bh, RIGHT_COLOR, "D");
        cv.restore();

        drawStrip(cv, w, strip, nl, nr);
    }

    private void drawStrip(Canvas cv, float w, float strip, int nl, int nr) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1B1917);
        cv.drawRect(0, 0, w, strip, paint);
        float half = w / 2 - 12 * dp;

        ChordNamer.Chord ch = chordAt(time);
        String lTitle = (chordsFromRight ? "Accords (main droite) : " : "Main gauche : ")
                + (ch != null ? ch.name : "—");
        String lNotes = ch != null ? ch.notes : "";
        text.setTextAlign(Paint.Align.LEFT);
        drawLine(cv, lTitle, 8 * dp, 14 * dp, half, 11, LEFT_COLOR, true);
        drawLine(cv, lNotes, 8 * dp, 28 * dp, half, 10, 0xFFE8E2D4, false);

        String rTitle = "Main droite : " + rightMode(time);
        StringBuilder rn = new StringBuilder();
        int[] sorted = Arrays.copyOf(pressedR, nr);
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            if (i > 0) rn.append(" · ");
            rn.append(ChordNamer.noteFr(sorted[i]));
        }
        text.setTextAlign(Paint.Align.RIGHT);
        drawLine(cv, rTitle, w - 8 * dp, 14 * dp, half, 11, RIGHT_COLOR, true);
        drawLine(cv, rn.toString(), w - 8 * dp, 28 * dp, half, 10, 0xFFE8E2D4, false);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLine(Canvas cv, String s, float x, float y, float maxW, float sp, int color, boolean bold) {
        if (s.isEmpty()) return;
        float size = sp * dp;
        text.setFakeBoldText(bold);
        text.setTextSize(size);
        while (text.measureText(s) > maxW && size > 7 * dp) {
            size -= 0.5f * dp;
            text.setTextSize(size);
        }
        text.setColor(color);
        cv.drawText(s, x, y, text);
    }

    /** Une main : un doigt par note qui sonne, sinon un doigt qui se prépare au-dessus de la prochaine note. */
    private void drawHand(Canvas cv, PitchTranscriber.Note[] arr, double maxLen, int[] pr, int np,
                          float kw, float ky, float kh, float bh, int hand, String letter) {
        if (arr.length == 0) return;
        float tipWhite = ky + bh + (kh - bh) * 0.45f;
        float tipBlack = ky + bh * 0.5f;

        if (np > 0) {
            for (int i = 0; i < np; i++) {
                int p = pr[i];
                fx[i] = keyX(p, kw);
                ft[i] = BLACK[p % 12] ? tipBlack : tipWhite;
                fl[i] = 0f;
            }
            drawHandShape(cv, np, 1f, true, kw, hand, letter);
            return;
        }

        int idx = firstIndex(arr, time + 1e-9);
        PitchTranscriber.Note next = null;
        for (int i = idx; i < arr.length; i++) {
            if (inRange(arr[i].pitch)) { next = arr[i]; break; }
        }
        PitchTranscriber.Note prev = null;
        for (int i = idx - 1; i >= 0 && i > idx - 300; i--) {
            if (inRange(arr[i].pitch)) { prev = arr[i]; break; }
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
            fx[0] = x; ft[0] = tip; fl[0] = (float) lift;
            drawHandShape(cv, 1, 0.85f, false, kw, hand, letter);
        } else if (prev != null) {
            double endP = Math.max(prev.end, prev.start + MIN_PRESS);
            if (time - endP < 1.0) {
                fx[0] = keyX(prev.pitch, kw);
                ft[0] = BLACK[prev.pitch % 12] ? tipBlack : tipWhite;
                fl[0] = (float) clamp((time - endP) / 0.12);
                drawHandShape(cv, 1, 0.85f, false, kw, hand, letter);
            }
        }
    }

    /** Dessine le bras, la paume et les doigts à partir de fx / ft / fl (n doigts). */
    private void drawHandShape(Canvas cv, int n, float a, boolean press, float kw, int hand, String letter) {
        float fw = Math.max(6 * dp, Math.min(kw * 0.8f, 13 * dp));
        float finger = 62 * dp;
        float minX = 1e9f, maxX = -1e9f, minTop = 1e9f;
        for (int i = 0; i < n; i++) {
            float tip = ft[i] - fl[i] * 28 * dp;
            minX = Math.min(minX, fx[i]);
            maxX = Math.max(maxX, fx[i]);
            minTop = Math.min(minTop, tip - finger);
        }
        float palmL = minX - fw * 0.9f;
        float palmR = maxX + fw * 0.9f;
        float palmTop = minTop - 26 * dp;
        float palmBot = minTop + 14 * dp;
        float cx = (minX + maxX) / 2f;
        float armHalf = Math.max((palmR - palmL) * 0.35f, 11 * dp);

        // bras
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(blend(SKIN, hand, 0.12f), a));
        rect.set(cx - armHalf, 0, cx + armHalf, palmTop + 10 * dp);
        cv.drawRect(rect, paint);
        // paume
        rect.set(palmL, palmTop, palmR, palmBot);
        paint.setColor(alpha(SKIN, a));
        cv.drawRoundRect(rect, 9 * dp, 9 * dp, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * dp);
        paint.setColor(alpha(SKIN_LINE, a));
        cv.drawRoundRect(rect, 9 * dp, 9 * dp, paint);

        // petit bracelet de couleur : bleu = main gauche, orange = main droite
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(hand, a));
        rect.set(cx - armHalf, palmTop - 6 * dp, cx + armHalf, palmTop);
        cv.drawRect(rect, paint);

        // lettre G / D sur la paume
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(10 * dp);
        text.setColor(alpha(0xFF6B4630, a));
        cv.drawText(letter, cx, palmTop + 14 * dp, text);

        // doigts
        for (int i = 0; i < n; i++) {
            float tip = ft[i] - fl[i] * 28 * dp;
            float top = tip - finger;
            if (press) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(alpha(hand, 0.35f));
                cv.drawCircle(fx[i], tip + fw * 0.2f, fw * 0.95f, paint);
            }
            rect.set(fx[i] - fw / 2, top, fx[i] + fw / 2, tip + fw * 0.35f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alpha(SKIN, a));
            cv.drawRoundRect(rect, fw / 2, fw / 2, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f * dp);
            paint.setColor(alpha(SKIN_LINE, a));
            cv.drawRoundRect(rect, fw / 2, fw / 2, paint);
            paint.setStrokeWidth(0.8f * dp);
            paint.setColor(alpha(SKIN_LINE, a * 0.6f));
            cv.drawLine(fx[i] - fw * 0.28f, tip - 16 * dp, fx[i] + fw * 0.28f, tip - 16 * dp, paint);
            cv.drawLine(fx[i] - fw * 0.28f, tip - 30 * dp, fx[i] + fw * 0.28f, tip - 30 * dp, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        text.setFakeBoldText(false);
    }
}
