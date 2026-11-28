package com.stemnotes;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Sauvegarde de l'analyse (notes détectées, moteur, paroles) de chaque piste, dans la mémoire de l'app :
 *   <fichiers de l'app>/analyses/<titre du morceau>/<instrument>.json
 * Avec les pistes audio de Musique/Separonsta, cela permet de rouvrir un morceau déjà séparé et analysé
 * entièrement hors ligne, sans nouvelle séparation ni nouvelle analyse.
 */
final class NoteStore {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Loaded {
        List<PitchTranscriber.Note> notes; // null si la piste n'a pas été analysée
        String engine = "";
        String lyrics;                     // null si pas de paroles
    }

    private NoteStore() {}

    private static File dir(Context ctx, String title) {
        return new File(new File(ctx.getFilesDir(), "analyses"), StemStore.safe(title));
    }

    private static File file(Context ctx, String title, String instrument) {
        return new File(dir(ctx, title), StemStore.safe(instrument) + ".json");
    }

    /** Vrai si au moins une piste de ce morceau a une analyse enregistrée. */
    static boolean has(Context ctx, String title) {
        try {
            File[] fs = dir(ctx, title).listFiles();
            if (fs == null) return false;
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) continue;
                JSONObject o = read(f);
                if (o != null && o.has("notes")) return true;
            }
        } catch (Exception ignored) {
        }
        // pas d'analyse dans la mémoire de l'appli (réinstallation…) : on regarde les fichiers à côté de l'audio
        return StemStore.hasAnalysisFile(ctx, title);
    }

    /** Copie l'analyse dans Musique/Separonsta (fichier .mid) pour qu'elle survive à une réinstallation. */
    private static void mirror(Context ctx, String title, String instrument, JSONObject o) {
        try {
            List<PitchTranscriber.Note> notes = new ArrayList<>();
            JSONArray arr = o.optJSONArray("notes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray a = arr.getJSONArray(i);
                    notes.add(new PitchTranscriber.Note(a.getInt(0), a.getDouble(1), a.getDouble(2), a.getInt(3)));
                }
            }
            String lyrics = o.has("lyrics") ? o.optString("lyrics", null) : null;
            StemStore.saveAnalysisFile(ctx, title, instrument,
                    NoteMidi.encode(instrument, notes, o.optString("engine", ""), lyrics));
        } catch (Throwable ignored) {
            // dossier inaccessible (permission refusée…) : l'analyse reste enregistrée dans l'appli
        }
    }

    /** Enregistre les notes d'une piste (et ses paroles si elle en a). */
    static synchronized void saveNotes(Context ctx, String title, String instrument,
                                       List<PitchTranscriber.Note> notes, String engine) throws Exception {
        File f = file(ctx, title, instrument);
        JSONObject o = f.exists() ? read(f) : null;
        if (o == null) o = new JSONObject();
        JSONArray arr = new JSONArray();
        for (PitchTranscriber.Note n : notes) {
            JSONArray a = new JSONArray();
            a.put(n.pitch);
            a.put(Math.round(n.start * 1000) / 1000.0);
            a.put(Math.round(n.end * 1000) / 1000.0);
            a.put(n.velocity);
            arr.put(a);
        }
        o.put("notes", arr);
        o.put("engine", engine == null ? "" : engine);
        write(f, o);
        mirror(ctx, title, instrument, o);
    }

    /** Enregistre les paroles d'une piste. */
    static synchronized void saveLyrics(Context ctx, String title, String instrument, String lyrics) throws Exception {
        File f = file(ctx, title, instrument);
        JSONObject o = f.exists() ? read(f) : null;
        if (o == null) o = new JSONObject();
        o.put("lyrics", lyrics);
        write(f, o);
        if (o.has("notes")) mirror(ctx, title, instrument, o);
    }

    /** Relit l'analyse d'une piste, ou null s'il n'y en a pas. */
    static synchronized Loaded load(Context ctx, String title, String instrument) {
        try {
            File f = file(ctx, title, instrument);
            if (!f.exists()) {
                // analyse absente de l'appli (réinstallation) : on la relit depuis le fichier .mid du dossier Separonsta
                NoteMidi.Data d = NoteMidi.decode(StemStore.readAnalysisFile(ctx, title, instrument));
                if (d == null) return null;
                Loaded r = new Loaded();
                r.notes = d.notes;
                r.engine = d.engine;
                r.lyrics = d.lyrics;
                return r;
            }
            JSONObject o = read(f);
            if (o == null) return null;
            Loaded l = new Loaded();
            l.engine = o.optString("engine", "");
            if (o.has("lyrics")) l.lyrics = o.optString("lyrics", null);
            JSONArray arr = o.optJSONArray("notes");
            if (arr != null) {
                l.notes = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray a = arr.getJSONArray(i);
                    l.notes.add(new PitchTranscriber.Note(a.getInt(0), a.getDouble(1), a.getDouble(2), a.getInt(3)));
                }
            }
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return new JSONObject(new String(bo.toByteArray(), UTF8));
        } finally {
            in.close();
        }
    }

    private static void write(File f, JSONObject o) throws Exception {
        File d = f.getParentFile();
        if (d != null && !d.exists() && !d.mkdirs()) throw new java.io.IOException("dossier impossible à créer");
        File tmp = new File(d, f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(o.toString().getBytes(UTF8));
        } finally {
            out.close();
        }
        if (f.exists() && !f.delete()) throw new java.io.IOException("écriture impossible");
        if (!tmp.renameTo(f)) throw new java.io.IOException("écriture impossible");
    }
}
