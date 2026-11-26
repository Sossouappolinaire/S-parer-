package com.stemnotes;

import android.content.Context;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transcription des notes avec Basic Pitch (Spotify, licence Apache-2.0), exécutée sur le téléphone.
 * Le modèle (~0,8 Mo) est téléchargé une seule fois, puis gardé en local.
 * En cas d'échec, l'appelant bascule sur PitchTranscriber (détection intégrée).
 */
final class BasicPitchTranscriber {

    private static final String MODEL_URL =
            "https://huggingface.co/litert-community/Basic-Pitch-LiteRT/resolve/main/basicpitch.tflite";
    private static final String MODEL_FILE = "basicpitch.tflite";

    private static final int WIN = 43844;          // 2 s à 22 050 Hz
    private static final int OVERLAP = 7680;
    private static final int HOP = WIN - OVERLAP;
    private static final int FRAMES = 172;
    private static final int KEEP_FROM = OVERLAP / 256 / 2;   // 15
    private static final int KEEP_TO = FRAMES - KEEP_FROM;     // 157
    private static final int FRAME_HOP = 256;
    private static final int BINS = 88;
    private static final int MIDI_LOW = 21;
    private static final double SR = 22050.0;

    private BasicPitchTranscriber() {}

    static File ensureModel(Context ctx) throws IOException {
        File f = new File(ctx.getFilesDir(), MODEL_FILE);
        if (f.exists() && f.length() > 500000) return f;
        File tmp = new File(ctx.getFilesDir(), MODEL_FILE + ".part");
        MvsepClient.download(MODEL_URL, tmp);
        if (tmp.length() < 500000) {
            tmp.delete();
            throw new IOException("modèle Basic Pitch incomplet");
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) throw new IOException("modèle Basic Pitch non enregistré");
        return f;
    }

    /** pcm : mono, 22 050 Hz, valeurs entre -1 et 1. */
    static List<PitchTranscriber.Note> transcribe(Context ctx, float[] pcm, boolean poly,
                                                  PitchTranscriber.Progress progress) throws Exception {
        File model = ensureModel(ctx);
        Interpreter.Options opt = new Interpreter.Options();
        opt.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
        Interpreter it = new Interpreter(model, opt);
        try {
            int nOut = it.getOutputTensorCount();
            List<Integer> bins88 = new ArrayList<>();
            Map<Integer, Object> outputs = new HashMap<>();
            for (int i = 0; i < nOut; i++) {
                int[] d = it.getOutputTensor(i).shape();
                if (d.length != 3) throw new IOException("sortie Basic Pitch inattendue");
                outputs.put(i, new float[d[0]][d[1]][d[2]]);
                if (d[2] == BINS) bins88.add(i);
            }
            if (bins88.size() != 2) throw new IOException("sorties Basic Pitch inattendues");

            final int len = pcm.length;
            final int total = (int) Math.ceil(len / (double) FRAME_HOP) + 2;
            float[][] a = new float[total][BINS];
            float[][] b = new float[total][BINS];
            final int pad = OVERLAP / 2;
            final int nWin = Math.max(1, (int) Math.ceil(len / (double) HOP));
            float[][] in = new float[1][WIN];

            for (int k = 0; k < nWin; k++) {
                for (int i = 0; i < WIN; i++) {
                    int o = k * HOP + i - pad;
                    float v = (o >= 0 && o < len) ? pcm[o] : 0f;
                    in[0][i] = v > 1f ? 1f : (v < -1f ? -1f : v);
                }
                it.runForMultipleInputsOutputs(new Object[]{in}, outputs);
                float[][] oa = ((float[][][]) outputs.get(bins88.get(0)))[0];
                float[][] ob = ((float[][][]) outputs.get(bins88.get(1)))[0];
                for (int j = KEEP_FROM; j < KEEP_TO && j < oa.length; j++) {
                    int g = (int) Math.round(((double) k * HOP + (double) (j - KEEP_FROM) * FRAME_HOP) / FRAME_HOP);
                    if (g < 0 || g >= total) continue;
                    System.arraycopy(oa[j], 0, a[g], 0, BINS);
                    System.arraycopy(ob[j], 0, b[g], 0, BINS);
                }
                if (progress != null) progress.onProgress((k + 1) * 100 / nWin);
            }

            // La « note » est plus dense que l'« onset » (attaques courtes) : on les distingue par la moyenne.
            double ma = 0, mb = 0;
            for (int t = 0; t < total; t++) {
                for (int p = 0; p < BINS; p++) {
                    ma += a[t][p];
                    mb += b[t][p];
                }
            }
            float[][] note = ma >= mb ? a : b;
            float[][] onset = ma >= mb ? b : a;
            List<PitchTranscriber.Note> notes = decode(note, onset, total);
            return poly ? notes : monophonic(notes);
        } finally {
            it.close();
        }
    }

