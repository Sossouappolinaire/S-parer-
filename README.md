# StemNotes

Application Android (Java) : sépare un morceau en pistes (voix, basse, batterie, autre…) via l'API MVSEP, puis extrait les notes de chaque instrument choisi et les exporte en fichier MIDI.

## Structure
Tous les fichiers sont à la racine du dépôt, sauf `.github/` (workflow de compilation).

## Compiler sur GitHub
1. Créez un dépôt GitHub et envoyez-y tous ces fichiers (sans dossier, sauf `.github`).
2. Onglet **Actions** → le workflow « Build APK » se lance à chaque push (ou lancez-le à la main).
3. Quand il est vert, ouvrez l'exécution → **Artifacts** → téléchargez `StemNotes-debug-apk`, installez l'APK.

## Utilisation
1. Créez un compte gratuit sur mvsep.com et copiez votre clé API (mvsep.com/user-api).
2. Dans l'app : collez la clé, choisissez le morceau, « Séparer les pistes ».
3. Cochez les pistes voulues, choisissez le mode (monophonique pour voix/basse, polyphonique pour piano/accords), « Extraire les notes ».
4. Enregistrez le `.mid` (une piste ou toutes).

Vous pouvez aussi importer directement une piste déjà isolée (par ex. depuis MVSEP ou Vocal Remover) : l'extraction des notes marche hors ligne.

## Réglages MVSEP
Par défaut `sep_type = 20` (Demucs4 : voix, batterie, basse, autre). Les types et options disponibles sont listés sur https://mvsep.com/full_api ; modifiez les champs dans l'app si vous voulez un autre modèle (ex. un séparateur avec piano).

## Limites
La transcription est faite sur le téléphone (détection de hauteur YIN + analyse spectrale), pas avec Basic Pitch de Spotify : ce dernier demande un modèle d'IA à embarquer. Résultat correct pour voix/basse/solos, approximatif pour le piano dense ou les accords serrés.
