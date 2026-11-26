package com.stemnotes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Transcription audio -> notes (hauteur, début, fin, vélocité).
 * - Mode monophonique : détection de hauteur YIN (voix, basse, solo).
 * - Mode polyphonique : analyse spectrale (FFT) avec suppression des harmoniques (piano, accords).
 * Fonctionne mieux sur une piste déjà isolée.
 */
final class PitchTranscriber {

    static final int SAMPLE_RATE = 22050;

    static final class Note {
        final int pitch;
        final double start;
        final double end;
        final int velocity;

        Note(int pitch, double start, double end, int velocity) {
            this.pitch = pitch;
            this.start = start;
            this.end = end;
            this.velocity = velocity;
        }
    }

    interface Progress {
        void onProgress(int percent);
    }

    private PitchTranscriber() {}

    // ------------------------------------------------------------------ FFT
    private static final class Fft {
        final int n;
        final double[] cos;
        final double[] sin;
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
            for (int i = 0; i < n; i++) {
                rev[i] = Integer.reverse(i) >>> (32 - bits);
            }
        }

        /** Transformée en place, non normalisée. */
        void transform(double[] re, double[] im, boolean inverse) {
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
                        double wr = cos[k];
                        double wi = inverse ? sin[k] : -sin[k];
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

    // ------------------------------------------------------------ Monophonique
    static List<Note> transcribeMono(float[] x, int sr, Progress progress) {
        final int W = 1024;
        final int TAU_MAX = 600;
        final int N = 2048;
        final int HOP = 512;
        final int need = W + TAU_MAX;

        List<Note> notes = new ArrayList<>();
        if (x.length < need + HOP) return notes;
        int frames = (x.length - need) / HOP + 1;

        double[] rms = new double[frames];
        double maxRms = 0;
        for (int f = 0; f < frames; f++) {
            int s0 = f * HOP;
            double s = 0;
            for (int j = 0; j < W; j++) s += (double) x[s0 + j] * x[s0 + j];
            rms[f] = Math.sqrt(s / W);
            if (rms[f] > maxRms) maxRms = rms[f];
        }
        if (maxRms < 1e-4) return notes;
        double gate = Math.max(0.03 * maxRms, 1e-4);

        Fft fft = new Fft(N);
        double[] ar = new double[N];
        double[] ai = new double[N];
        double[] br = new double[N];
        double[] bi = new double[N];
        double[] e2 = new double[TAU_MAX + 1];
        double[] cm = new double[TAU_MAX + 1];
        int[] midi = new int[frames];
        Arrays.fill(midi, -1);
        int tauMin = Math.max(2, sr / 1200);

        for (int f = 0; f < frames; f++) {
            if (progress != null && f % 200 == 0) progress.onProgress(f * 100 / frames);
            if (rms[f] < gate) continue;
            int s0 = f * HOP;

            Arrays.fill(ar, 0);
            Arrays.fill(ai, 0);
            Arrays.fill(br, 0);
            Arrays.fill(bi, 0);
            for (int j = 0; j < W; j++) ar[j] = x[s0 + j];
            for (int j = 0; j < need; j++) br[j] = x[s0 + j];

            fft.transform(ar, ai, false);
            fft.transform(br, bi, false);
            for (int k = 0; k < N; k++) {
                double pr = ar[k] * br[k] + ai[k] * bi[k];
                double pim = ar[k] * bi[k] - ai[k] * br[k];
                ar[k] = pr;
                ai[k] = pim;
            }
            fft.transform(ar, ai, true);

            double e1 = 0;
            for (int j = 0; j < W; j++) e1 += (double) x[s0 + j] * x[s0 + j];
            e2[0] = e1;
            for (int tau = 0; tau < TAU_MAX; tau++) {
                double a = x[s0 + tau];
                double b = x[s0 + tau + W];
                e2[tau + 1] = e2[tau] - a * a + b * b;
            }

            double running = 0;
            cm[0] = 1;
            for (int tau = 1; tau <= TAU_MAX; tau++) {
                double c = ar[tau] / N;
                double d = e1 + e2[tau] - 2 * c;
                if (d < 0) d = 0;
                running += d;
                cm[tau] = running > 0 ? d * tau / running : 1;
            }

            int found = -1;
            for (int tau = tauMin; tau < TAU_MAX; tau++) {
                if (cm[tau] < 0.15) {
                    while (tau + 1 < TAU_MAX && cm[tau + 1] < cm[tau]) tau++;
                    found = tau;
                    break;
                }
            }
            if (found < 0) continue;

            double better = found;
            if (found > 1) {
                double a = cm[found - 1], b = cm[found], c2 = cm[found + 1];
                double den = a - 2 * b + c2;
                if (Math.abs(den) > 1e-12) better = found + (a - c2) / (2 * den);
            }
            double freq = sr / better;
            int m = (int) Math.round(69 + 12 * Math.log(freq / 440.0) / Math.log(2));
            if (m >= 28 && m <= 100) midi[f] = m;
        }

        // Lissage médian sur 5 trames
        int[] sm = new int[frames];
        int[] w = new int[5];
        for (int f = 0; f < frames; f++) {
            for (int k = -2; k <= 2; k++) {
                int idx = Math.min(frames - 1, Math.max(0, f + k));
                w[k + 2] = midi[idx];
            }
            Arrays.sort(w);
            sm[f] = w[2];
        }

        // Combler les petits trous entre deux trames de même hauteur
        for (int f = 1; f < frames - 1; f++) {
            if (sm[f] < 0 && sm[f - 1] >= 0) {
                int g = f;
                while (g < frames && sm[g] < 0) g++;
                if (g < frames && g - f <= 3 && sm[g] == sm[f - 1]) {
                    for (int k = f; k < g; k++) sm[k] = sm[f - 1];
                }
                f = g;
            }
        }

        // Segmentation en notes
        int f = 0;
        while (f < frames) {
            if (sm[f] < 0) { f++; continue; }
            int g = f;
            while (g < frames && sm[g] == sm[f]) g++;
            if (g - f >= 5) {
                double mean = 0;
                for (int k = f; k < g; k++) mean += rms[k];
                mean /= (g - f);
                int vel = (int) (40 + 80 * Math.min(1.0, mean / maxRms));
                notes.add(new Note(sm[f],
                        (f * (double) HOP + W / 2.0) / sr,
                        (g * (double) HOP + W / 2.0) / sr,
                        vel));
            }
            f = g;
        }
        if (progress != null) progress.onProgress(100);
        return notes;
    }

    // ------------------------------------------------------------ Polyphonique
    static List<Note> transcribePoly(float[] x, int sr, Progress progress) {
        final int N = 4096;
        final int HOP = 1024;
        final int LOW = 36;
        final int HIGH = 96;
        final int NN = HIGH - LOW + 1;
        final int MAXV = 5;
        final double[] HW = {1.0, 0.5, 0.33, 0.25};

        List<Note> notes = new ArrayList<>();
        if (x.length < N + HOP) return notes;
        int frames = (x.length - N) / HOP + 1;

        double[] rms = new double[frames];
        double maxRms = 0;
        for (int f = 0; f < frames; f++) {
            int s0 = f * HOP;
            double s = 0;
            for (int j = 0; j < N; j++) s += (double) x[s0 + j] * x[s0 + j];
            rms[f] = Math.sqrt(s / N);
            if (rms[f] > maxRms) maxRms = rms[f];
        }
        if (maxRms < 1e-4) return notes;
        double gate = Math.max(0.08 * maxRms, 1e-4);
        // Piste très faible (cas fréquent du piano séparé) : on la remet à un niveau standard
        final double norm = Math.min(200.0, 0.2 / maxRms);

        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);

        // Bins des harmoniques de chaque note
        int[][] bins = new int[NN][4];
        double[] freqs = new double[NN];
        for (int m = 0; m < NN; m++) {
            freqs[m] = 440.0 * Math.pow(2.0, (LOW + m - 69) / 12.0);
            for (int h = 0; h < 4; h++) {
                int b = (int) Math.round((h + 1) * freqs[m] * N / sr);
                bins[m][h] = (b >= 1 && b <= N / 2 - 2) ? b : -1;
            }
        }

        Fft fft = new Fft(N);
        double[] re = new double[N];
        double[] im = new double[N];
        double[] mag = new double[N / 2 + 1];
        boolean[][] on = new boolean[frames][NN];
        float[][] vel = new float[frames][NN];
        double maxSal = 1e-9;

        for (int f = 0; f < frames; f++) {
            if (progress != null && f % 100 == 0) progress.onProgress(f * 100 / frames);
            if (rms[f] < gate) continue;
            int s0 = f * HOP;
            for (int j = 0; j < N; j++) {
                re[j] = x[s0 + j] * win[j];
                im[j] = 0;
            }
            fft.transform(re, im, false);
            for (int k = 0; k <= N / 2; k++) {
                mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]) * 4.0 / N * norm;
            }

