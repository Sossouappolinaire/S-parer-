package com.stemnotes;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Écrit un fichier MIDI standard (120 BPM, 480 ticks/noire). */
final class MidiWriter {

    private static final int DIVISION = 480;
    private static final double TICKS_PER_SECOND = 960.0; // 120 BPM * 480 / 60

    private MidiWriter() {}

    static byte[] write(List<String> names, List<List<PitchTranscriber.Note>> tracks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "MThd");
        writeInt(out, 6);
        writeShort(out, tracks.size() > 1 ? 1 : 0);
        writeShort(out, tracks.size());
        writeShort(out, DIVISION);
        for (int i = 0; i < tracks.size(); i++) {
            int channel = i % 15;
            if (channel >= 9) channel++; // évite le canal 10 (percussions)
            byte[] trk = buildTrack(names.get(i), tracks.get(i), i == 0, channel);
            writeAscii(out, "MTrk");
            writeInt(out, trk.length);
            out.write(trk, 0, trk.length);
        }
        return out.toByteArray();
    }

    private static byte[] buildTrack(String name, List<PitchTranscriber.Note> notes,
                                     boolean withTempo, int channel) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        if (withTempo) {
            t.write(0x00); t.write(0xFF); t.write(0x51); t.write(0x03);
            t.write(0x07); t.write(0xA1); t.write(0x20);
        }
        byte[] nm = ascii(name);
        t.write(0x00); t.write(0xFF); t.write(0x03);
        vlq(t, nm.length);
        t.write(nm, 0, nm.length);

        // événements : {tick, ordre (0 = off, 1 = on), pitch, vélocité}
        List<long[]> ev = new ArrayList<>();
        for (PitchTranscriber.Note n : notes) {
            long s = Math.round(n.start * TICKS_PER_SECOND);
            long e = Math.max(s + 1, Math.round(n.end * TICKS_PER_SECOND));
            ev.add(new long[]{s, 1, n.pitch, n.velocity});
            ev.add(new long[]{e, 0, n.pitch, 0});
        }
        Collections.sort(ev, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                if (a[0] != b[0]) return Long.compare(a[0], b[0]);
                return Long.compare(a[1], b[1]);
            }
        });

        long last = 0;
        for (long[] e : ev) {
            vlq(t, e[0] - last);
            last = e[0];
            if (e[1] == 1) {
                t.write(0x90 | channel);
                t.write((int) e[2]);
                t.write((int) e[3]);
            } else {
                t.write(0x80 | channel);
                t.write((int) e[2]);
                t.write(0);
            }
        }
        t.write(0x00); t.write(0xFF); t.write(0x2F); t.write(0x00);
        return t.toByteArray();
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

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) {
            char c = s.charAt(i);
            b[i] = (byte) (c >= 32 && c < 127 ? c : '?');
        }
        return b;
    }

    private static void writeAscii(ByteArrayOutputStream o, String s) {
        byte[] b = ascii(s);
        o.write(b, 0, b.length);
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }
}
