package com.stemnotes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Locale;

/** Écrit les paroles d'une piste vocale avec Whisper (API Groq). */
final class GroqClient {

    private static final String URL_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions";
    // Si le premier modèle échoue (limite atteinte, erreur serveur), le suivant prend le relais.
    private static final String[] MODELS = {"whisper-large-v3-turbo", "whisper-large-v3"};
    private static final long MAX_BYTES = 24L * 1024 * 1024; // limite gratuite : 25 Mo
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private GroqClient() {}

    /** Renvoie les paroles, une phrase par ligne avec son horodatage. */
    static String transcribe(String token, File audio, String language) throws IOException {
        if (audio.length() > MAX_BYTES) {
            throw new IOException("fichier trop gros pour Groq (25 Mo max)");
        }
        IOException last = null;
        for (String model : MODELS) {
            try {
                return transcribeWith(token, audio, language, model);
            } catch (IOException e) {
                last = e;
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.startsWith("HTTP 401") || m.startsWith("HTTP 403")) throw e; // clé refusée : inutile d'insister
            }
        }
        throw last != null ? last : new IOException("Groq indisponible");
    }

    private static String transcribeWith(String token, File audio, String language, String model)
            throws IOException {
        String filename = "piste." + guessExt(audio);
        String boundary = "----stemnotes" + System.currentTimeMillis();
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        field(pre, boundary, "model", model);
        field(pre, boundary, "response_format", "verbose_json");
        field(pre, boundary, "temperature", "0");
        if (language != null && !language.isEmpty()) field(pre, boundary, "language", language);
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        pre.write(head.getBytes(UTF8));
        byte[] post = ("\r\n--" + boundary + "--\r\n").getBytes(UTF8);

        HttpURLConnection c = (HttpURLConnection) new URL(URL_TRANSCRIBE).openConnection();
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Authorization", "Bearer " + token);
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
            JSONArray seg = j.optJSONArray("segments");
            if (seg != null && seg.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < seg.length(); i++) {
                    JSONObject s = seg.getJSONObject(i);
                    String t = s.optString("text", "").trim();
                    if (t.isEmpty()) continue;
                    double st = s.optDouble("start", 0);
                    int min = (int) (st / 60);
                    sb.append(String.format(Locale.US, "%d:%04.1f  %s\n", min, st - min * 60, t));
                }
                return sb.toString().trim();
            }
            return j.optString("text", "").trim();
        } catch (org.json.JSONException e) {
            throw new IOException("réponse Groq inattendue");
        }
    }

    /** Devine le format d'après les premiers octets (Groq veut une extension valide). */
    private static String guessExt(File f) {
        byte[] h = new byte[12];
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                int n = in.read(h);
                if (n < 12) return "mp3";
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "mp3";
        }
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') return "wav";
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "flac";
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "ogg";
        if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return "m4a";
        return "mp3";
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
        if (code >= 400) {
            String msg = body;
            try {
                msg = new JSONObject(body).getJSONObject("error").optString("message", body);
            } catch (Exception ignored) {
            }
            throw new IOException("HTTP " + code + " " + msg);
        }
        return body;
    }
}