            double first = 0;
            for (int v = 0; v < MAXV; v++) {
                int bestM = -1;
                double bestS = 0;
                for (int m = 0; m < NN; m++) {
                    double s = 0;
                    double pk1 = 0;
                    double others = 0;
                    for (int h = 0; h < 4; h++) {
                        int b = bins[m][h];
                        if (b < 0) break;
                        double pk = Math.max(mag[b - 1], Math.max(mag[b], mag[b + 1]));
                        if (h == 0) pk1 = pk;
                        else if (pk > others) others = pk;
                        s += HW[h] * pk;
                    }
                    // La fondamentale doit être présente (évite les erreurs d'octave)
                    if (pk1 < 0.2 * others) continue;
                    if (s > bestS) { bestS = s; bestM = m; }
                }
                if (bestM < 0) break;
                if (v == 0) {
                    if (bestS < 0.004) break;
                    first = bestS;
                } else if (bestS < 0.3 * first) {
                    break;
                }
                on[f][bestM] = true;
                vel[f][bestM] = (float) bestS;
                if (bestS > maxSal) maxSal = bestS;

                for (int h = 1; h <= 8; h++) {
                    int b = (int) Math.round(h * freqs[bestM] * N / sr);
                    for (int k = b - 1; k <= b + 1; k++) {
                        if (k >= 0 && k < mag.length) mag[k] = 0;
                    }
                }
            }
        }

        // Nettoyage et segmentation par note
        for (int m = 0; m < NN; m++) {
            for (int f = 1; f < frames - 1; f++) {
                if (!on[f][m] && on[f - 1][m] && on[f + 1][m]) on[f][m] = true;
            }
            int f = 0;
            while (f < frames) {
                if (!on[f][m]) { f++; continue; }
                int g = f;
                float peak = 0;
                while (g < frames && on[g][m]) {
                    if (vel[g][m] > peak) peak = vel[g][m];
                    g++;
                }
                if (g - f >= 2) {
                    int v = (int) (40 + 80 * Math.min(1.0, peak / maxSal));
                    notes.add(new Note(LOW + m,
                            (f * (double) HOP + N / 2.0) / sr,
                            (g * (double) HOP + N / 2.0) / sr,
                            v));
                }
                f = g;
            }
        }
        Collections.sort(notes, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                int c = Double.compare(a.start, b.start);
                return c != 0 ? c : Integer.compare(a.pitch, b.pitch);
            }
        });
        if (progress != null) progress.onProgress(100);
        return notes;
    }

    // ---------------------------------------------------------------- Utilitaires
    // Noms des notes en solfège français
    private static final String[] NAMES =
            {"Do", "Do#", "Ré", "Ré#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"};

    private static final double[] KEY_MAJOR =
            {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] KEY_MINOR =
            {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /** Nom d'une note en solfège avec son octave, par ex. « Ré4 » ou « Fa#3 ». */
    static String noteName(int pitch) {
        return NAMES[pitch % 12] + (pitch / 12 - 1);
    }

    /** Tonalité estimée (algorithme de Krumhansl-Schmuckler), par ex. « Fa majeur » ou « Mi mineur ». */
    static String detectKey(List<Note> notes) {
        List<List<Note>> one = new ArrayList<>();
        one.add(notes);
        return detectKeyMulti(one);
    }

    /**
     * Une seule tonalité pour plusieurs instruments : chaque piste compte autant que les autres
     * (une piste bruitée ou très dense, comme un piano mal détecté, ne peut pas tirer la tonalité à elle seule).
     */
    static String detectKeyMulti(List<List<Note>> tracks) {
        double[] total = new double[12];
        int used = 0;
        for (List<Note> notes : tracks) {
            if (notes == null || notes.size() < 4) continue;
            double[] h = new double[12];
            double sum = 0;
            for (Note n : notes) {
                double w = Math.max(0.05, n.end - n.start) * (0.4 + 0.6 * n.velocity / 127.0);
                h[((n.pitch % 12) + 12) % 12] += w;
                sum += w;
            }
            if (sum <= 0) continue;
            for (int i = 0; i < 12; i++) total[i] += h[i] / sum;
            used++;
        }
        if (used == 0) return "indéterminée";
        double best = -2;
        int bestTonic = 0;
        boolean bestMajor = true;
        for (int t = 0; t < 12; t++) {
            for (int m = 0; m < 2; m++) {
                double[] prof = m == 0 ? KEY_MAJOR : KEY_MINOR;
                double r = correlation(total, prof, t);
                if (r > best) {
                    best = r;
                    bestTonic = t;
                    bestMajor = m == 0;
                }
            }
        }
        return NAMES[bestTonic] + (bestMajor ? " majeur" : " mineur");
    }

    /** Notes (0..11) appartenant à la gamme de la tonalité donnée (ex. « Fa majeur »), ou null si indéterminée. */
    static boolean[] keyScale(String key) {
        if (key == null) return null;
        int tonic = -1;
        int bestLen = 0;
        for (int i = 0; i < NAMES.length; i++) {
            if (key.startsWith(NAMES[i] + " ") && NAMES[i].length() > bestLen) {
                tonic = i;
                bestLen = NAMES[i].length();
            }
        }
        if (tonic < 0) return null;
        int[] steps = key.endsWith("mineur") ? new int[]{0, 2, 3, 5, 7, 8, 10} : new int[]{0, 2, 4, 5, 7, 9, 11};
        boolean[] sc = new boolean[12];
        for (int st : steps) sc[(tonic + st) % 12] = true;
        return sc;
    }

    /** Ramène chaque note hors tonalité sur la note de la gamme la plus proche (même tonalité pour tous les instruments). */
    static List<Note> snapToKey(List<Note> notes, String key) {
        boolean[] sc = keyScale(key);
        if (sc == null || notes == null) return notes;
        List<Note> out = new ArrayList<>(notes.size());
        for (Note n : notes) {
            int p = n.pitch;
            if (!sc[((p % 12) + 12) % 12]) {
                for (int d = 1; d <= 2; d++) {
                    if (sc[(((p - d) % 12) + 12) % 12]) { p = p - d; break; }
                    if (sc[(((p + d) % 12) + 12) % 12]) { p = p + d; break; }
                }
            }
            out.add(p == n.pitch ? n : new Note(p, n.start, n.end, n.velocity));
        }
        return out;
    }

    private static double correlation(double[] h, double[] prof, int tonic) {
        double mh = 0, mp = 0;
        for (int i = 0; i < 12; i++) {
            mh += h[i];
            mp += prof[i];
        }
        mh /= 12;
        mp /= 12;
        double num = 0, dh = 0, dp = 0;
        for (int i = 0; i < 12; i++) {
            double a = h[(i + tonic) % 12] - mh;
            double b = prof[i] - mp;
            num += a * b;
            dh += a * a;
            dp += b * b;
        }
        if (dh == 0 || dp == 0) return -2;
        return num / Math.sqrt(dh * dp);
    }
}
