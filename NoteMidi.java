package com.stemnotes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Analyse d'une piste (notes, moteur, paroles) enregistrée dans un vrai fichier MIDI standard
 * (120 BPM, 480 ticks/noire), rangé à côté de l'audio dans Musique/Separonsta/<titre>/<instrument>/.
 * Le fichier s'ouvre dans n'importe quel lecteur MIDI ; l'appli le relit pour retrouver l'analyse
 * même après une réinstallation. Le moteur et les paroles sont dans des événements texte (FF 01).
 */
final class NoteMidi {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final double TPS = 960.0; // ticks par seconde : 120 BPM * 480 / 60
    private static final String TAG_ENGINE = "stemnotes:engine=";
    private static final String TAG_LYRICS = "stemnotes:lyrics=";

    static final class Data {
        List<PitchTranscriber.Note> notes = new ArrayList<>();
        String engine = "";
        String lyrics; // null si pas de paroles
    }

    private NoteMidi() {}

    static byte[] encode(String name, List<PitchTranscriber.Note> notes, String engine, String lyrics) {
        String low = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        int channel = (low.contains("batterie") || low.contains("drum")) ? 9 : 0;
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(0x00); t.write(0xFF); t.write(0x51); t.write(0x03); t.write(0x07); t.write(0xA1); t.write(0x20);
        text(t, 0x03, name == null ? "piste" : name);
        text(t, 0x01, TAG_ENGINE + (engine == null ? "" : engine));
        if (lyrics != null && !lyrics.isEmpty()) text(t, 0x01, TAG_LYRICS + lyrics);

        List<long[]> ev = new ArrayList<>();
        if (notes != null) {
            for (PitchTranscriber.Note n : notes) {
                long s = Math.round(n.start * TPS);
                long e = Math.max(s + 1, Math.round(n.end * TPS));
                ev.add(new long[]{s, 1, n.pitch, n.velocity});
                ev.add(new long[]{e, 0, n.pitch, 0});
            }
        }
        Collections.sort(ev, new Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) {
                if (a[0] != b[0]) return Long.compare(a[0], b[0]);
                return Long.compare(a[1], b[1]);
            }
        });
        long last = 0;
        for (long[] e : ev) {
            vlq(t, e[0] - last);
            last = e[0];
            t.write((e[1] == 1 ? 0x90 : 0x80) | channel);
            t.write((int) e[2]);
            t.write((int) e[3]);
        }
        t.write(0x00); t.write(0xFF); t.write(0x2F); t.write(0x00);

        byte[] trk = t.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ascii(out, "MThd"); i32(out, 6); i16(out, 0); i16(out, 1); i16(out, 480);
        ascii(out, "MTrk"); i32(out, trk.length);
        out.write(trk, 0, trk.length);
        return out.toByteArray();
    }

    /** Relit un fichier écrit par {@link #encode} ; renvoie null si ce n'est pas un fichier de l'appli. */
    static Data decode(byte[] b) {
        try {
            if (b == null || b.length < 22 || b[0] != 'M' || b[1] != 'T' || b[2] != 'h' || b[3] != 'd') return null;
            int pos = 14;
            if (b[pos] != 'M' || b[pos + 1] != 'T' || b[pos + 2] != 'r' || b[pos + 3] != 'k') return null;
            int len = ((b[pos + 4] & 0xFF) << 24) | ((b[pos + 5] & 0xFF) << 16) | ((b[pos + 6] & 0xFF) << 8) | (b[pos + 7] & 0xFF);
            pos += 8;
            int end = Math.min(b.length, pos + len);
            Data d = new Data();
            boolean ours = false;
            long tick = 0;
            int running = 0;
            double[][] open = new double[128][]; // {début, vélocité}
            while (pos < end) {
                long delta = 0;
                int c;
                do {
                    c = b[pos++] & 0xFF;
                    delta = (delta << 7) | (c & 0x7F);
                } while ((c & 0x80) != 0 && pos < end);
                tick += delta;
                int st = b[pos] & 0xFF;
                if (st == 0xFF) {
                    int type = b[pos + 1] & 0xFF;
                    pos += 2;
                    long l = 0;
                    do {
                        c = b[pos++] & 0xFF;
                        l = (l << 7) | (c & 0x7F);
                    } while ((c & 0x80) != 0 && pos < end);
                    if (type == 0x01) {
                        String s = new String(b, pos, (int) l, UTF8);
                        if (s.startsWith(TAG_ENGINE)) { d.engine = s.substring(TAG_ENGINE.length()); ours = true; }
                        else if (s.startsWith(TAG_LYRICS)) { d.lyrics = s.substring(TAG_LYRICS.length()); ours = true; }
                    }
                    pos += (int) l;
                    if (type == 0x2F) break;
                    continue;
                }
                if (st == 0xF0 || st == 0xF7) {
                    pos++;
                    long l = 0;
                    do {
                        c = b[pos++] & 0xFF;
                        l = (l << 7) | (c & 0x7F);
                    } while ((c & 0x80) != 0 && pos < end);
                    pos += (int) l;
                    continue;
                }
                if (st >= 0x80) { running = st; pos++; } else st = running;
                int kind = st & 0xF0;
                int n1 = b[pos++] & 0xFF;
                int n2 = 0;
                if (kind != 0xC0 && kind != 0xD0) n2 = b[pos++] & 0xFF;
                double sec = tick / TPS;
                if (kind == 0x90 && n2 > 0) {
                    open[n1] = new double[]{sec, n2};
                } else if (kind == 0x80 || kind == 0x90) {
                    if (open[n1] != null) {
                        d.notes.add(new PitchTranscriber.Note(n1, open[n1][0], Math.max(sec, open[n1][0] + 0.001), (int) open[n1][1]));
                        open[n1] = null;
                    }
                }
            }
            if (!ours) return null;
            Collections.sort(d.notes, new Comparator<PitchTranscriber.Note>() {
                @Override public int compare(PitchTranscriber.Note a, PitchTranscriber.Note c2) {
                    return Double.compare(a.start, c2.start);
                }
            });
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void text(ByteArrayOutputStream o, int type, String s) {
        byte[] bytes = s.getBytes(UTF8);
        o.write(0x00); o.write(0xFF); o.write(type);
        vlq(o, bytes.length);
        o.write(bytes, 0, bytes.length);
    }

    private static void vlq(ByteArrayOutputStream o, long value) {
        long buffer = value & 0x7F;
        while ((value >>= 7) > 0) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }
        while (true) {
            o.write((int) (buffer & 0xFF));
            if ((buffer & 0x80) != 0) buffer >>= 8;
            else break;
        }
    }

    private static void ascii(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i));
    }

    private static void i32(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xFF); o.write((v >>> 16) & 0xFF); o.write((v >>> 8) & 0xFF); o.write(v & 0xFF);
    }

    private static void i16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF); o.write(v & 0xFF);
    }
}
