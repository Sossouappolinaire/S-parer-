package com.stemnotes;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Lit la clé API MVSEP stockée dans Supabase (table stemnotes_config). */
final class SupabaseConfig {

    private static final String URL_BASE = "https://hvjgqaxejqnvgvefjhnj.supabase.co";
    private static final String ANON_KEY = "sb_publishable_65fqWs6C2pi_ToCuTxnqZg_V9OyUA3v";

    private SupabaseConfig() {}

    /** Renvoie la clé MVSEP, ou une chaîne vide si elle est introuvable. */
    static String fetchMvsepToken() throws IOException {
        String u = URL_BASE + "/rest/v1/stemnotes_config?select=value&key=eq.mvsep_api_token";
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("apikey", ANON_KEY);
        c.setRequestProperty("Authorization", "Bearer " + ANON_KEY);
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("Supabase HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
        } finally {
            in.close();
        }
        try {
            JSONArray a = new JSONArray(new String(bo.toByteArray(), "UTF-8"));
            if (a.length() == 0) return "";
            return a.getJSONObject(0).optString("value", "").trim();
        } catch (org.json.JSONException e) {
            throw new IOException("Réponse Supabase inattendue");
        }
    }
}