    private static List<PitchTranscriber.Note> decode(float[][] note, float[][] onset, int total) {
        final float onsetThr = 0.5f, frameThr = 0.3f;
        final int minLen = 3, minRun = 12;
        List<PitchTranscriber.Note> out = new ArrayList<>();
        for (int p = 0; p < BINS; p++) {
            boolean[] covered = new boolean[total];
            List<Integer> peaks = new ArrayList<>();
            for (int t = 0; t < total; t++) {
                float v = onset[t][p];
                if (v < onsetThr) continue;
                float prev = t > 0 ? onset[t - 1][p] : 0f;
                float next = t + 1 < total ? onset[t + 1][p] : 0f;
                if (v >= prev && v > next) peaks.add(t);
            }
            for (int idx = 0; idx < peaks.size(); idx++) {
                int t0 = peaks.get(idx);
                int limit = idx + 1 < peaks.size() ? peaks.get(idx + 1) : total;
                int e = t0;
                float mx = note[t0][p];
                while (e + 1 < limit && note[e + 1][p] >= frameThr) {
                    e++;
                    if (note[e][p] > mx) mx = note[e][p];
                }
                if (e - t0 + 1 < minLen) continue;
                for (int t = t0; t <= e; t++) covered[t] = true;
                out.add(make(p, t0, e + 1, mx));
            }
            // notes tenues sans attaque détectée
            int t = 0;
            while (t < total) {
                if (note[t][p] >= 0.5f && !covered[t]) {
                    int s = t;
                    float mx = 0;
                    while (t < total && note[t][p] >= 0.5f && !covered[t]) {
                        if (note[t][p] > mx) mx = note[t][p];
                        t++;
                    }
                    if (t - s >= minRun) out.add(make(p, s, t, mx));
                } else {
                    t++;
                }
            }
        }
        Collections.sort(out, new Comparator<PitchTranscriber.Note>() {
            @Override public int compare(PitchTranscriber.Note x, PitchTranscriber.Note y) {
                int c = Double.compare(x.start, y.start);
                return c != 0 ? c : Integer.compare(x.pitch, y.pitch);
            }
        });
        return out;
    }

    private static PitchTranscriber.Note make(int bin, int f0, int f1, float strength) {
        int vel = 40 + Math.round(Math.min(1f, strength) * 70f);
        return new PitchTranscriber.Note(MIDI_LOW + bin, f0 * FRAME_HOP / SR, f1 * FRAME_HOP / SR,
                Math.max(1, Math.min(127, vel)));
    }

    /** Une seule note à la fois (voix, basse) : la plus forte garde la place. */
    private static List<PitchTranscriber.Note> monophonic(List<PitchTranscriber.Note> in) {
        List<PitchTranscriber.Note> res = new ArrayList<>();
        for (PitchTranscriber.Note n : in) {
            if (!res.isEmpty()) {
                PitchTranscriber.Note last = res.get(res.size() - 1);
                if (n.start < last.end) {
                    if (n.velocity > last.velocity) {
                        res.remove(res.size() - 1);
                        if (n.start - last.start >= 0.04) {
                            res.add(new PitchTranscriber.Note(last.pitch, last.start, n.start, last.velocity));
                        }
                        res.add(n);
                    } else if (n.end - last.end >= 0.08) {
                        res.add(new PitchTranscriber.Note(n.pitch, last.end, n.end, n.velocity));
                    }
                    continue;
                }
            }
            res.add(n);
        }
        return res;
    }
}
