package com.stemnotes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Client minimal de l'API MVSEP (https://mvsep.com/full_api). */
final class MvsepClient {

    private static final String BASE = "https://mvsep.com/api/separation/";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    interface Status {
        void onStatus(String status);
    }

    static final class Remote {
        final String name;
        final String url;

        Remote(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private MvsepClient() {}

    /** Envoie le fichier et renvoie le "hash" de la tâche. */
    static String create(String token, File audio, String sepType, String opt1, String filename)
            throws IOException {
        String boundary = "----stemnotes" + System.currentTimeMillis();
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        field(pre, boundary, "api_token", token);
        field(pre, boundary, "sep_type", sepType);
        if (opt1 != null && !opt1.isEmpty()) field(pre, boundary, "add_opt1", opt1);
        field(pre, boundary, "output_format", "0"); // mp3 320 kbps : léger et décodable partout
        String fileHead = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"audiofile\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + mimeFor(filename) + "\r\n\r\n";
        pre.write(fileHead.getBytes(UTF8));
        byte[] post = ("\r\n--" + boundary + "--\r\n").getBytes(UTF8);

        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "create").openConnection();
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setFixedLengthStreamingMode(pre.size() + audio.length() + post.length);
        OutputStream os = c.getOutputStream();
        try {
            os.write(pre.toByteArray());
            FileInputStream in = new FileInputStream(audio);
            try {
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) os.write(b, 0, r);
            } finally {
                in.close();
            }
            os.write(post);
            os.flush();
        } finally {
            os.close();
        }

        String body = readBody(c);
        try {
            JSONObject j = new JSONObject(body);
            if (!j.optBoolean("success", false)) throw new IOException("MVSEP : " + body);
            return j.getJSONObject("data").getString("hash");
        } catch (org.json.JSONException e) {
            throw new IOException("Réponse MVSEP inattendue : " + body);
        }
    }

    /** Attend la fin de la séparation puis renvoie les pistes à télécharger. */
    static List<Remote> waitForResult(String hash, Status cb) throws Exception {
        String url = BASE + "get?hash=" + URLEncoder.encode(hash, "UTF-8");
        final long t0 = System.currentTimeMillis();
        for (int i = 0; i < 540; i++) { // ~90 minutes max (file d'attente MVSEP parfois longue)
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            String body = readBody(c);
            JSONObject j = new JSONObject(body);
            String st = j.optString("status", "");
            if (st.isEmpty() && j.optJSONObject("data") != null) {
                st = j.getJSONObject("data").optString("status", "");
            }
            if ("done".equals(st)) {
                JSONObject data = j.optJSONObject("data");
                JSONArray files = data == null ? null : data.optJSONArray("files");
                List<Remote> out = new ArrayList<>();
                if (files != null) {
                    for (int k = 0; k < files.length(); k++) {
                        JSONObject f = files.getJSONObject(k);
                        String u = f.optString("url", "");
                        if (u.isEmpty()) continue;
                        String name = f.optString("type", "");
                        if (name.isEmpty()) name = f.optString("download", "piste" + (k + 1));
                        out.add(new Remote(name, u));
                    }
                }
                if (out.isEmpty()) throw new IOException("MVSEP : aucune piste dans la réponse : " + body);
                return out;
            }
            if ("failed".equals(st) || "error".equals(st) || "not_found".equals(st)) {
                throw new IOException("MVSEP a échoué (" + st + ") : " + body);
            }
            if (cb != null) {
                StringBuilder sb = new StringBuilder();
                if ("waiting".equals(st) || st.isEmpty()) sb.append("file d'attente MVSEP");
                else if ("processing".equals(st)) sb.append("traitement en cours");
                else sb.append(st);
                JSONObject d = j.optJSONObject("data");
                if (d != null) {
                    int order = d.optInt("current_order", -1);
                    int count = d.optInt("queue_count", -1);
                    if (order > 0 && count > 0) sb.append(" · position ").append(order).append("/").append(count);
                    else if (order > 0) sb.append(" · position ").append(order);
                }
                long min = (System.currentTimeMillis() - t0) / 60000L;
                sb.append(" · ").append(min).append(" min");
                cb.onStatus(sb.toString());
            }
            Thread.sleep(10000);
        }
        throw new IOException("MVSEP : délai dépassé (90 min). Réessayez plus tard.");
    }

    static void download(String url, File dest) throws IOException {
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("Téléchargement : HTTP " + code);
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(dest);
        try {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
        } finally {
            out.close();
            in.close();
        }
    }

    private static String mimeFor(String filename) {
        String f = filename.toLowerCase(java.util.Locale.ROOT);
        if (f.endsWith(".mp3")) return "audio/mpeg";
        if (f.endsWith(".wav")) return "audio/wav";
        if (f.endsWith(".flac")) return "audio/flac";
        if (f.endsWith(".m4a") || f.endsWith(".mp4")) return "audio/mp4";
        if (f.endsWith(".aac")) return "audio/aac";
        if (f.endsWith(".ogg") || f.endsWith(".opus")) return "audio/ogg";
        return "audio/mpeg";
    }

    private static void field(ByteArrayOutputStream o, String boundary, String name, String value)
            throws IOException {
        String s = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        o.write(s.getBytes(UTF8));
    }

    private static String readBody(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (is != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = is.read(b)) > 0) bo.write(b, 0, r);
            is.close();
            body = new String(bo.toByteArray(), UTF8);
        }
        if (code >= 400) throw new IOException("HTTP " + code + " " + body);
        return body;
    }
}
