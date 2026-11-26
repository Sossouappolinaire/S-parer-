package com.stemnotes;

import android.content.Context;

import java.util.List;

/**
 * Version allégée : le moteur IA Basic Pitch est désactivé pour que l'APK s'installe
 * sur tous les téléphones. L'appel échoue volontairement et la détection intégrée
 * (PitchTranscriber) prend le relais.
 */
final class BasicPitchTranscriber {

    private BasicPitchTranscriber() {}

    static List<PitchTranscriber.Note> transcribe(Context ctx, float[] pcm, boolean poly,
                                                  PitchTranscriber.Progress progress) throws Exception {
        throw new UnsupportedOperationException("Basic Pitch désactivé dans cette version");
    }
}
