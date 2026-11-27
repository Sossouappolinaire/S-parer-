package com.stemnotes;

/** Donne le nom d'un accord (en français) à partir des notes jouées ensemble. */
final class ChordNamer {

    static final String[] FR = {"Do", "Do♯", "Ré", "Ré♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si"};

    private static final int[][] SHAPES = {
            {0, 4, 7}, {0, 3, 7}, {0, 3, 6}, {0, 4, 8}, {0, 2, 7}, {0, 5, 7},
            {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10}, {0, 3, 6, 10}, {0, 3, 6, 9},
            {0, 4, 7, 9}, {0, 3, 7, 9}, {0, 2, 4, 7}, {0, 7}
    };
    private static final String[] SUFFIX = {
            "", " m", " dim", " aug", " sus2", " sus4",
            " 7", " maj7", " m7", " m7♭5", " dim7",
            " 6", " m6", " add9", " 5"
    };

    static final class Chord {
        final String name;   // ex. « Do », « La m », « Sol 7 / Si »
        final String notes;  // ex. « Do · Mi · Sol »
        final int size;      // nombre de notes différentes

        Chord(String name, String notes, int size) {
            this.name = name;
            this.notes = notes;
            this.size = size;
        }
    }

    private ChordNamer() {}

    static String noteFr(int pitch) {
        return FR[((pitch % 12) + 12) % 12];
    }

    /** pitches : hauteurs MIDI (n premières valeurs). Renvoie null s'il n'y a aucune note. */
    static Chord name(int[] pitches, int n) {
        if (n <= 0) return null;
        int[] sorted = new int[n];
        System.arraycopy(pitches, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);

        StringBuilder nb = new StringBuilder();
        int mask = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            int pc = sorted[i] % 12;
            if ((mask & (1 << pc)) != 0) continue;
            mask |= 1 << pc;
            if (count > 0) nb.append(" · ");
            nb.append(FR[pc]);
            count++;
        }
        int bass = sorted[0] % 12;
        if (count == 1) return new Chord("Note " + FR[bass], nb.toString(), 1);

        double bestScore = -1e9;
        int bestRoot = -1, bestShape = -1;
        for (int r = 0; r < 12; r++) {
            if ((mask & (1 << r)) == 0) continue;
            for (int s = 0; s < SHAPES.length; s++) {
                int shapeMask = 0;
                for (int iv : SHAPES[s]) shapeMask |= 1 << ((r + iv) % 12);
                int match = Integer.bitCount(mask & shapeMask);
                int extra = Integer.bitCount(mask & ~shapeMask);
                int missing = Integer.bitCount(shapeMask & ~mask);
                if (missing > 1) continue;
                if (missing == 1) { // seule la quinte juste peut manquer, et jamais dans un accord de 2 notes
                    int fifth = 1 << ((r + 7) % 12);
                    boolean fifthInShape = (shapeMask & fifth) != 0;
                    boolean fifthMissing = (mask & fifth) == 0;
                    if (!(fifthInShape && fifthMissing) || SHAPES[s].length < 3) continue;
                }
                double score = 3.0 * match - 2.0 * extra - 1.5 * missing + (r == bass ? 0.6 : 0)
                        - 0.05 * s; // à égalité, les accords simples d'abord
                if (score > bestScore) {
                    bestScore = score;
                    bestRoot = r;
                    bestShape = s;
                }
            }
        }
        if (bestRoot < 0) return new Chord(FR[bass] + " + …", nb.toString(), count);
        String name = FR[bestRoot] + SUFFIX[bestShape];
        if (bass != bestRoot) name += " / " + FR[bass];
        return new Chord(name, nb.toString(), count);
    }
}
