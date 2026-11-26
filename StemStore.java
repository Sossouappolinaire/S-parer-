package com.stemnotes;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sauvegarde des pistes sur le téléphone :
 *   Musique/Separonsta/<titre du morceau>/<instrument>/<instrument>.mp3
 * Si le même morceau est ajouté une seconde fois, les pistes sont relues depuis ce dossier
 * au lieu d'être séparées à nouveau.
 */
final class StemStore {

    static final String ROOT = "Separonsta";

    static final class Entry {
        final String instrument;
        final Uri uri;

        Entry(String instrument, Uri uri) {
            this.instrument = instrument;
            this.uri = uri;
        }
    }

    /** Un morceau déjà séparé et enregistré dans Separonsta. */
    static final class Song {
        final String title;
        final int count;

        Song(String title, int count) {
            this.title = title;
            this.count = count;
        }
    }

    private StemStore() {}

    /** Nom utilisable comme dossier / fichier. */
    static String safe(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1).trim();
        return t.isEmpty() ? "Sans titre" : t;
    }

    /** Titre d'un morceau à partir du nom de fichier (sans l'extension audio). */
    static String titleOf(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 5) n = n.substring(0, dot);
        return safe(n);
    }

    private static String mimeFor(String ext) {
        if ("wav".equals(ext)) return "audio/x-wav";
        if ("flac".equals(ext)) return "audio/flac";
        if ("m4a".equals(ext)) return "audio/mp4";
        if ("ogg".equals(ext)) return "audio/ogg";
        return "audio/mpeg";
    }

    private static File legacyDir(String title, String instrument) {
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ROOT);
        File d = new File(new File(root, safe(title)), safe(instrument));
        return d;
    }

    /** Enregistre un fichier audio dans Separonsta/<titre>/<instrument>/. */
    static void save(Context ctx, String title, String instrument, File src) throws Exception {
        String ext = AudioDecoder.sniffExt(src);
        if (ext == null) ext = "mp3";
        String inst = safe(instrument);
        String fileName = inst + "." + ext;

        if (Build.VERSION.SDK_INT >= 29) {
            String rel = Environment.DIRECTORY_MUSIC + "/" + ROOT + "/" + safe(title) + "/" + inst + "/";
            ContentResolver cr = ctx.getContentResolver();
            Uri target = findExisting(cr, rel, fileName);
            boolean created = false;
            if (target == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(ext));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                target = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                created = true;
            }
            if (target == null) throw new java.io.IOException("création du fichier impossible");
            OutputStream os = cr.openOutputStream(target, "wt");
            try {
                copy(new FileInputStream(src), os);
            } finally {
                os.close();
            }
            if (created) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(target, done, null, null);
            }
        } else {
            File dir = legacyDir(title, instrument);
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("dossier impossible à créer");
            File out = new File(dir, fileName);
            copy(new FileInputStream(src), new java.io.FileOutputStream(out));
            android.media.MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()}, null, null);
        }
    }

    private static Uri findExisting(ContentResolver cr, String rel, String name) {
        Cursor c = null;
        try {
            c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel, name}, null);
            if (c != null && c.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** Pistes déjà enregistrées pour ce morceau (liste vide s'il n'y en a pas). */
    static List<Entry> list(Context ctx, String title) {
        List<Entry> out = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String rel = Environment.DIRECTORY_MUSIC + "/" + ROOT + "/" + safe(title) + "/";
                Cursor c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH},
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{rel + "%"}, MediaStore.MediaColumns.RELATIVE_PATH + " ASC");
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String p = c.getString(1);
                            if (p == null || !p.startsWith(rel)) continue;
                            String[] seg = p.substring(rel.length()).split("/");
                            if (seg.length == 0 || seg[0].isEmpty()) continue;
                            Uri u = android.content.ContentUris.withAppendedId(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                            out.add(new Entry(seg[0], u));
                        }
                    } finally {
                        c.close();
                    }
                }
            } else {
                File root = new File(new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), ROOT), safe(title));
                File[] dirs = root.listFiles();
                if (dirs != null) {
                    java.util.Arrays.sort(dirs);
                    for (File d : dirs) {
                        File[] fs = d.isDirectory() ? d.listFiles() : null;
                        if (fs == null) continue;
                        for (File f : fs) {
                            String n = f.getName().toLowerCase(Locale.ROOT);
                            if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac")
                                    || n.endsWith(".m4a") || n.endsWith(".ogg")) {
                                out.add(new Entry(d.getName(), Uri.fromFile(f)));
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Liste des morceaux déjà séparés (un dossier par morceau dans Separonsta), triés par titre. */
    static List<Song> listSongs(Context ctx) {
        List<Song> out = new ArrayList<>();
        try {
            java.util.Map<String, java.util.Set<String>> map = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (Build.VERSION.SDK_INT >= 29) {
                String rel = Environment.DIRECTORY_MUSIC + "/" + ROOT + "/";
                Cursor c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns.RELATIVE_PATH},
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{rel + "%"}, null);
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String p = c.getString(0);
                            if (p == null || !p.startsWith(rel)) continue;
                            String[] seg = p.substring(rel.length()).split("/");
                            if (seg.length < 2 || seg[0].isEmpty() || seg[1].isEmpty()) continue;
                            java.util.Set<String> set = map.get(seg[0]);
                            if (set == null) {
                                set = new java.util.HashSet<>();
                                map.put(seg[0], set);
                            }
                            set.add(seg[1]);
                        }
                    } finally {
                        c.close();
                    }
                }
            } else {
                File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ROOT);
                File[] songs = root.listFiles();
                if (songs != null) {
                    for (File sd : songs) {
                        if (!sd.isDirectory()) continue;
                        File[] insts = sd.listFiles();
                        if (insts == null) continue;
                        java.util.Set<String> set = new java.util.HashSet<>();
                        for (File d : insts) if (d.isDirectory()) set.add(d.getName());
                        if (!set.isEmpty()) map.put(sd.getName(), set);
                    }
                }
            }
            for (java.util.Map.Entry<String, java.util.Set<String>> e : map.entrySet()) {
                out.add(new Song(e.getKey(), e.getValue().size()));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Copie une piste enregistrée vers un fichier du cache de l'app. */
    static void copyTo(Context ctx, Uri uri, File dest) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("lecture impossible");
        copy(in, new java.io.FileOutputStream(dest));
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        try {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
        } finally {
            in.close();
            out.flush();
            if (out instanceof java.io.FileOutputStream) out.close();
        }
    }
}
