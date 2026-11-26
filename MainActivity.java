package com.stemnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_SONG = 1;
    private static final int REQ_STEM = 2;
    private static final int REQ_SAVE = 3;
    private static final int REQ_PERM = 4;

    private static final int M = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int W = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String[] NAV_LABELS = {"Accueil", "Pistes", "Aide", "Profil"};
    private static final int[] NAV_ICONS = {IconView.HOME, IconView.BARS, IconView.HELP, IconView.USER};

    private static final class Stem {
        final String name;
        final File file;
        boolean selected = true;
        boolean poly;
        List<PitchTranscriber.Note> notes;
        volatile String lyrics;
        volatile String engine = "";
        volatile boolean lyricsBusy;
        volatile float pct;
        BarView bar;
        TextView pctLabel;
        TextView playLabel;
        TextView mixLabel;

        Stem(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    // lecture : une ou plusieurs pistes jouées ensemble, synchronisées
    private final LinkedHashMap<Stem, MediaPlayer> mix = new LinkedHashMap<>();
    private boolean mixPaused = false;
    private int tickCount = 0;
    private static final int[] STEM_COLORS = {0xFFE8590C, 0xFF2F9E44, 0xFF1C7ED6, 0xFFAE3EC9,
            0xFFF59F00, 0xFFD6336C, 0xFF0CA678, 0xFF5C7CFA};

    // vues du lecteur (nulles si non affichées)
    private NoteRollView roll;
    private final List<View> instViews = new ArrayList<>(); // clavier piano / basse du lecteur
    private TextView clockLabel, rollNow, lyPrev, lyCur, lyNext, playAllBtn;
    private SeekBar seek;
    private boolean seeking = false;
    private int lyIdx = -2;
    private String lastNow = "";
    private String lyCacheSrc = null;
    private List<LyricLine> lyCache = new ArrayList<>();

    private static final class LyricLine {
        final double t;
        final String text;
        LyricLine(double t, String text) { this.t = t; this.text = text; }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mix.isEmpty()) return;
            tickCount++;
            if (!mixPaused && tickCount % 30 == 0) resync();
            updateLive(false);
            if (!mixPaused) ui.postDelayed(this, 33);
        }
    };
    private final List<Stem> stems = new ArrayList<>();
    private final Set<String> seenDone = new HashSet<>();
    private final int[] scrollYs = new int[4];
    private final boolean[] helpOpen = new boolean[5];

    private SharedPreferences prefs;
    private FrameLayout content;
    private LinearLayout[] navItems;
    private IconView[] navIcons;
    private TextView[] navLabels;

    private int tab = 0;
    private int renderedTab = -1;
    private int segment = 0; // onglet Pistes : 0 = pistes, 1 = notes

    private Uri songUri;
    private String songName = "";
    private byte[] pendingMidi;
    private boolean savedOnce = false;
    private String stemsTitle = "";          // titre du morceau dont viennent les pistes
    private volatile boolean saving = false;
    private boolean stemsSaved = false;      // les pistes affichées sont déjà dans Separonsta
    private List<StemStore.Song> savedSongs = new ArrayList<>(); // morceaux déjà séparés (Separonsta)
    private Runnable pendingAfterPerm;
    private int importCounter = 0;

    private volatile boolean busy = false;
    private volatile boolean sepRunning = false;
    private volatile boolean anaRunning = false;
    private volatile float sepPct = 0f;      // 0..100
    private volatile float anaPct = 0f;      // 0..1
    private volatile String sepStatus = "";
    private volatile String anaStatus = "";

    // vues « vivantes » mises à jour pendant les tâches (nulles si l'écran n'est pas affiché)
    private BarView homeSepBar, homeAnaBar, progressBar;
    private TextView homeSepText, homeAnaText, progressLabel, extractLabel;
    private ArtView homeArt1, homeArt2;

    // ============================================================ cycle de vie
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        prefs = getSharedPreferences("stemnotes", MODE_PRIVATE);

        getWindow().setStatusBarColor(Ui.CREAM);
        getWindow().setNavigationBarColor(Ui.WHITE);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);

        LinearLayout root = vbox();
        root.setBackgroundColor(Ui.CREAM);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(M, 0, 1f));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(M, Ui.dp(1)));
        root.addView(buildNav(), new LinearLayout.LayoutParams(M, W));
        setContentView(root);

        render(false);
        loadTokenFromSupabase();
    }

    /** Récupère la clé MVSEP dans Supabase au lancement et l'enregistre. */
    private void loadTokenFromSupabase() {
        exec.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final String t = SupabaseConfig.fetchMvsepToken();
                    if (t.isEmpty() || t.equals(prefs.getString("token", ""))) return;
                    prefs.edit().putString("token", t).apply();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            render(false);
                        }
                    });
                } catch (Exception ignored) {
                    // hors ligne : on garde la clé déjà enregistrée
                }
                try {
                    final String g = SupabaseConfig.fetchGroqToken();
                    if (!g.isEmpty() && !g.equals(prefs.getString("groq_token", ""))) {
                        prefs.edit().putString("groq_token", g).apply();
                    }
                } catch (Exception ignored) {
                    // pas de clé Groq en ligne : on utilise celle du Profil
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        exec.shutdownNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSavedSongs();
    }

    /** Relit la liste des morceaux déjà séparés (en arrière-plan) et réaffiche l'accueil si elle a changé. */
    private void refreshSavedSongs() {
        exec.execute(new Runnable() {
            @Override public void run() {
                final List<StemStore.Song> now = StemStore.listSongs(getApplicationContext());
                boolean same = now.size() == savedSongs.size();
                for (int i = 0; same && i < now.size(); i++) {
                    same = now.get(i).title.equals(savedSongs.get(i).title) && now.get(i).count == savedSongs.get(i).count;
                }
                if (same) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        savedSongs = now;
                        if ((tab == 0 || (tab == 1 && segment == 0)) && mix.isEmpty() && !sepRunning && !anaRunning) {
                            render(false);
                        }
                    }
                });
            }
        });
    }

    // ============================================================ nouvelle séparation
    private boolean hasUnsavedStems() {
        if (stemsSaved) return false;
        for (Stem x : stems) if (!x.name.equals("Morceau complet")) return true;
        return false;
    }

    /** Repart de zéro : on peut choisir un nouveau morceau à séparer. */
    private void newSeparation() {
        if (busy || sepRunning || anaRunning) { toast("Une tâche est en cours"); return; }
        if (hasUnsavedStems()) {
            new AlertDialog.Builder(this)
                    .setTitle("Nouvelle séparation")
                    .setMessage("Les pistes actuelles (et leurs notes) ne sont pas enregistrées dans Separonsta. "
                            + "Les retirer de l'écran quand même ?\n\nAstuce : « Télécharger les pistes » les garde "
                            + "dans la liste des morceaux déjà séparés.")
                    .setPositiveButton("Continuer", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { resetForNewSeparation(); }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        } else {
            resetForNewSeparation();
        }
    }

    private void resetForNewSeparation() {
        stopPlayback();
        stems.clear();
        seenDone.clear();
        songUri = null;
        songName = "";
        stemsTitle = "";
        savedOnce = false;
        stemsSaved = false;
        pendingMidi = null;
        segment = 0;
        tab = 0;
        render(true);
        pickAudio(REQ_SONG);
    }

    /** Charge un morceau déjà séparé (dossier Separonsta) : analyse, notes, MIDI… sans nouvelle séparation. */
    private void openSavedSong(final String title) {
        if (busy) { toast("Une tâche est en cours"); return; }
        if (hasUnsavedStems()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("Les pistes affichées ne sont pas enregistrées dans Separonsta. Les remplacer par ce morceau ?")
                    .setPositiveButton("Remplacer", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { loadSavedSong(title); }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        } else {
            loadSavedSong(title);
        }
    }

    private void loadSavedSong(final String title) {
        if (busy) { toast("Une tâche est en cours"); return; }
        busy = true;
        sepRunning = true;
        sepPct = 5f;
        sepStatus = "Chargement de " + title;
        render(false);
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<Stem> found = readSavedStems(title);
                    if (found.isEmpty()) throw new Exception("aucune piste trouvée pour " + title);
                    sepPct = 100f; sepStatus = "Terminé"; sepRunning = false;
                    finishSeparation(found, title, "Morceau chargé : " + title, true);
                } catch (Throwable e) {
                    sepRunning = false;
                    toastLater("Erreur : " + e.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() { render(false); }
                    });
                } finally {
                    sepRunning = false;
                    busy = false;
                }
            }
        });
    }

    /** Relit les pistes enregistrées d'un morceau vers le cache de l'app. */
    private List<Stem> readSavedStems(String title) throws Exception {
        List<StemStore.Entry> saved = StemStore.list(getApplicationContext(), title);
        List<Stem> found = new ArrayList<>();
        for (int i = 0; i < saved.size(); i++) {
            StemStore.Entry e = saved.get(i);
            File f = new File(getCacheDir(), "stem_" + i);
            StemStore.copyTo(getApplicationContext(), e.uri, f);
            Stem st = new Stem(e.instrument, f);
            st.poly = guessPoly(e.instrument);
            String low = e.instrument.toLowerCase(Locale.ROOT);
            st.selected = !(low.contains("drum") || low.contains("batterie"));
            found.add(st);
            sepPct = 10f + 90f * (i + 1) / saved.size();
            postLive();
        }
        return found;
    }

    /** Carte « Morceaux déjà séparés » : un appui charge le morceau. */
    private View savedSongsCard() {
        LinearLayout card = vbox();
        card.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1.5f));
        card.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(8));
        card.addView(Ui.text(this, "Morceaux déjà séparés", 16, Ui.INK, true));
        card.addView(Ui.text(this, "Touchez un morceau pour le rouvrir sans le séparer à nouveau.", 11, Ui.MUTED, false),
                Ui.lp(M, W, 0, 3, 0, 6));
        for (final StemStore.Song so : savedSongs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(10), 0, Ui.dp(10));
            LinearLayout t = vbox();
            t.addView(Ui.text(this, so.title, 14, Ui.INK, true));
            boolean current = !stems.isEmpty() && !stemsTitle.isEmpty() && so.title.equals(StemStore.safe(stemsTitle));
            t.addView(Ui.text(this, so.count + " piste(s)" + (current ? " · affiché" : ""), 11, Ui.MUTED, false),
                    Ui.lp(W, W, 0, 2, 0, 0));
            row.addView(t, new LinearLayout.LayoutParams(0, W, 1f));
            row.addView(new IconView(this, IconView.ARROW, Ui.INK), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openSavedSong(so.title); }
            });
            Ui.pressable(row);
            card.addView(row, new LinearLayout.LayoutParams(M, W));
        }
        return card;
    }

    // ============================================================ lecture (une piste seule ou plusieurs ensemble)
    private static String fmtTime(int ms) {
        int sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private static boolean isPlayingSafe(MediaPlayer p) {
        try { return p.isPlaying(); } catch (Exception e) { return false; }
    }

    private int posMs() {
        int m = 0;
        for (MediaPlayer p : mix.values()) {
            try { m = Math.max(m, p.getCurrentPosition()); } catch (Exception ignored) { }
        }
        return m;
    }

    private int durMs() {
        int m = 0;
        for (MediaPlayer p : mix.values()) {
            try { m = Math.max(m, p.getDuration()); } catch (Exception ignored) { }
        }
        return m;
    }

    private String playText(Stem s) {
        if (mix.size() == 1 && mix.containsKey(s)) {
            String t = fmtTime(posMs()) + " / " + fmtTime(durMs());
            return (mixPaused ? "▶ Reprendre · " : "⏸ Pause · ") + t;
        }
        return "▶ Écouter seul";
    }

    private String mixText(Stem s) {
        return mix.containsKey(s) ? "✓ Dans le mix (retirer)" : "+ Jouer avec le mix";
    }

    private void stopPlayback() {
        ui.removeCallbacks(ticker);
        for (MediaPlayer p : mix.values()) {
            try { p.stop(); } catch (Exception ignored) { }
            try { p.release(); } catch (Exception ignored) { }
        }
        mix.clear();
        mixPaused = false;
    }

    private void pauseAll() {
        if (mix.isEmpty()) return;
        for (MediaPlayer p : mix.values()) {
            try { if (p.isPlaying()) p.pause(); } catch (Exception ignored) { }
        }
        mixPaused = true;
        ui.removeCallbacks(ticker);
    }

    private void resumeAll() {
        for (MediaPlayer p : mix.values()) {
            try { p.start(); } catch (Exception ignored) { }
        }
        mixPaused = false;
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    /** Remet d'aplomb les pistes qui dérivent légèrement les unes par rapport aux autres. */
    private void resync() {
        MediaPlayer ref = null;
        for (MediaPlayer p : mix.values()) {
            if (isPlayingSafe(p)) { ref = p; break; }
        }
        if (ref == null) return;
        try {
            int rp = ref.getCurrentPosition();
            for (MediaPlayer p : mix.values()) {
                if (p == ref || !isPlayingSafe(p)) continue;
                if (Math.abs(p.getCurrentPosition() - rp) > 120) p.seekTo(rp);
            }
        } catch (Exception ignored) {
        }
    }

    private void seekAll(int ms) {
        for (MediaPlayer p : mix.values()) {
            try { p.seekTo(Math.min(ms, p.getDuration())); } catch (Exception ignored) { }
        }
    }

    /** Ajoute une piste à la lecture, calée sur la position actuelle des autres. */
    private boolean addPlayer(final Stem s, int atMs) {
        if (s.file == null || !s.file.exists()) { toast("Fichier audio introuvable"); return false; }
        MediaPlayer mp = new MediaPlayer();
        try {
            FileInputStream fis = new FileInputStream(s.file);
            try {
                mp.setDataSource(fis.getFD());
            } finally {
                fis.close();
            }
            mp.prepare();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    for (MediaPlayer o : mix.values()) {
                        if (o != m && isPlayingSafe(o)) return; // une autre piste continue
                    }
                    stopPlayback();
                    render(false);
                }
            });
            mix.put(s, mp);
            if (atMs > 0) {
                final MediaPlayer fmp = mp;
                mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                    @Override public void onSeekComplete(MediaPlayer m) {
                        m.setOnSeekCompleteListener(null);
                        if (!mixPaused && mix.containsValue(fmp)) {
                            try { m.start(); } catch (Exception ignored) { }
                        }
                    }
                });
                mp.seekTo(Math.min(atMs, mp.getDuration()));
            } else if (!mixPaused) {
                mp.start();
            }
            return true;
        } catch (Exception e) {
            mix.remove(s);
            try { mp.release(); } catch (Exception ignored) { }
            toast("Lecture impossible : " + e.getMessage());
            return false;
        }
    }

    /** « Écouter seul » : joue uniquement cette piste (ou met en pause / reprend si elle joue déjà seule). */
    private void togglePlay(final Stem s) {
        if (mix.size() == 1 && mix.containsKey(s)) {
            if (mixPaused) resumeAll(); else pauseAll();
            render(false);
            return;
        }
        stopPlayback();
        addPlayer(s, 0);
        render(false);
        ui.post(ticker);
    }

    /** Ajoute ou retire une piste du mix en cours : les pistes jouent ensemble, au même endroit. */
    private void toggleMix(Stem s) {
        if (mix.containsKey(s)) {
            MediaPlayer p = mix.remove(s);
            try { p.stop(); } catch (Exception ignored) { }
            try { p.release(); } catch (Exception ignored) { }
            if (mix.isEmpty()) stopPlayback();
        } else if (mix.isEmpty()) {
            mixPaused = false;
            addPlayer(s, 0);
        } else {
            addPlayer(s, posMs());
        }
        render(false);
        ui.removeCallbacks(ticker);
        if (!mix.isEmpty() && !mixPaused) ui.post(ticker);
    }

    private void togglePauseAll() {
        if (mix.isEmpty()) return;
        if (mixPaused) resumeAll(); else pauseAll();
        render(false);
    }

    // ------------------------------------------------------------ paroles synchronisées
    private List<LyricLine> lyricLines() {
        Stem src = null;
        for (Stem x : stems) {
            if (x.lyrics != null && mix.containsKey(x)) { src = x; break; }
        }
        if (src == null) {
            for (Stem x : stems) if (x.lyrics != null) { src = x; break; }
        }
        if (src == null) return new ArrayList<>();
        if (src.lyrics.equals(lyCacheSrc)) return lyCache;
        List<LyricLine> out = new ArrayList<>();
        Pattern pat = Pattern.compile("^(\\d+):(\\d{1,2}(?:\\.\\d+)?)\\s+(.*)$");
        for (String line : src.lyrics.split("\n")) {
            Matcher m = pat.matcher(line.trim());
            if (m.matches()) {
                double t = Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2));
                out.add(new LyricLine(t, m.group(3)));
            }
        }
        lyCacheSrc = src.lyrics;
        lyCache = out;
        return out;
    }

    private void updateLyrics(double sec, boolean force) {
        if (lyCur == null) return;
        List<LyricLine> lines = lyricLines();
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).t <= sec + 0.15) idx = i; else break;
        }
        if (!force && idx == lyIdx) return;
        lyIdx = idx;
        lyPrev.setText(idx >= 1 ? lines.get(idx - 1).text : "");
        lyCur.setText(idx >= 0 ? lines.get(idx).text : "♪");
        lyNext.setText(idx + 1 < lines.size() ? lines.get(idx + 1).text : "");
    }

    /** Met à jour les vues du lecteur (défilé, horloge, paroles, boutons). */
    private void updateLive(boolean force) {
        if (mix.isEmpty()) return;
        int pos = posMs();
        double sec = pos / 1000.0;
        if (roll != null) {
            roll.setTime(sec);
            if (rollNow != null && (force || tickCount % 3 == 0)) {
                String now = roll.soundingAt(sec);
                if (!now.equals(lastNow)) {
                    lastNow = now;
                    rollNow.setText(now.isEmpty() ? "…" : now);
                }
            }
        }
        for (View v : instViews) {
            if (v instanceof PianoView) ((PianoView) v).setTime(sec);
            else if (v instanceof BassView) ((BassView) v).setTime(sec);
        }
        if (force || tickCount % 15 == 0) {
            if (clockLabel != null) clockLabel.setText(fmtTime(pos) + " / " + fmtTime(durMs()));
            if (seek != null && !seeking) seek.setProgress(pos);
            for (Stem x : stems) {
                if (x.playLabel != null) x.playLabel.setText(playText(x));
            }
        }
        updateLyrics(sec, force);
    }

    /** Tonalité unique du morceau, calculée sur toutes les pistes mélodiques ensemble. */
    private String songKey() {
        List<List<PitchTranscriber.Note>> all = new ArrayList<>();
        for (Stem x : stems) {
            if (x.notes != null && !isDrums(x)) all.add(x.notes);
        }
        return PitchTranscriber.detectKeyMulti(all);
    }

    private static boolean isDrums(Stem x) {
        String n = x.name.toLowerCase(Locale.ROOT);
        return n.contains("batterie") || n.contains("drum");
    }

    /** Notes d'une piste, calées sur la tonalité du morceau si l'option est activée. */
    private List<PitchTranscriber.Note> notesOf(Stem x) {
        if (x.notes == null) return null;
        if (prefs.getBoolean("snap_key", false) && !isDrums(x)) {
            return PitchTranscriber.snapToKey(x.notes, songKey());
        }
        return x.notes;
    }

    /** Carte « Lecteur » : défilé des notes + paroles + commandes, pour les pistes en lecture. */
    private void buildPlayer(LinearLayout col) {
        if (mix.isEmpty()) {
            boolean any = anyNotes();
            for (Stem x : stems) if (x.lyrics != null) any = true;
            if (any) {
                col.addView(Ui.text(this, "Lancez une piste (▶) pour voir les notes et les paroles défiler.",
                        12, Ui.MUTED, false), Ui.lp(M, W, 0, 0, 0, 12));
            }
            return;
        }
        LinearLayout card = vbox();
        card.setBackground(Ui.box(Ui.WHITE, 18, Ui.ORANGE, 2));
        card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(this, "Lecteur", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, W, 1f));
        String key = songKey();
        if (!key.equals("indéterminée")) top.addView(pill("Tonalité : " + key, Ui.YELLOW_SOFT, Ui.INK));
        card.addView(top);

        StringBuilder names = new StringBuilder();
        for (Stem x : mix.keySet()) {
            if (names.length() > 0) names.append("  +  ");
            names.append(x.name);
        }
        card.addView(Ui.text(this, "En lecture : " + names, 12, Ui.MUTED, false), Ui.lp(M, W, 0, 3, 0, 8));

        // défilé des notes
        List<NoteRollView.Track> tracks = new ArrayList<>();
        for (Stem x : mix.keySet()) {
            List<PitchTranscriber.Note> n = notesOf(x);
            if (n != null) tracks.add(new NoteRollView.Track(x.name, STEM_COLORS[stems.indexOf(x) % STEM_COLORS.length], n));
        }
        if (!tracks.isEmpty()) {
            roll = new NoteRollView(this);
            roll.setTracks(tracks, PitchTranscriber.keyScale(key));
            card.addView(roll, new LinearLayout.LayoutParams(M, Ui.dp(180)));
            rollNow = Ui.text(this, "…", 12, Ui.INK, true);
            card.addView(rollNow, Ui.lp(M, W, 0, 6, 0, 0));

            // clavier de piano (avec un doigt) et guitare basse : notes jouées en temps réel
            for (NoteRollView.Track tr : tracks) {
                String ln = tr.name.toLowerCase(Locale.ROOT);
                View inst = null;
                int hDp = 0;
                String title = "";
                if (ln.contains("piano")) {
                    inst = new PianoView(this, tr);
                    hDp = 150;
                    title = "🎹 " + tr.name;
                } else if (ln.contains("basse") || ln.contains("bass")) {
                    inst = new BassView(this, tr);
                    hDp = 130;
                    title = "🎸 " + tr.name;
                }
                if (inst == null) continue;
                card.addView(Ui.text(this, title, 12, Ui.MUTED, true), Ui.lp(M, W, 0, 10, 0, 4));
                card.addView(inst, new LinearLayout.LayoutParams(M, Ui.dp(hDp)));
                instViews.add(inst);
            }
        } else {
            card.addView(Ui.text(this, "Extrayez les notes de ces pistes (bouton orange plus bas) pour les voir défiler.",
                    12, Ui.MUTED, false), Ui.lp(M, W, 0, 0, 0, 4));
        }

        // paroles
        if (!lyricLines().isEmpty()) {
            LinearLayout ly = vbox();
            ly.setBackground(Ui.box(Ui.YELLOW_SOFT, 14, 0, 0));
            ly.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            lyPrev = Ui.text(this, "", 12, Ui.MUTED, false);
            lyCur = Ui.text(this, "♪", 17, Ui.INK, true);
            lyNext = Ui.text(this, "", 12, Ui.MUTED, false);
            lyPrev.setMinLines(1); lyNext.setMinLines(1); lyCur.setMinLines(2);
            ly.addView(lyPrev);
            ly.addView(lyCur, Ui.lp(M, W, 0, 3, 0, 3));
            ly.addView(lyNext);
            card.addView(ly, Ui.lp(M, W, 0, 10, 0, 0));
        }

        // commandes
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        playAllBtn = Ui.text(this, mixPaused ? "▶" : "⏸", 16, Ui.WHITE, true);
        playAllBtn.setGravity(Gravity.CENTER);
        playAllBtn.setBackground(Ui.box(Ui.ORANGE, 14, 0, 0));
        playAllBtn.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        playAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePauseAll(); }
        });
        Ui.pressable(playAllBtn);
        ctl.addView(playAllBtn);
        seek = new SeekBar(this);
        seek.setMax(Math.max(1, durMs()));
        seek.setProgress(posMs());
        seek.setProgressTintList(ColorStateList.valueOf(Ui.ORANGE));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.ORANGE));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) { seekAll(progress); updateLive(true); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { seeking = false; }
        });
        ctl.addView(seek, new LinearLayout.LayoutParams(0, W, 1f));
        clockLabel = Ui.text(this, "", 11, Ui.MUTED, false);
        ctl.addView(clockLabel);
        TextView stop = Ui.text(this, "■", 16, Ui.INK, true);
        stop.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopPlayback(); render(false); }
        });
        ctl.addView(stop);
        card.addView(ctl, Ui.lp(M, W, 0, 10, 0, 0));

        col.addView(card, Ui.lp(M, W, 0, 0, 0, 14));
        updateLive(true);
    }

    // ============================================================ navigation
    private View buildNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.WHITE);
        bar.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        navItems = new LinearLayout[4];
        navIcons = new IconView[4];
        navLabels = new TextView[4];
        for (int i = 0; i < 4; i++) {
            LinearLayout it = vbox();
            it.setGravity(Gravity.CENTER);
            it.setPadding(0, Ui.dp(7), 0, Ui.dp(6));
            IconView ic = new IconView(this, NAV_ICONS[i], Ui.MUTED);
            it.addView(ic, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
            TextView t = Ui.text(this, NAV_LABELS[i], 11, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            it.addView(t, Ui.lp(W, W, 0, 2, 0, 0));
            final int idx = i;
            it.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(idx); }
            });
            navItems[i] = it;
            navIcons[i] = ic;
            navLabels[i] = t;
            bar.addView(it, Ui.lp(0, W, 4, 0, 4, 0));
            ((LinearLayout.LayoutParams) it.getLayoutParams()).weight = 1f;
        }
        return bar;
    }

    private void updateNav() {
        for (int i = 0; i < 4; i++) {
            boolean on = i == tab;
            navItems[i].setBackground(on ? Ui.box(Ui.YELLOW_PILL, 16, 0, 0) : null);
            navIcons[i].setColor(on ? Ui.INK : Ui.MUTED);
            navLabels[i].setTextColor(on ? Ui.INK : Ui.MUTED);
            navLabels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void select(int idx) {
        if (idx == tab) return;
        tab = idx;
        render(true);
    }

    private void openReview(int seg) {
        segment = seg;
        tab = 1;
        render(true);
    }

    // ============================================================ rendu
    private void render(boolean animate) {
        if (renderedTab >= 0 && content.getChildCount() > 0) {
            scrollYs[renderedTab] = content.getChildAt(0).getScrollY();
        }
        homeSepBar = null; homeAnaBar = null; progressBar = null;
        homeSepText = null; homeAnaText = null; progressLabel = null; extractLabel = null;
        homeArt1 = null; homeArt2 = null;
        for (Stem s : stems) { s.bar = null; s.pctLabel = null; s.playLabel = null; s.mixLabel = null; }
        instViews.clear();
        roll = null; rollNow = null; clockLabel = null; lyPrev = null; lyCur = null; lyNext = null;
        playAllBtn = null; seek = null; lyIdx = -2; lastNow = "";

        content.removeAllViews();
        final ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.CREAM);
        LinearLayout col = vbox();
        col.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(28));
        sv.addView(col, new ViewGroup.LayoutParams(M, W));

        switch (tab) {
            case 0: buildHome(col); break;
            case 1: buildReview(col); break;
            case 2: buildHelp(col); break;
            default: buildProfile(col); break;
        }
        content.addView(sv, new FrameLayout.LayoutParams(M, M));
        renderedTab = tab;
        updateNav();

        final int y = scrollYs[tab];
        sv.post(new Runnable() {
            @Override public void run() { sv.scrollTo(0, y); }
        });
        if (animate) {
            sv.setAlpha(0f);
            sv.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void refreshLive() {
        if (homeSepBar != null) {
            homeSepBar.setProgress(sepPct / 100f, true);
            if (homeSepText != null) homeSepText.setText((int) sepPct + " % · " + sepStatus);
        }
        if (homeAnaBar != null) {
            homeAnaBar.setProgress(anaPct, true);
            if (homeAnaText != null) homeAnaText.setText((int) (anaPct * 100) + " % · " + anaStatus);
        }
        if (progressBar != null) progressBar.setProgress(overallFraction(), true);
        if (progressLabel != null) progressLabel.setText(nextStepLabel());
        for (Stem s : stems) {
            if (s.bar != null) s.bar.setProgress(s.notes != null ? 1f : s.pct, true);
            if (s.pctLabel != null) s.pctLabel.setText(pctText(s));
        }
        if (extractLabel != null) extractLabel.setText(extractText());
    }

    private void postLive() {
        ui.post(new Runnable() {
            @Override public void run() { refreshLive(); }
        });
    }

    // ============================================================ état
    private boolean hasToken() {
        return !prefs.getString("token", "").trim().isEmpty();
    }

    private boolean anyNotes() {
        for (Stem s : stems) if (s.notes != null) return true;
        return false;
    }

    private int selectedCount() {
        int n = 0;
        for (Stem s : stems) if (s.selected) n++;
        return n;
    }

    private int doneSteps() {
        int n;
        if (!stems.isEmpty()) n = 3;
        else n = (hasToken() ? 1 : 0) + (songUri != null ? 1 : 0);
        if (anyNotes()) n++;
        if (savedOnce) n++;
        return n;
    }

    private float overallFraction() {
        float f = doneSteps();
        if (sepRunning) f += sepPct / 100f;
        else if (anaRunning) f += anaPct;
        return Math.min(1f, f / 5f);
    }

    private String nextStepLabel() {
        if (sepRunning) return "Séparation en cours…";
        if (anaRunning) return "Analyse en cours…";
        if (!stems.isEmpty()) {
            if (!anyNotes()) return "Étape suivante : extraire les notes";
            if (!savedOnce) return "Étape suivante : enregistrer le MIDI";
            return "Tout est terminé";
        }
        if (!hasToken()) return "Étape suivante : ajouter la clé API";
        if (songUri == null) return "Étape suivante : choisir un morceau";
        return "Étape suivante : lancer la séparation";
    }

    private String pctText(Stem s) {
        return s.notes != null ? "100 %" : (int) (s.pct * 100) + " %";
    }

    private String extractText() {
        if (anaRunning) return "Analyse… " + (int) (anaPct * 100) + " %";
        return "Extraire les notes (" + selectedCount() + ")";
    }

    // ============================================================ ACCUEIL
    private void buildHome(LinearLayout col) {
        col.addView(header("Séparer & transcrire", true));
        col.addView(progressCard());

        // ---- Unité 1 : séparation
        boolean tokenOk = hasToken();
        boolean songOk = songUri != null || !stems.isEmpty();
        boolean sepOk = !stems.isEmpty() && !sepRunning;
        int d1 = (tokenOk ? 1 : 0) + (songOk ? 1 : 0) + (sepOk ? 1 : 0);

        View btn1;
        if (sepRunning) {
            btn1 = pillButton("Séparation… " + (int) sepPct + " %", -1, false, null);
        } else if (!tokenOk) {
            btn1 = pillButton("Ajouter la clé API", -1, true, new View.OnClickListener() {
                @Override public void onClick(View v) { select(3); }
            });
        } else if (songUri == null && stems.isEmpty()) {
            btn1 = pillButton("Choisir un morceau", -1, true, new View.OnClickListener() {
                @Override public void onClick(View v) { pickAudio(REQ_SONG); }
            });
        } else if (stems.isEmpty()) {
            btn1 = pillButton("Lancer la séparation", -1, true, new View.OnClickListener() {
                @Override public void onClick(View v) { startSeparation(); }
            });
        } else {
            LinearLayout two = vbox();
            two.addView(pillButton("Terminé · voir les pistes", -1, true, new View.OnClickListener() {
                @Override public void onClick(View v) { openReview(0); }
            }));
            two.addView(pillButton("Nouvelle séparation", IconView.PLUS, true, new View.OnClickListener() {
                @Override public void onClick(View v) { newSeparation(); }
            }), Ui.lp(M, W, 0, 8, 0, 0));
            btn1 = two;
        }
        homeArt1 = new ArtView(this, Ui.YELLOW_BORDER, Ui.WHITE);
        homeArt1.setActive(sepRunning);
        col.addView(unitCard("Unité 1", "Séparer le morceau",
                "Isole voix, basse, batterie et autres instruments.",
                Ui.YELLOW, Ui.INK, d1, 3, homeArt1, btn1), Ui.lp(M, W, 0, 14, 0, 12));

        col.addView(sectionRow("u1a", 1, "Clé API MVSEP",
                tokenOk ? "Enregistrée" : "À renseigner dans Profil",
                stateOf(tokenOk, true), false, null, new View.OnClickListener() {
                    @Override public void onClick(View v) { select(3); }
                }));
        col.addView(sectionRow("u1b", 2, "Morceau",
                songUri != null ? songName : (songOk ? (!stemsTitle.isEmpty() ? stemsTitle : "Piste importée") : "Choisir un fichier audio"),
                stateOf(songOk, tokenOk), false, null, new View.OnClickListener() {
                    @Override public void onClick(View v) { pickAudio(REQ_SONG); }
                }));
        View sepExtra = sepRunning ? runExtra(true) : null;
        col.addView(sectionRow("u1c", 3, "Séparation",
                sepRunning ? "Envoi et traitement chez MVSEP" : (sepOk ? stems.size() + " pistes prêtes" : "Lancer la séparation"),
                sepRunning ? 1 : stateOf(sepOk, tokenOk && songOk), true, sepExtra, new View.OnClickListener() {
                    @Override public void onClick(View v) { startSeparation(); }
                }));

        if (!savedSongs.isEmpty()) {
            col.addView(savedSongsCard(), Ui.lp(M, W, 0, 4, 0, 4));
        }

        // ---- Unité 2 : extraction des notes
        boolean unlocked2 = !stems.isEmpty();
        boolean notes = anyNotes();
        int d2 = (unlocked2 && selectedCount() > 0 ? 1 : 0) + (notes ? 2 : 0);
        View btn2;
        if (!unlocked2) {
            btn2 = pillButton("Séparez d'abord un morceau", IconView.LOCK, false, null);
        } else if (anaRunning) {
            btn2 = pillButton("Analyse… " + (int) (anaPct * 100) + " %", -1, false, null);
        } else if (notes) {
            btn2 = pillButton("Voir les notes", -1, true, new View.OnClickListener() {
                @Override public void onClick(View v) { openReview(1); }
            });
        } else if (selectedCount() == 0) {
            btn2 = pillButton("Choisir les pistes", -1, true, new View.OnClickListener() {
                @Override public void onClick(View v) { openReview(0); }
            });
        } else {
            btn2 = pillButton("Analyser " + selectedCount() + " piste(s)", -1, true, new View.OnClickListener() {
                @Override public void onClick(View v) { startTranscription(); }
            });
        }
        homeArt2 = new ArtView(this, Ui.OLIVE_DARK, Ui.CREAM);
        homeArt2.setActive(anaRunning);
        col.addView(unitCard("Unité 2", "Extraire les notes",
                "Détecte les notes jouées par chaque instrument choisi.",
                Ui.OLIVE, Ui.INK, d2, 3, homeArt2, btn2), Ui.lp(M, W, 0, 10, 0, 12));

        if (unlocked2) {
            col.addView(sectionRow("u2a", 1, "Choisir les pistes",
                    selectedCount() + " cochée(s) sur " + stems.size(),
                    stateOf(selectedCount() > 0, true), false, null, new View.OnClickListener() {
                        @Override public void onClick(View v) { openReview(0); }
                    }));
            View anaExtra = anaRunning ? runExtra(false) : null;
            col.addView(sectionRow("u2b", 2, "Analyse",
                    anaRunning ? "Détection des notes sur le téléphone" : (notes ? "Terminée" : "Extraire les notes des pistes cochées"),
                    anaRunning ? 1 : stateOf(notes, selectedCount() > 0), false, anaExtra, new View.OnClickListener() {
                        @Override public void onClick(View v) { startTranscription(); }
                    }));
            col.addView(sectionRow("u2c", 3, "Résultats",
                    notes ? "Voir les notes détectées" : "Disponibles après l'analyse",
                    stateOf(notes, notes), true, null, new View.OnClickListener() {
                        @Override public void onClick(View v) { if (notes()) openReview(1); }
                    }));
        }

        // ---- Unité 3 : export
        View btn3;
        if (!notes) {
            btn3 = pillButton("Analysez d'abord des pistes", IconView.LOCK, false, null);
        } else {
            btn3 = pillButton(savedOnce ? "MIDI enregistré · en refaire un" : "Enregistrer le MIDI", -1, true,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { openReview(1); }
                    });
        }
        ArtView art3 = new ArtView(this, 0xFF1F4D38, Ui.YELLOW);
        col.addView(unitCard("Unité 3", "Exporter en MIDI",
                "Un fichier par instrument, ou tout en un seul.",
                Ui.GREEN_DARK, Ui.WHITE, savedOnce ? 1 : 0, 1, art3, btn3), Ui.lp(M, W, 0, 10, 0, 0));

        refreshLive();
    }

    private boolean notes() {
        return anyNotes();
    }

    /** 2 = terminé, 1 = en cours, 0 = verrouillé. */
    private int stateOf(boolean done, boolean available) {
        if (done) return 2;
        return available ? 1 : 0;
    }

    private View runExtra(boolean sep) {
        LinearLayout l = vbox();
        BarView b = new BarView(this, Ui.LINE, Ui.ORANGE);
        l.addView(b, Ui.lp(M, Ui.dp(8), 0, 8, 0, 0));
        TextView t = Ui.text(this, "", 11, Ui.MUTED, false);
        l.addView(t, Ui.lp(M, W, 0, 4, 0, 0));
        if (sep) { homeSepBar = b; homeSepText = t; }
        else { homeAnaBar = b; homeAnaText = t; }
        return l;
    }

    private View progressCard() {
        LinearLayout card = vbox();
        card.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1));
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = vbox();
        texts.addView(Ui.text(this, "Voir ma progression", 12, Ui.MUTED, false));
        progressLabel = Ui.text(this, nextStepLabel(), 15, Ui.INK, true);
        texts.addView(progressLabel, Ui.lp(W, W, 0, 2, 0, 0));
        top.addView(texts, new LinearLayout.LayoutParams(0, W, 1f));
        top.addView(new IconView(this, IconView.ARROW, Ui.INK), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        card.addView(top);

        progressBar = new BarView(this, Ui.TRACK, Ui.ORANGE);
        card.addView(progressBar, Ui.lp(M, Ui.dp(8), 0, 12, 0, 0));
        progressBar.setProgress(overallFraction(), true);
        return card;
    }

    private View unitCard(String label, String title, String sub, int bg, int fg,
                          int done, int count, ArtView art, View button) {
        LinearLayout card = vbox();
        card.setBackground(Ui.box(bg, 22, 0, 0));
        card.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = vbox();
        TextView l = Ui.text(this, label, 11, fg, true);
        l.setAlpha(0.75f);
        left.addView(l);
        left.addView(Ui.text(this, title, 18, fg, true), Ui.lp(W, W, 0, 2, 0, 0));
        TextView s = Ui.text(this, sub, 12, fg, false);
        s.setAlpha(0.85f);
        left.addView(s, Ui.lp(W, W, 0, 4, 0, 0));
        DotsView dots = new DotsView(this);
        dots.set(done, count);
        left.addView(dots, Ui.lp(W, W, 0, 10, 0, 0));
        top.addView(left, new LinearLayout.LayoutParams(0, W, 1f));
        top.addView(art, Ui.lp(Ui.dp(96), Ui.dp(84), 8, 0, 0, 0));
        card.addView(top);
        card.addView(button, Ui.lp(M, W, 0, 14, 0, 0));
        return card;
    }

    private View pillButton(String label, int icon, boolean enabled, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.box(enabled ? Ui.WHITE : 0xB3FFFFFF, 16, 0, 0));
        b.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        if (icon >= 0) {
            b.addView(new IconView(this, icon, enabled ? Ui.INK : Ui.MUTED),
                    Ui.lp(Ui.dp(16), Ui.dp(16), 0, 0, 8, 0));
        }
        b.addView(Ui.text(this, label, 14, enabled ? Ui.INK : Ui.MUTED, true));
        if (enabled && l != null) {
            b.setOnClickListener(l);
            Ui.pressable(b);
        }
        return b;
    }

    private View sectionRow(String key, int number, String title, String sub, int state,
                            boolean last, View extra, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        FrameLayout left = new FrameLayout(this);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        line.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        left.addView(line, new FrameLayout.LayoutParams(Ui.dp(2), M, Gravity.CENTER_HORIZONTAL));

        FrameLayout badge = new FrameLayout(this);
        if (state == 2) {
            badge.setBackground(Ui.oval(Ui.GREEN_OK));
            badge.addView(new IconView(this, IconView.CHECK, Ui.WHITE),
                    new FrameLayout.LayoutParams(Ui.dp(16), Ui.dp(16), Gravity.CENTER));
        } else {
            GradientDrawableHolder.apply(badge, state == 1);
            TextView n = Ui.text(this, String.valueOf(number), 12, state == 1 ? Ui.INK : Ui.MUTED, true);
            n.setGravity(Gravity.CENTER);
            badge.addView(n, new FrameLayout.LayoutParams(M, M));
        }
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(28), Ui.dp(28),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bp.topMargin = Ui.dp(14);
        left.addView(badge, bp);
        row.addView(left, new LinearLayout.LayoutParams(Ui.dp(36), M));

        if (state == 2) {
            if (!seenDone.contains(key)) {
                seenDone.add(key);
                Ui.pop(badge);
            }
        } else {
            seenDone.remove(key);
        }

        LinearLayout card = vbox();
        card.setBackground(Ui.box(Ui.WHITE, 16, state == 1 ? Ui.YELLOW_BORDER : Ui.YELLOW_SOFT, 1.5f));
        card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = vbox();
        texts.addView(Ui.text(this, "Section " + number, 11, Ui.MUTED, false));
        texts.addView(Ui.text(this, title, 15, state == 0 ? Ui.MUTED : Ui.INK, true), Ui.lp(W, W, 0, 1, 0, 0));
        TextView st = Ui.text(this, sub, 12, Ui.MUTED, false);
        st.setMaxLines(1);
        st.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(st, Ui.lp(W, W, 0, 2, 0, 0));
        inner.addView(texts, new LinearLayout.LayoutParams(0, W, 1f));
        IconView trail = new IconView(this,
                state == 0 ? IconView.LOCK : (state == 2 ? IconView.CHECK : IconView.CHEVRON),
                state == 2 ? Ui.GREEN_OK : Ui.MUTED);
        trail.setRotation(state == 1 ? -90f : 0f);
        inner.addView(trail, Ui.lp(Ui.dp(20), Ui.dp(20), 8, 0, 0, 0));
        card.addView(inner);
        if (extra != null) card.addView(extra);
        if (click != null) {
            card.setOnClickListener(click);
            Ui.pressable(card);
        }
        row.addView(card, Ui.lp(0, W, 0, 0, 0, 10));
        ((LinearLayout.LayoutParams) card.getLayoutParams()).weight = 1f;
        return row;
    }

    /** Petit utilitaire pour le fond des pastilles numérotées. */
    private static final class GradientDrawableHolder {
        static void apply(View v, boolean current) {
            android.graphics.drawable.GradientDrawable g = Ui.oval(current ? Ui.WHITE : Ui.LINE);
            if (current) g.setStroke(Ui.dp(2), Ui.YELLOW_BORDER);
            v.setBackground(g);
        }
    }

    // ============================================================ PISTES (Review)
    private void buildReview(LinearLayout col) {
        col.addView(header("Pistes", false));
        col.addView(segmented("Pistes", "Notes", segment));
        buildPlayer(col);
        if (segment == 0) buildStems(col);
        else buildNotes(col);
    }

    private View segmented(String a, String b, int sel) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(Ui.box(Ui.TRACK, 14, 0, 0));
        track.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        String[] labels = {a, b};
        for (int i = 0; i < 2; i++) {
            TextView t = Ui.text(this, labels[i], 14, i == sel ? Ui.INK : Ui.MUTED, i == sel);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(9), 0, Ui.dp(9));
            if (i == sel) t.setBackground(Ui.box(Ui.WHITE, 11, Ui.LINE, 1));
            final int idx = i;
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (segment != idx) { segment = idx; render(false); }
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, W, 1f);
            track.addView(t, p);
        }
        LinearLayout.LayoutParams lp = Ui.lp(M, W, 0, 0, 0, 16);
        track.setLayoutParams(lp);
        return track;
    }

    private void buildStems(LinearLayout col) {
        col.addView(Ui.text(this, "Piste à la une", 16, Ui.INK, true), Ui.lp(W, W, 0, 0, 0, 8));

        LinearLayout feat = new LinearLayout(this);
        feat.setOrientation(LinearLayout.HORIZONTAL);
        feat.setGravity(Gravity.CENTER_VERTICAL);
        feat.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1.5f));
        feat.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(10), Ui.dp(14));
        LinearLayout ft = vbox();
        if (stems.isEmpty()) {
            ft.addView(Ui.text(this, "Aucune piste pour l'instant", 16, Ui.INK, true));
            TextView d = Ui.text(this, "Séparez un morceau depuis l'accueil ou importez une piste déjà isolée.", 12, Ui.MUTED, false);
            ft.addView(d, Ui.lp(W, W, 0, 4, 0, 0));
        } else {
            Stem f = stems.get(0);
            LinearLayout tags = new LinearLayout(this);
            tags.setOrientation(LinearLayout.HORIZONTAL);
            tags.addView(pill(f.poly ? "POLY" : "MONO", Ui.YELLOW_SOFT, Ui.INK));
            tags.addView(pill(f.name, Ui.TRACK, Ui.MUTED), Ui.lp(W, W, 6, 0, 0, 0));
            ft.addView(tags);
            ft.addView(Ui.text(this, f.notes != null ? f.name + " · " + f.notes.size() + " notes" : f.name, 18, Ui.INK, true),
                    Ui.lp(W, W, 0, 8, 0, 0));
            ft.addView(Ui.text(this, f.notes != null ? "Analyse terminée" : "Prête à être analysée", 12, Ui.MUTED, false),
                    Ui.lp(W, W, 0, 3, 0, 0));
        }
        feat.addView(ft, new LinearLayout.LayoutParams(0, W, 1f));
        feat.addView(new ArtView(this, Ui.YELLOW, Ui.OLIVE_DARK), Ui.lp(Ui.dp(76), Ui.dp(68), 8, 0, 0, 0));
        col.addView(feat);

        // « Mes pistes » + bouton Importer
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Mes pistes", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, W, 1f));
        LinearLayout add = new LinearLayout(this);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setBackground(Ui.box(Ui.WHITE, 14, Ui.YELLOW_BORDER, 1));
        add.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        add.addView(new IconView(this, IconView.PLUS, Ui.INK), Ui.lp(Ui.dp(14), Ui.dp(14), 0, 0, 6, 0));
        add.addView(Ui.text(this, "Importer", 13, Ui.INK, true));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAudio(REQ_STEM); }
        });
        Ui.pressable(add);
        head.addView(add);
        col.addView(head, Ui.lp(M, W, 0, 20, 0, 10));

        // grille 2 colonnes
        for (int i = 0; i < stems.size(); i += 2) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.addView(stemCard(stems.get(i)), Ui.lp(0, W, 0, 0, 6, 12));
            ((LinearLayout.LayoutParams) r.getChildAt(0).getLayoutParams()).weight = 1f;
            if (i + 1 < stems.size()) {
                r.addView(stemCard(stems.get(i + 1)), Ui.lp(0, W, 6, 0, 0, 12));
            } else {
                r.addView(new View(this), Ui.lp(0, 1, 6, 0, 0, 0));
            }
            ((LinearLayout.LayoutParams) r.getChildAt(1).getLayoutParams()).weight = 1f;
            col.addView(r);
        }

        if (!stems.isEmpty()) {
            col.addView(pillButtonYellow(saving ? "Enregistrement…" : "Télécharger les pistes (dossier Separonsta)",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { downloadStems(); }
                    }), Ui.lp(M, W, 0, 0, 0, 4));
            col.addView(Ui.text(this, "Chaque piste est enregistrée en MP3 dans Musique/Separonsta/<titre>/<instrument>. "
                    + "Si vous rajoutez le même morceau, elles sont relues sans nouvelle séparation.", 11, Ui.MUTED, false),
                    Ui.lp(M, W, 0, 0, 0, 12));
            TextView b = Ui.text(this, extractText(), 15, Ui.WHITE, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.box(anaRunning ? 0xFFF0B48C : Ui.ORANGE, 18, 0, 0));
            b.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
            if (!anaRunning) {
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { startTranscription(); }
                });
                Ui.pressable(b);
            }
            extractLabel = b;
            col.addView(b, Ui.lp(M, W, 0, 6, 0, 0));
        }

        col.addView(pillButtonYellow("Nouvelle séparation", new View.OnClickListener() {
            @Override public void onClick(View v) { newSeparation(); }
        }), Ui.lp(M, W, 0, 12, 0, 0));
        if (!savedSongs.isEmpty()) {
            col.addView(savedSongsCard(), Ui.lp(M, W, 0, 14, 0, 0));
        }

        for (final Stem s : stems) {
            if (s.lyrics == null) continue;
            LinearLayout lc = vbox();
            lc.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1.5f));
            lc.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
            lc.addView(Ui.text(this, "Paroles · " + s.name, 16, Ui.INK, true));
            TextView t = Ui.text(this, s.lyrics, 13, Ui.INK, false);
            t.setTextIsSelectable(true);
            lc.addView(t, Ui.lp(M, W, 0, 8, 0, 6));
            lc.addView(Ui.text(this, "Écrit par Whisper (Groq) : peut contenir des erreurs.", 11, Ui.MUTED, false));
            col.addView(lc, Ui.lp(M, W, 0, 14, 0, 0));
        }
    }

    // ============================================================ paroles (Groq / Whisper)
    private void startLyrics(final Stem s) {
        final String token = prefs.getString("groq_token", "").trim();
        final String lang = prefs.getString("groq_lang", "").trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            toast("Ajoutez d'abord votre clé API Groq (Profil)");
            select(3);
            return;
        }
        if (s.lyricsBusy) return;
        s.lyricsBusy = true;
        render(false);
        toast("Écriture des paroles en cours…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String txt = GroqClient.transcribe(token, s.file, lang);
                    s.lyrics = txt.isEmpty() ? "Aucune parole détectée." : txt;
                    toastLater("Paroles prêtes : voir en bas de l'onglet Pistes");
                } catch (Exception e) {
                    toastLater("Erreur Groq : " + e.getMessage());
                } finally {
                    s.lyricsBusy = false;
                    ui.post(new Runnable() {
                        @Override public void run() { render(false); }
                    });
                }
            }
        }).start();
    }

    private View stemCard(final Stem s) {
        LinearLayout card = vbox();
        card.setBackground(s.selected ? Ui.box(Ui.WHITE, 18, Ui.ORANGE, 2)
                : Ui.box(Ui.WHITE, 18, Ui.YELLOW_SOFT, 1.5f));
        card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        TextView name = Ui.text(this, s.name, 15, Ui.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);

        TextView play = Ui.text(this, playText(s), 12, Ui.WHITE, true);
        play.setBackground(Ui.box(Ui.ORANGE, 12, 0, 0));
        play.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(s); }
        });
        s.playLabel = play;
        card.addView(play, Ui.lp(W, W, 0, 8, 0, 0));

        boolean inMix = mix.containsKey(s);
        TextView mixBtn = Ui.text(this, mixText(s), 11, inMix ? Ui.WHITE : Ui.INK, true);
        mixBtn.setBackground(Ui.box(inMix ? Ui.GREEN_OK : Ui.YELLOW_SOFT, 12, 0, 0));
        mixBtn.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
        mixBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMix(s); }
        });
        s.mixLabel = mixBtn;
        card.addView(mixBtn, Ui.lp(W, W, 0, 6, 0, 0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String state = anaRunning && s.selected && s.notes == null ? "En analyse…"
                : (s.notes != null ? s.notes.size() + " notes" : "Non analysée");
        row.addView(Ui.text(this, state, 11, Ui.MUTED, false), new LinearLayout.LayoutParams(0, W, 1f));
        s.pctLabel = Ui.text(this, pctText(s), 11, Ui.INK, true);
        row.addView(s.pctLabel);
        card.addView(row, Ui.lp(M, W, 0, 4, 0, 6));

        s.bar = new BarView(this, Ui.TRACK, Ui.ORANGE);
        s.bar.setProgress(s.notes != null ? 1f : s.pct, false);
        card.addView(s.bar, Ui.lp(M, Ui.dp(6), 0, 0, 0, 10));

        TextView mode = Ui.text(this, (s.poly ? "Poly" : "Mono") + " · changer", 11, Ui.INK, true);
        mode.setBackground(Ui.box(Ui.YELLOW_SOFT, 10, 0, 0));
        mode.setPadding(Ui.dp(9), Ui.dp(4), Ui.dp(9), Ui.dp(4));
        mode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (anaRunning) return;
                s.poly = !s.poly;
                render(false);
            }
        });
        card.addView(mode, Ui.lp(W, W, 0, 0, 0, 0));

        TextView ly = Ui.text(this, s.lyricsBusy ? "Paroles…" : (s.lyrics != null ? "Paroles ✓" : "Paroles (Groq)"),
                11, Ui.INK, true);
        ly.setBackground(Ui.box(Ui.YELLOW_SOFT, 10, 0, 0));
        ly.setPadding(Ui.dp(9), Ui.dp(4), Ui.dp(9), Ui.dp(4));
        ly.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startLyrics(s); }
        });
        card.addView(ly, Ui.lp(W, W, 0, 6, 0, 0));

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (anaRunning) return;
                s.selected = !s.selected;
                render(false);
            }
        });
        Ui.pressable(card);
        return card;
    }

    private void buildNotes(LinearLayout col) {
        final List<String> names = new ArrayList<>();
        final List<List<PitchTranscriber.Note>> tracks = new ArrayList<>();

        // Une seule tonalité pour tout le morceau : calculée sur les notes de tous les instruments (sans la batterie)
        final String songKey = songKey();
        final boolean snap = prefs.getBoolean("snap_key", false);
        if (anyNotes()) {
            LinearLayout kc = vbox();
            kc.setBackground(Ui.box(Ui.WHITE, 18, Ui.ORANGE, 2));
            kc.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
            kc.addView(Ui.text(this, "Tonalité du morceau (la même pour tous les instruments)", 12, Ui.MUTED, false));
            kc.addView(Ui.text(this, songKey, 22, Ui.INK, true), Ui.lp(M, W, 0, 2, 0, 0));
            if (PitchTranscriber.keyScale(songKey) != null) {
                kc.addView(Ui.text(this, snap
                        ? "Les notes hors tonalité sont ramenées sur la gamme, dans l'aperçu et dans les fichiers .mid."
                        : "Les notes hors tonalité sont conservées telles que détectées.", 11, Ui.MUTED, false),
                        Ui.lp(M, W, 0, 4, 0, 8));
                kc.addView(pillButtonYellow(snap ? "Caler sur la tonalité : activé" : "Caler sur la tonalité : désactivé",
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                prefs.edit().putBoolean("snap_key", !snap).apply();
                                render(false);
                            }
                        }));
            }
            col.addView(kc, Ui.lp(M, W, 0, 0, 0, 12));
        }

        for (final Stem s : stems) {
            if (s.notes == null) continue;
            names.add(s.name);
            final List<PitchTranscriber.Note> shown = notesOf(s);
            tracks.add(shown);

            LinearLayout card = vbox();
            card.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1.5f));
            card.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(Ui.text(this, s.name, 17, Ui.INK, true), new LinearLayout.LayoutParams(0, W, 1f));
            top.addView(pill(shown.size() + " notes", Ui.YELLOW_SOFT, Ui.INK));
            card.addView(top);
            if (!isDrums(s)) {
                card.addView(Ui.text(this, "Tonalité du morceau : " + songKey, 15, Ui.INK, true),
                        Ui.lp(M, W, 0, 6, 0, 0));
            }
            if (!s.engine.isEmpty()) {
                card.addView(Ui.text(this, "Moteur : " + s.engine, 11, Ui.MUTED, false), Ui.lp(M, W, 0, 2, 0, 0));
            }
            TextView list = Ui.text(this, describe(shown), 12, Ui.INK, false);
            list.setTypeface(Typeface.MONOSPACE);
            list.setTextIsSelectable(true);
            card.addView(list, Ui.lp(M, W, 0, 10, 0, 10));
            card.addView(pillButtonYellow("Enregistrer " + s.name + " en .mid", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    List<List<PitchTranscriber.Note>> t1 = new ArrayList<>();
                    t1.add(shown);
                    saveMidi(MidiWriter.write(Collections.singletonList(s.name), t1), s.name);
                }
            }));
            col.addView(card, Ui.lp(M, W, 0, 0, 0, 12));
        }
        if (tracks.isEmpty()) {
            LinearLayout empty = vbox();
            empty.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_SOFT, 1.5f));
            empty.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(16));
            empty.addView(Ui.text(this, "Aucune note pour l'instant", 16, Ui.INK, true));
            empty.addView(Ui.text(this, "Cochez des pistes dans l'onglet Pistes, puis lancez l'extraction.", 12, Ui.MUTED, false),
                    Ui.lp(W, W, 0, 4, 0, 0));
            col.addView(empty);
        } else if (tracks.size() > 1) {
            TextView b = Ui.text(this, "Tout enregistrer (un seul .mid)", 15, Ui.WHITE, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.box(Ui.ORANGE, 18, 0, 0));
            b.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    saveMidi(MidiWriter.write(names, tracks), "stemnotes_toutes_pistes");
                }
            });
            Ui.pressable(b);
            col.addView(b, Ui.lp(M, W, 0, 4, 0, 0));
        }
    }

    private View pillButtonYellow(String label, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 14, Ui.INK, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.box(Ui.YELLOW_PILL, 14, 0, 0));
        b.setPadding(Ui.dp(12), Ui.dp(11), Ui.dp(12), Ui.dp(11));
        b.setOnClickListener(l);
        Ui.pressable(b);
        return b;
    }

    private TextView pill(String text, int bg, int fg) {
        TextView t = Ui.text(this, text, 11, fg, true);
        t.setBackground(Ui.box(bg, 10, 0, 0));
        t.setPadding(Ui.dp(9), Ui.dp(3), Ui.dp(9), Ui.dp(3));
        return t;
    }

    private String describe(List<PitchTranscriber.Note> notes) {
        if (notes.isEmpty()) return "Aucune note détectée.";
        StringBuilder sb = new StringBuilder();
        int max = Math.min(notes.size(), 40);
        for (int i = 0; i < max; i++) {
            PitchTranscriber.Note n = notes.get(i);
            int min = (int) (n.start / 60);
            double sec = n.start - min * 60;
            sb.append(String.format(Locale.US, "%d:%05.2f  %-6s %.2f s\n",
                    min, sec, PitchTranscriber.noteName(n.pitch), n.end - n.start));
        }
        if (notes.size() > max) sb.append("… +").append(notes.size() - max).append(" dans le .mid");
        return sb.toString().trim();
    }

    // ============================================================ AIDE
    private void buildHelp(LinearLayout col) {
        col.addView(header("Aide", false));
        String[] titles = {"Comment ça marche", "Clé API MVSEP", "Mono ou Poly ?", "Pistes importées", "Limites"};
        String[] bodies = {
                "1. Ajoutez votre clé API dans Profil.\n2. Choisissez un morceau et lancez la séparation.\n3. Cochez les instruments voulus dans Pistes, choisissez Mono ou Poly, puis extrayez les notes.\n4. Enregistrez le fichier .mid.",
                "Créez un compte gratuit sur mvsep.com et copiez la clé depuis mvsep.com/user-api. La séparation passe par une file d'attente : elle peut durer plusieurs minutes.",
                "Mono : voix, basse, mélodie jouée une note à la fois.\nPoly : piano, guitare, accords (plusieurs notes en même temps).",
                "Vous pouvez importer une piste déjà isolée (depuis MVSEP, Vocal Remover…) pour l'analyser sans passer par MVSEP, même hors ligne.",
                "La détection est approximative sur les mélanges denses ; elle marche mieux sur des pistes bien isolées. Pendant l'attente chez MVSEP, la barre est une estimation."
        };
        int[] colors = {Ui.OLIVE, Ui.ORANGE, Ui.GREEN_DARK, Ui.YELLOW_BORDER, Ui.OLIVE_DARK};
        int[] icons = {IconView.HOME, IconView.USER, IconView.BARS, IconView.PLUS, IconView.LOCK};
        for (int i = 0; i < titles.length; i++) {
            col.addView(helpRow(i, titles[i], bodies[i], colors[i], icons[i]), Ui.lp(M, W, 0, 0, 0, 10));
        }
    }

    private View helpRow(final int idx, String title, String body, int color, int icon) {
        LinearLayout card = vbox();
        card.setBackground(Ui.box(Ui.WHITE, 16, Ui.YELLOW_SOFT, 1.5f));
        card.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(Ui.box(color, 10, 0, 0));
        ic.addView(new IconView(this, icon, Ui.WHITE), new FrameLayout.LayoutParams(Ui.dp(18), Ui.dp(18), Gravity.CENTER));
        head.addView(ic, Ui.lp(Ui.dp(36), Ui.dp(36), 0, 0, 12, 0));
        head.addView(Ui.text(this, title, 15, Ui.INK, true), new LinearLayout.LayoutParams(0, W, 1f));
        final IconView chev = new IconView(this, IconView.CHEVRON, Ui.MUTED);
        chev.setRotation(helpOpen[idx] ? 180f : 0f);
        head.addView(chev, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        card.addView(head);

        final TextView tv = Ui.text(this, body, 13, Ui.MUTED, false);
        tv.setLineSpacing(0f, 1.15f);
        tv.setVisibility(helpOpen[idx] ? View.VISIBLE : View.GONE);
        card.addView(tv, Ui.lp(M, W, 0, 10, 0, 0));

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                helpOpen[idx] = !helpOpen[idx];
                tv.setVisibility(helpOpen[idx] ? View.VISIBLE : View.GONE);
                chev.animate().rotation(helpOpen[idx] ? 180f : 0f).setDuration(200).start();
            }
        });
        Ui.pressable(card);
        return card;
    }

    // ============================================================ PROFIL
    private void buildProfile(LinearLayout col) {
        col.addView(header("Profil", false));

        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout av = new FrameLayout(this);
        av.setBackground(Ui.oval(Ui.OLIVE));
        av.addView(new IconView(this, IconView.USER, Ui.WHITE), new FrameLayout.LayoutParams(Ui.dp(32), Ui.dp(32), Gravity.CENTER));
        who.addView(av, Ui.lp(Ui.dp(64), Ui.dp(64), 0, 0, 14, 0));
        LinearLayout wt = vbox();
        wt.addView(Ui.text(this, "Mon espace", 18, Ui.INK, true));
        wt.addView(Ui.text(this, "Réglages et statistiques", 12, Ui.MUTED, false), Ui.lp(W, W, 0, 2, 0, 0));
        who.addView(wt);
        col.addView(who, Ui.lp(M, W, 0, 0, 0, 16));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(statCard("Séparations", prefs.getInt("sep_count", 0)), Ui.lp(0, W, 0, 0, 6, 0));
        stats.addView(statCard("Notes extraites", prefs.getInt("notes_total", 0)), Ui.lp(0, W, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) stats.getChildAt(0).getLayoutParams()).weight = 1f;
        ((LinearLayout.LayoutParams) stats.getChildAt(1).getLayoutParams()).weight = 1f;
        col.addView(stats, Ui.lp(M, W, 0, 0, 0, 16));

        LinearLayout card = vbox();
        card.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1.5f));
        card.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        card.addView(Ui.text(this, "Réglages MVSEP", 16, Ui.INK, true));
        card.addView(field("Clé API (mvsep.com/user-api)", "token", "", false));
        card.addView(field("Type de séparation (sep_type, 63 = chant/basse/batterie/guitare/piano)", "sep_type", "63", true));
        card.addView(field("Option du modèle (add_opt1, facultatif)", "opt1", "", false));
        col.addView(card);

        LinearLayout g = vbox();
        g.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1.5f));
        g.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        g.addView(Ui.text(this, "Réglages Groq (paroles)", 16, Ui.INK, true));
        g.addView(field("Clé API Groq (console.groq.com/keys)", "groq_token", "", false));
        g.addView(field("Langue des paroles (fr, en… vide = automatique)", "groq_lang", "", false));
        col.addView(g, Ui.lp(M, W, 0, 12, 0, 0));
    }

    private View statCard(String label, int value) {
        LinearLayout c = vbox();
        c.setBackground(Ui.box(Ui.YELLOW, 18, 0, 0));
        c.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        c.addView(Ui.text(this, String.valueOf(value), 24, Ui.INK, true));
        c.addView(Ui.text(this, label, 12, Ui.INK, false));
        return c;
    }

    private View field(String label, final String key, String def, boolean numeric) {
        LinearLayout l = vbox();
        l.addView(Ui.text(this, label, 12, Ui.MUTED, false), Ui.lp(W, W, 0, 12, 0, 4));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(Ui.INK);
        e.setBackground(Ui.box(Ui.CREAM, 12, Ui.YELLOW_SOFT, 1.5f));
        e.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(prefs.getString(key, def));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(key, s.toString().trim()).apply();
            }
        });
        l.addView(e, new LinearLayout.LayoutParams(M, W));
        return l;
    }

    // ============================================================ en-tête commun
    private View header(String title, boolean chevron) {
        LinearLayout wrap = vbox();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(Ui.oval(Ui.OLIVE));
        logo.addView(new IconView(this, IconView.NOTE, Ui.WHITE),
                new FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        row.addView(logo, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(chip(false, prefs.getInt("sep_count", 0)));
        row.addView(chip(true, prefs.getInt("notes_total", 0)), Ui.lp(W, W, 8, 0, 0, 0));
        wrap.addView(row);

        LinearLayout trow = new LinearLayout(this);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        trow.addView(Ui.text(this, title, 24, Ui.INK, true));
        if (chevron) trow.addView(new IconView(this, IconView.CHEVRON, Ui.INK), Ui.lp(Ui.dp(22), Ui.dp(22), 4, 0, 0, 0));
        wrap.addView(trow, Ui.lp(M, W, 0, 14, 0, 4));
        return wrap;
    }

    private View chip(boolean red, int value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.box(Ui.WHITE, 18, Ui.YELLOW_BORDER, 1));
        c.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        if (red) {
            FrameLayout b = new FrameLayout(this);
            b.setBackground(Ui.oval(Ui.RED));
            b.addView(new IconView(this, IconView.NOTE, Ui.WHITE),
                    new FrameLayout.LayoutParams(Ui.dp(13), Ui.dp(13), Gravity.CENTER));
            c.addView(b, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        } else {
            c.addView(new IconView(this, IconView.FLAME, Ui.ORANGE), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        }
        c.addView(Ui.text(this, String.valueOf(value), 14, Ui.INK, true), Ui.lp(W, W, 6, 0, 0, 0));
        return c;
    }

    // ============================================================ fichiers
    private void pickAudio(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (req == REQ_SONG) {
            songUri = uri;
            songName = displayName(uri);
            render(false);
        } else if (req == REQ_STEM) {
            if (busy) { toast("Une tâche est en cours"); return; }
            busy = true;
            final String name = displayName(uri);
            final int id = ++importCounter;
            exec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final File f = copyToCache(uri, "import_" + id);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                stems.add(new Stem(name, f));
                                stemsSaved = false;
                                stems.get(stems.size() - 1).poly = guessPoly(name);
                                render(false);
                                toast("Piste importée : " + name);
                            }
                        });
                    } catch (Throwable e) {
                        toastLater("Erreur : " + e.getMessage());
                    } finally {
                        busy = false;
                    }
                }
            });
        } else if (req == REQ_SAVE) {
            try {
                OutputStream os = getContentResolver().openOutputStream(uri);
                try {
                    os.write(pendingMidi);
                } finally {
                    os.close();
                }
                savedOnce = true;
                toast("Fichier MIDI enregistré");
                render(false);
            } catch (Exception e) {
                toast("Échec de l'enregistrement : " + e.getMessage());
            }
        }
    }

    private boolean guessPoly(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        return low.contains("piano") || low.contains("other") || low.contains("guitar")
                || low.contains("guitare") || low.contains("key") || low.contains("instrum")
                || low.contains("music") || low.contains("clavier") || low.contains("synth")
                || low.contains("orgue") || low.contains("organ") || low.contains("cordes")
                || low.contains("string") || low.contains("autres");
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "audio";
    }

    private File copyToCache(Uri uri, String name) throws Exception {
        File out = new File(getCacheDir(), name);
        InputStream in = getContentResolver().openInputStream(uri);
        FileOutputStream os = new FileOutputStream(out);
        try {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) os.write(b, 0, r);
        } finally {
            os.close();
            in.close();
        }
        return out;
    }

    private static void copyFile(File from, File to) throws java.io.IOException {
        InputStream in = new java.io.FileInputStream(from);
        FileOutputStream os = new FileOutputStream(to);
        try {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) os.write(b, 0, r);
        } finally {
            os.close();
            in.close();
        }
    }

    private void saveMidi(byte[] data, String name) {
        pendingMidi = data;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/midi");
        i.putExtra(Intent.EXTRA_TITLE, name.replaceAll("[^A-Za-z0-9_-]", "_") + ".mid");
        startActivityForResult(i, REQ_SAVE);
    }

    // ============================================================ séparation MVSEP
    /** Nom de fichier avec une vraie extension audio (MVSEP refuse un fichier sans extension). */
    private String uploadName(Uri uri) {
        String ext = "";
        String n = songName == null ? "" : songName;
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ext.matches("mp3|wav|flac|m4a|mp4|aac|ogg|opus")) {
            String t = getContentResolver().getType(uri);
            t = t == null ? "" : t.toLowerCase(Locale.ROOT);
            if (t.contains("wav")) ext = "wav";
            else if (t.contains("flac")) ext = "flac";
            else if (t.contains("mp4") || t.contains("m4a")) ext = "m4a";
            else if (t.contains("aac")) ext = "aac";
            else if (t.contains("ogg") || t.contains("opus")) ext = "ogg";
            else ext = "mp3";
        }
        return "input." + ext;
    }

    /** Une séparation MVSEP complète avec un modèle donné : envoi, attente, téléchargement des pistes. */
    private List<Stem> mvsepOnce(String token, File upload, String upName, String sepType, String opt1)
            throws Exception {
        String hash;
        try {
            hash = MvsepClient.create(token, upload, sepType, opt1, upName);
        } catch (java.io.IOException first) {
            sepStatus = "Nouvel essai MVSEP"; postLive();
            Thread.sleep(4000);
            hash = MvsepClient.create(token, upload, sepType, opt1, upName);
        }
        sepPct = 20f; sepStatus = "En file d'attente chez MVSEP"; postLive();

        final int[] polls = {0};
        List<MvsepClient.Remote> files = MvsepClient.waitForResult(hash,
                new MvsepClient.Status() {
                    @Override public void onStatus(String st) {
                        polls[0]++;
                        sepPct = 20f + 65f * (1f - (float) Math.exp(-polls[0] / 60.0));
                        sepStatus = st;
                        postLive();
                    }
                });

        List<Stem> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MvsepClient.Remote r = files.get(i);
            sepPct = 85f + 15f * i / files.size();
            sepStatus = "Téléchargement : " + frName(r.name);
            postLive();
            File f = new File(getCacheDir(), "stem_" + i);
            MvsepClient.download(r.url, f);
            Stem s = new Stem(frName(r.name), f);
            s.poly = guessPoly(r.name);
            String low = r.name.toLowerCase(Locale.ROOT);
            s.selected = !(low.contains("drum") || low.contains("batterie"));
            out.add(s);
        }
        return out;
    }

    /** Nom d'instrument en français à partir du nom renvoyé par MVSEP. */
    private static String frName(String raw) {
        String l = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (l.contains("lead-guitar") || l.contains("lead guitar")) return "Guitare solo";
        if (l.contains("rhythm")) return "Guitare rythmique";
        if (l.contains("acoustic")) return "Guitare acoustique";
        if (l.contains("electric-guitar") || l.contains("electric guitar")) return "Guitare électrique";
        if (l.contains("guitar")) return "Guitare";
        if (l.contains("digital-piano")) return "Piano numérique";
        if (l.contains("piano")) return "Piano";
        if (l.contains("bass")) return "Basse";
        if (l.contains("drum")) return "Batterie";
        if (l.contains("lead") && l.contains("vocal")) return "Chant principal";
        if (l.contains("back") && l.contains("vocal")) return "Chœurs";
        if (l.contains("vocal")) return "Chant";
        if (l.contains("instrum")) return "Instrumental";
        if (l.contains("string")) return "Cordes";
        if (l.contains("wind")) return "Vents";
        if (l.contains("synth")) return "Synthé";
        if (l.contains("organ")) return "Orgue";
        if (l.contains("keys")) return "Claviers";
        if (l.contains("other")) return "Autres instruments";
        return raw == null || raw.isEmpty() ? "Piste" : raw.substring(0, 1).toUpperCase(Locale.ROOT) + raw.substring(1);
    }

    private void startSeparation() {
        if (busy) { toast("Une tâche est en cours"); return; }
        final String token = prefs.getString("token", "").trim();
        String st = prefs.getString("sep_type", "63").trim();
        final String sepType = st.isEmpty() ? "63" : st;
        final String opt1 = prefs.getString("opt1", "").trim();
        if (token.isEmpty()) { toast("Ajoutez d'abord votre clé API (Profil)"); select(3); return; }
        if (songUri == null) { toast("Choisissez d'abord un morceau"); return; }
        final Uri song = songUri;
        final String uploadName = uploadName(song);
        final String title = StemStore.titleOf(songName);

        busy = true;
        sepRunning = true;
        sepPct = 2f;
        sepStatus = "Préparation du fichier";
        render(false);

        final String[] planB = {null};
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    // Même morceau déjà téléchargé dans Separonsta : on relit les pistes au lieu de les séparer
                    List<StemStore.Entry> saved = StemStore.list(getApplicationContext(), title);
                    if (!saved.isEmpty()) {
                        sepStatus = "Pistes retrouvées dans Separonsta"; sepPct = 10f; postLive();
                        List<Stem> found = readSavedStems(title);
                        sepPct = 100f; sepStatus = "Terminé"; sepRunning = false;
                        finishSeparation(found, title, "Pistes retrouvées dans Separonsta : pas de nouvelle séparation", true);
                        return;
                    }

                    File in = copyToCache(song, "input_song");

                    // MVSEP refuse tout ce qui n'est pas un vrai fichier audio : on vérifie le format réel
                    File upload = in;
                    String upName;
                    String ext = AudioDecoder.sniffExt(in);
                    if (ext == null || ext.equals("aac")) {
                        sepPct = 4f; sepStatus = "Conversion du fichier en WAV"; postLive();
                        upload = new File(getCacheDir(), "input_song.wav");
                        try {
                            AudioDecoder.toWav(in, upload);
                        } catch (Exception conv) {
                            throw new Exception("Ce fichier n'est pas un audio lisible (" + conv.getMessage() + ")");
                        }
                        upName = "input.wav";
                    } else {
                        upName = "input." + ext;
                    }

                    // Modèles essayés dans l'ordre : le suivant prend le relais si le précédent échoue
                    List<String[]> attempts = new ArrayList<>();
                    attempts.add(new String[]{sepType, opt1});
                    attempts.add(new String[]{"63", ""});
                    attempts.add(new String[]{"20", "2"});
                    attempts.add(new String[]{"20", ""});
                    List<String[]> uniq = new ArrayList<>();
                    for (String[] a : attempts) {
                        boolean dup = false;
                        for (String[] u : uniq) if (u[0].equals(a[0]) && u[1].equals(a[1])) dup = true;
                        if (!dup) uniq.add(a);
                    }

                    List<Stem> fresh = null;
                    String lastErr = "";
                    for (int a = 0; a < uniq.size() && fresh == null; a++) {
                        try {
                            sepPct = 8f;
                            sepStatus = a == 0 ? "Envoi à MVSEP" : "Autre modèle MVSEP (" + a + "/" + (uniq.size() - 1) + ")";
                            postLive();
                            fresh = mvsepOnce(token, upload, upName, uniq.get(a)[0], uniq.get(a)[1]);
                        } catch (Throwable mvsepFail) {
                            lastErr = String.valueOf(mvsepFail.getMessage());
                            if (lastErr.contains("not an Audio")) break; // un autre modèle n'y changera rien
                        }
                    }
                    if (fresh == null) {
                        // Plan B : MVSEP indisponible -> on analyse le morceau complet sur le téléphone
                        sepStatus = "Plan B : analyse du morceau complet"; postLive();
                        File full = new File(getCacheDir(), "stem_full");
                        copyFile(in, full);
                        fresh = new ArrayList<>();
                        Stem whole = new Stem("Morceau complet", full);
                        whole.poly = true;
                        fresh.add(whole);
                        String why = lastErr;
                        if (why.length() > 110) why = why.substring(0, 110) + "…";
                        planB[0] = "MVSEP indisponible (" + why + ") : le morceau complet est analysé à la place";
                    }
                    sepPct = 100f;
                    sepStatus = "Terminé";
                    sepRunning = false;
                    finishSeparation(fresh, planB[0] != null ? "" : title,
                            planB[0] != null ? planB[0] : "Séparation terminée", false);
                } catch (Throwable e) {
                    sepRunning = false;
                    toastLater("Erreur : " + e.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() { render(false); }
                    });
                } finally {
                    sepRunning = false;
                    busy = false;
                }
            }
        });
    }

    private void finishSeparation(final List<Stem> result, final String title, final String message, final boolean alreadySaved) {
        ui.post(new Runnable() {
            @Override public void run() {
                stopPlayback();
                stems.clear();
                stems.addAll(result);
                stemsTitle = title;
                savedOnce = false;
                stemsSaved = alreadySaved;
                seenDone.clear();
                segment = 0;
                prefs.edit().putInt("sep_count", prefs.getInt("sep_count", 0) + 1).apply();
                render(false);
                toast(message);
            }
        });
    }

    // ============================================================ téléchargement dans Separonsta
    /** Enregistre chaque piste en MP3 : Musique/Separonsta/<titre>/<instrument>/<instrument>.mp3 */
    private void downloadStems() {
        if (stems.isEmpty() || saving) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingAfterPerm = new Runnable() {
                @Override public void run() { downloadStems(); }
            };
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        final String title = !stemsTitle.isEmpty() ? stemsTitle
                : (songName.isEmpty() ? "Morceau importé" : StemStore.titleOf(songName));
        final List<Stem> todo = new ArrayList<>();
        for (Stem x : stems) {
            if (x.file != null && x.file.exists() && !x.name.equals("Morceau complet")) todo.add(x);
        }
        if (todo.isEmpty()) { toast("Aucune piste à enregistrer"); return; }
        saving = true;
        render(false);
        toast("Enregistrement de " + todo.size() + " piste(s)…");
        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0;
                String err = "";
                Set<String> used = new HashSet<>();
                for (Stem x : todo) {
                    String inst = StemStore.safe(x.name);
                    String base = inst;
                    for (int k = 2; used.contains(inst.toLowerCase(Locale.ROOT)); k++) inst = base + " " + k;
                    used.add(inst.toLowerCase(Locale.ROOT));
                    try {
                        StemStore.save(getApplicationContext(), title, inst, x.file);
                        ok++;
                    } catch (Throwable e) {
                        err = String.valueOf(e.getMessage());
                    }
                }
                saving = false;
                if (ok == todo.size()) stemsSaved = true;
                toastLater(ok == todo.size()
                        ? ok + " piste(s) enregistrée(s) dans Musique/" + StemStore.ROOT + "/" + title
                        : ok + "/" + todo.size() + " enregistrée(s). Erreur : " + err);
                ui.post(new Runnable() {
                    @Override public void run() { render(false); }
                });
                refreshSavedSongs();
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_PERM) return;
        Runnable r = pendingAfterPerm;
        pendingAfterPerm = null;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (r != null) r.run();
        } else {
            toast("Autorisation refusée : impossible d'enregistrer sur le téléphone");
        }
    }

    // ============================================================ transcription
    private void startTranscription() {
        if (busy) { toast("Une tâche est en cours"); return; }
        final List<Stem> chosen = new ArrayList<>();
        for (Stem s : stems) {
            if (s.selected) {
                s.notes = null;
                s.pct = 0f;
                chosen.add(s);
            }
        }
        if (chosen.isEmpty()) { toast("Cochez au moins une piste"); return; }

        busy = true;
        anaRunning = true;
        anaPct = 0f;
        anaStatus = "Décodage";
        render(false);

        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    final int n = chosen.size();
                    int total = 0;
                    for (int i = 0; i < n; i++) {
                        final int idx = i;
                        final Stem s = chosen.get(i);
                        anaStatus = s.name + " (" + (i + 1) + "/" + n + ")";
                        postLive();
                        float[] pcm = AudioDecoder.decodeMono(s.file, PitchTranscriber.SAMPLE_RATE);
                        PitchTranscriber.Progress p = new PitchTranscriber.Progress() {
                            @Override public void onProgress(int pct) {
                                s.pct = pct / 100f;
                                anaPct = (idx + pct / 100f) / n;
                                postLive();
                            }
                        };
                        List<PitchTranscriber.Note> res = null;
                        try {
                            res = BasicPitchTranscriber.transcribe(getApplicationContext(), pcm, s.poly, p);
                            s.engine = "Basic Pitch (IA)";
                        } catch (Throwable bpFail) {
                            res = null; // la détection intégrée prend le relais
                        }
                        if (res == null) {
                            s.pct = 0f;
                            res = s.poly
                                    ? PitchTranscriber.transcribePoly(pcm, PitchTranscriber.SAMPLE_RATE, p)
                                    : PitchTranscriber.transcribeMono(pcm, PitchTranscriber.SAMPLE_RATE, p);
                            s.engine = "détection intégrée";
                            if (s.poly && res.size() < 8) {
                                // le mode polyphonique ne trouve presque rien : on essaie la mélodie seule
                                List<PitchTranscriber.Note> mono =
                                        PitchTranscriber.transcribeMono(pcm, PitchTranscriber.SAMPLE_RATE, p);
                                if (mono.size() > res.size()) {
                                    res = mono;
                                    s.engine = "détection intégrée (mélodie)";
                                }
                            }
                        }
                        s.notes = res;
                        s.pct = 1f;
                        total += res.size();
                    }
                    anaPct = 1f;
                    anaStatus = "Terminé";
                    anaRunning = false;
                    final int added = total;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            prefs.edit().putInt("notes_total", prefs.getInt("notes_total", 0) + added).apply();
                            segment = 1;
                            tab = 1;
                            render(true);
                            toast("Analyse terminée : " + added + " notes");
                        }
                    });
                } catch (Throwable e) {
                    anaRunning = false;
                    toastLater("Erreur : " + e.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() { render(false); }
                    });
                } finally {
                    anaRunning = false;
                    busy = false;
                }
            }
        });
    }

    // ============================================================ aides
    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void toastLater(final String s) {
        ui.post(new Runnable() {
            @Override public void run() { toast(s); }
        });
    }
}
