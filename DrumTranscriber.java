package com.stemnotes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Détection des coups de batterie sur une piste « batterie » isolée.
 * On cherche les attaques dans quatre bandes de fréquences (grosse caisse, corps des toms/caisse claire,
 * claquement de la caisse claire, aigus des cymbales) puis on classe chaque coup :
 * grosse caisse, caisse claire, charleston, toms, crash, ride.
 * Les coups de mains graves/médiums (sans grosse caisse) sont attribués au Touba (congas) et non plus aux toms de la batterie :
 * {@link #kitOnly} renvoie la batterie seule, {@link #toubaOnly} renvoie le Touba seul.
 * Les notes renvoyées utilisent les numéros de la batterie General MIDI (36 = grosse caisse, 38 = caisse claire…,
 * 63 = conga aiguë, 64 = conga grave).
 */
final class DrumTranscriber {

    static final int KICK = 36;
    static final int SNARE = 38;
    static final int HIHAT = 42;
    static final int TOUBA_HIGH = 63; // Touba aigu (petite conga)
    static final int TOUBA_LOW = 64;  // Touba grave (grande conga)
    static final int TOM_HIGH = 50;
    static final int TOM_MID = 47;
    static final int TOM_FLOOR = 43;
    static final int CRASH = 49;
    static final int RIDE = 51;

    private static final int N = 1024;
    private static final int HOP = 256;
    private static final int BANDS = 4;
    private static final int B_KICK = 0, B_LOW = 1, B_SNARE = 2, B_HAT = 3;

    private DrumTranscriber() {}

    /** Vrai si la note appartient au Touba (et non à la batterie). */
    static boolean isTouba(int pitch) {
        return pitch >= 62 && pitch <= 64;
    }

    /** Les coups de la batterie seule (sans le Touba). */
    static List<PitchTranscriber.Note> kitOnly(List<PitchTranscriber.Note> all) {
        List<PitchTranscriber.Note> out = new ArrayList<>();
        for (PitchTranscriber.Note n : all) if (!isTouba(n.pitch)) out.add(n);
        return out;
    }

    /** Les coups du Touba seul. */
    static List<PitchTranscriber.Note> toubaOnly(List<PitchTranscriber.Note> all) {
        List<PitchTranscriber.Note> out = new ArrayList<>();
        for (PitchTranscriber.Note n : all) if (isTouba(n.pitch)) out.add(n);
        return out;
    }

    /** Nom français d'un numéro de batterie. */
    static String nameOf(int pitch) {
        switch (pitch) {
            case KICK: return "Grosse caisse";
            case SNARE: return "Caisse claire";
            case HIHAT: return "Charleston";
            case TOUBA_HIGH: return "Touba aigu";
            case TOUBA_LOW: return "Touba grave";
            case TOM_HIGH: return "Tom aigu";
            case TOM_MID: return "Tom médium";
            case TOM_FLOOR: return "Tom basse";
            case CRASH: return "Crash";
            case RIDE: return "Ride";
            default: return "Percussion";
        }
    }

    private static final class Fft {
        final int n;
        final double[] cos, sin;
        final int[] rev;

        Fft(int n) {
            this.n = n;
            cos = new double[n / 2];
            sin = new double[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = Math.cos(2 * Math.PI * i / n);
                sin[i] = Math.sin(2 * Math.PI * i / n);
            }
            rev = new int[n];
            int bits = Integer.numberOfTrailingZeros(n);
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        }

        void transform(double[] re, double[] im) {
            for (int i = 0; i < n; i++) {
                int j = rev[i];
                if (j > i) {
                    double t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >> 1;
                int step = n / size;
                for (int i = 0; i < n; i += size) {
                    for (int j = i, k = 0; j < i + half; j++, k += step) {
                        double wr = cos[k], wi = -sin[k];
                        int l = j + half;
                        double tr = re[l] * wr - im[l] * wi;
                        double ti = re[l] * wi + im[l] * wr;
                        re[l] = re[j] - tr;
                        im[l] = im[j] - ti;
                        re[j] += tr;
                        im[j] += ti;
                    }
                }
            }
        }
    }

    private static final class Onset {
        final int frame;
        final int band;
        final double flux;

        Onset(int frame, int band, double flux) {
            this.frame = frame;
            this.band = band;
            this.flux = flux;
        }
    }

    private static int bin(double hz, int sr) {
        return (int) Math.round(hz * N / sr);
    }

    static List<PitchTranscriber.Note> transcribe(float[] x, int sr, PitchTranscriber.Progress progress) {
        List<PitchTranscriber.Note> out = new ArrayList<>();
        if (x == null || x.length < N * 2) return out;
        int frames = (x.length - N) / HOP;
        Fft fft = new Fft(N);
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));

        int[] lo = {bin(40, sr), bin(110, sr), bin(1500, sr), bin(6500, sr)};
        int[] hi = {bin(110, sr), bin(420, sr), bin(5000, sr), Math.min(N / 2 - 1, bin(11000, sr))};

        double[][] e = new double[BANDS][frames];
        double[] re = new double[N];
        double[] im = new double[N];
        for (int t = 0; t < frames; t++) {
            int off = t * HOP;
            for (int i = 0; i < N; i++) {
                re[i] = x[off + i] * win[i];
                im[i] = 0;
            }
            fft.transform(re, im);
            for (int b = 0; b < BANDS; b++) {
                double s = 0;
                for (int k = lo[b]; k < hi[b]; k++) s += Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                e[b][t] = s;
            }
            if (progress != null && t % 400 == 0) progress.onProgress((int) (70.0 * t / frames));
        }

        // enveloppe compressée (log) puis flux positif
        double[][] v = new double[BANDS][frames];
        double[][] flux = new double[BANDS][frames];
        for (int b = 0; b < BANDS; b++) {
            double mx = 1e-9;
            for (int t = 0; t < frames; t++) mx = Math.max(mx, e[b][t]);
            for (int t = 0; t < frames; t++) v[b][t] = Math.log(1 + 10 * e[b][t] / mx);
            for (int t = 2; t < frames; t++) {
                double f = v[b][t] - Math.max(v[b][t - 1], v[b][t - 2]);
                flux[b][t] = f > 0 ? f : 0;
            }
        }

        // pics d'attaque par bande
        List<Onset> onsets = new ArrayList<>();
        double[] p90 = new double[BANDS];
        int[] minGap = {7, 5, 5, 4};
        double[] floor = {0.10, 0.10, 0.10, 0.10};
        for (int b = 0; b < BANDS; b++) {
            double[] pre = new double[frames + 1];
            for (int t = 0; t < frames; t++) pre[t + 1] = pre[t] + flux[b][t];
            int w = 40;
            int last = -100;
            List<Double> fl = new ArrayList<>();
            for (int t = 3; t < frames - 3; t++) {
                int a = Math.max(0, t - w), c = Math.min(frames, t + w + 1);
                double mean = (pre[c] - pre[a]) / (c - a);
                double thr = 2.2 * mean + floor[b];
                double f = flux[b][t];
                if (f > thr && f >= flux[b][t - 1] && f >= flux[b][t + 1] && t - last >= minGap[b]) {
                    onsets.add(new Onset(t, b, f));
                    fl.add(f);
                    last = t;
                }
            }
            if (!fl.isEmpty()) {
                Collections.sort(fl);
                p90[b] = Math.max(0.2, fl.get((int) (fl.size() * 0.9) % fl.size()));
            } else {
                p90[b] = 0.5;
            }
        }
        Collections.sort(onsets, new Comparator<Onset>() {
            @Override public int compare(Onset a, Onset b) {
                return a.frame != b.frame ? Integer.compare(a.frame, b.frame) : Integer.compare(a.band, b.band);
            }
        });
        if (progress != null) progress.onProgress(80);

        // regroupement des attaques simultanées (≈ 25 ms)
        int i = 0;
        while (i < onsets.size()) {
            int f0 = onsets.get(i).frame;
            double[] fx = new double[BANDS];
            boolean[] on = new boolean[BANDS];
            int j = i;
            while (j < onsets.size() && onsets.get(j).frame - f0 <= 2) {
                Onset o = onsets.get(j);
                on[o.band] = true;
                fx[o.band] = Math.max(fx[o.band], o.flux / p90[o.band]);
                j++;
            }
            classify(out, x, sr, fft, win, e, v, f0, on, fx, frames, lo, hi);
            i = j;
        }

        Collections.sort(out, new Comparator<PitchTranscriber.Note>() {
            @Override public int compare(PitchTranscriber.Note a, PitchTranscriber.Note b) {
                return Double.compare(a.start, b.start);
            }
        });
        if (progress != null) progress.onProgress(100);
        return out;
    }

    private static void classify(List<PitchTranscriber.Note> out, float[] x, int sr, Fft fft, double[] win,
                                 double[][] e, double[][] v, int f0, boolean[] on, double[] fx, int frames,
                                 int[] lo, int[] hi) {
        double time = (f0 * (double) HOP + N * 0.35) / sr;
        boolean snareHit = false;

        if (on[B_KICK] && fx[B_KICK] >= 0.4) add(out, KICK, time, 0.10, fx[B_KICK]);

        // forme du spectre au moment du coup : la caisse claire a plus d'énergie dans les médiums-aigus
        // (1,5 à 5 kHz) que le charleston, dont l'énergie est surtout au-dessus de 6,5 kHz
        double midSum = 0, topSum = 0;
        for (int t = f0 + 1; t <= f0 + 3 && t < frames; t++) {
            midSum += e[B_SNARE][t];
            topSum += e[B_HAT][t];
        }
        double shape = topSum > 0 ? midSum / topSum : 0;


        if (on[B_SNARE] && fx[B_SNARE] >= 0.5 && shape >= 0.55) {
            add(out, SNARE, time, 0.12, fx[B_SNARE]);
            snareHit = true;
        } else if (on[B_LOW] && fx[B_LOW] >= 0.5 && !on[B_KICK]) {
            // coup de main sur une peau grave/médium : c'est le Touba (aigu ou grave), pas un tom de batterie
            int touba = toubaPitch(x, sr, fft, win, f0, frames, lo[B_LOW], hi[B_LOW]);
            add(out, touba, time, 0.18, fx[B_LOW]);
        }

        if (on[B_HAT] && fx[B_HAT] >= 0.3 && !(snareHit && fx[B_HAT] < 0.8 * fx[B_SNARE])) {
            double peak = 0;
            for (int t = f0; t < Math.min(frames, f0 + 4); t++) peak = Math.max(peak, e[B_HAT][t]);
            // le creux le plus bas entre 60 et 140 ms : une cymbale qui résonne ne retombe pas
            double dip = Double.MAX_VALUE;
            for (int t = f0 + 5; t < Math.min(frames, f0 + 13); t++) dip = Math.min(dip, e[B_HAT][t]);
            double decay = (dip < Double.MAX_VALUE && peak > 0) ? dip / peak : 0;
            if (decay > 0.45 && fx[B_HAT] > 0.9) add(out, CRASH, time, 0.5, fx[B_HAT]);
            else if (decay > 0.30) add(out, RIDE, time, 0.3, fx[B_HAT]);
            else add(out, HIHAT, time, 0.06, fx[B_HAT]);
        }
    }

    private static int toubaPitch(float[] x, int sr, Fft fft, double[] win, int f0, int frames, int lo, int hi) {
        double[] sum = new double[hi - lo + 1];
        double[] re = new double[N];
        double[] im = new double[N];
        for (int t = f0 + 1; t <= f0 + 3 && t < frames; t++) {
            int off = t * HOP;
            for (int i = 0; i < N; i++) {
                re[i] = x[off + i] * win[i];
                im[i] = 0;
            }
            fft.transform(re, im);
            for (int k = lo; k <= hi; k++) sum[k - lo] += Math.sqrt(re[k] * re[k] + im[k] * im[k]);
        }
        int best = 0;
        for (int k = 1; k < sum.length; k++) if (sum[k] > sum[best]) best = k;
        double hz = (best + lo) * (double) sr / N;
        return hz >= 215 ? TOUBA_HIGH : TOUBA_LOW;
    }

    private static void add(List<PitchTranscriber.Note> out, int pitch, double time, double len, double strength) {
        int vel = (int) Math.round(45 + 82 * Math.min(1.0, strength));
        out.add(new PitchTranscriber.Note(pitch, time, time + len, Math.max(1, Math.min(127, vel))));
    }
}
