package com.stemnotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Défilé des notes : les notes arrivent de la droite et passent sur la barre de lecture.
 * Chaque instrument a sa couleur ; les lignes teintées sont les notes de la tonalité du morceau
 * (la même pour tous les instruments).
 */
final class NoteRollView extends View {

    static final class Track {
        final String name;
        final int color;
        final PitchTranscriber.Note[] notes; // triées par début
        double maxLen;

        Track(String name, int color, List<PitchTranscriber.Note> src) {
            this.name = name;
            this.color = color;
            this.notes = src.toArray(new PitchTranscriber.Note[0]);
            Arrays.sort(this.notes, new Comparator<PitchTranscriber.Note>() {
                @Override public int compare(PitchTranscriber.Note a, PitchTranscriber.Note b) {
                    return Double.compare(a.start, b.start);
                }
            });
            for (PitchTranscriber.Note n : this.notes) maxLen = Math.max(maxLen, n.end - n.start);
        }
    }

    private static final double PAST = 2.0;    // secondes visibles avant la barre
    private static final double FUTURE = 6.0;  // secondes visibles après la barre

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;

    private List<Track> tracks = new ArrayList<>();
    private boolean[] scale;
    private int lo = 48;
    private int hi = 72;
    private double time;

    NoteRollView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextSize(9 * dp);
    }

    void setTracks(List<Track> t, boolean[] scale) {
        this.tracks = t;
        this.scale = scale;
        List<Integer> ps = new ArrayList<>();
        for (Track tr : t) for (PitchTranscriber.Note n : tr.notes) ps.add(n.pitch);
        if (ps.isEmpty()) {
            lo = 48;
            hi = 72;
        } else {
            java.util.Collections.sort(ps);
            int a = ps.get(0);
            int b = ps.get(ps.size() - 1);
            if (b - a > 36) { // on ignore les rares notes aberrantes
                a = ps.get((int) (ps.size() * 0.03));
                b = ps.get((int) ((ps.size() - 1) * 0.97));
            }
            lo = a - 1;
            hi = Math.max(b + 1, lo + 12);
        }
        invalidate();
    }

    void setTime(double sec) {
        time = sec;
        invalidate();
    }

    /** Notes qui sonnent à l'instant t, par ex. « Guitare Ré4 · Voix La3 ». */
    String soundingAt(double t) {
        StringBuilder sb = new StringBuilder();
        for (Track tr : tracks) {
            for (int i = firstIndex(tr, t - tr.maxLen); i < tr.notes.length; i++) {
                PitchTranscriber.Note n = tr.notes[i];
                if (n.start > t) break;
                if (n.end > t) {
                    if (sb.length() > 0) sb.append("  ·  ");
                    sb.append(tr.name).append(' ').append(PitchTranscriber.noteName(n.pitch));
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static int firstIndex(Track tr, double start) {
        int a = 0, b = tr.notes.length;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (tr.notes[m].start < start) a = m + 1; else b = m;
        }
        return a;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float left = 30 * dp;
        float rows = hi - lo + 1;
        float rowH = h / rows;
        float pxPerSec = (w - left) / (float) (PAST + FUTURE);
        float headX = left + (float) PAST * pxPerSec;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        cv.drawRect(0, 0, w, h, paint);

        // lignes de la gamme
        for (int p = lo; p <= hi; p++) {
            float y = h - (p - lo + 1) * rowH;
            boolean inKey = scale != null && scale[((p % 12) + 12) % 12];
            if (inKey) {
                paint.setColor(0x33FFD24D);
                cv.drawRect(left, y, w, y + rowH, paint);
            }
            if (p % 12 == 0) {
                paint.setColor(0x33000000);
                cv.drawRect(left, y + rowH - 0.5f * dp, w, y + rowH, paint);
                text.setColor(0xFF777777);
                cv.drawText(PitchTranscriber.noteName(p), 2 * dp, y + rowH - 1 * dp, text);
            }
        }

        // notes
        double t0 = time - PAST;
        double t1 = time + FUTURE;
        for (Track tr : tracks) {
            for (int i = firstIndex(tr, t0 - tr.maxLen); i < tr.notes.length; i++) {
                PitchTranscriber.Note n = tr.notes[i];
                if (n.start > t1) break;
                if (n.end < t0 || n.pitch < lo || n.pitch > hi) continue;
                float x0 = Math.max(left, headX + (float) (n.start - time) * pxPerSec);
                float x1 = headX + (float) (n.end - time) * pxPerSec;
                if (x1 <= left) continue;
                float y = h - (n.pitch - lo + 1) * rowH;
                rect.set(x0, y + 1, Math.max(x1, x0 + 3 * dp), y + rowH - 1);
                boolean now = n.start <= time && n.end > time;
                boolean past = n.end <= time;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(tr.color);
                paint.setAlpha(past ? 90 : 255);
                cv.drawRoundRect(rect, 3 * dp, 3 * dp, paint);
                if (now) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.5f * dp);
                    paint.setColor(0xFF000000);
                    cv.drawRoundRect(rect, 3 * dp, 3 * dp, paint);
                }
            }
        }
        paint.setAlpha(255);

        // marge gauche (noms des Do) par-dessus les notes qui sortent
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFF6DC);
        cv.drawRect(0, 0, left, h, paint);
        for (int p = lo; p <= hi; p++) {
            if (p % 12 == 0) {
                float y = h - (p - lo + 1) * rowH;
                text.setColor(0xFF555555);
                cv.drawText(PitchTranscriber.noteName(p), 2 * dp, y + rowH - 1 * dp, text);
            }
        }

        // barre de lecture
        paint.setColor(0xFFE8590C);
        cv.drawRect(headX - 1 * dp, 0, headX + 1 * dp, h, paint);
    }
}
