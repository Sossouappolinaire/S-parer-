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

## Lecteur animé
- **Piano** : deux mains. La main gauche (bleue) joue les accords : son nom et ses notes s'affichent en haut (ex. « Do m · Do · Mi♭ · Sol »). La main droite (orange) joue la mélodie ou accompagne avec des accords ; l'indication « mélodie / accompagnement » change en direct.
- **Batterie** : analysée par détection des coups (grosse caisse, caisse claire, charleston, 3 toms, crash, ride). Le lecteur montre la batterie, deux baguettes avec leurs mains, et le pied sur la pédale de grosse caisse. Le .mid de la batterie est écrit sur le canal 10.

## Réglages MVSEP
Par défaut `sep_type = 20` (Demucs4 : voix, batterie, basse, autre). Les types et options disponibles sont listés sur https://mvsep.com/full_api ; modifiez les champs dans l'app si vous voulez un autre modèle (ex. un séparateur avec piano).
Pour mieux isoler le piano des autres instruments, essayez `sep_type = 30` (Ensemble All-In : chant, basse, batterie, piano, guitare…) : plus lent, mais plus propre que `63`.

## Limites
La transcription est faite sur le téléphone (détection de hauteur YIN + analyse spectrale), pas avec Basic Pitch de Spotify : ce dernier demande un modèle d'IA à embarquer. Résultat correct pour voix/basse/solos, approximatif pour le piano dense ou les accords serrés.

## Séparation plus propre (v1.4)
- Le modèle multi-pistes MVSEP (sep_type 63) sépare d'abord le morceau, puis chaque instrument est **affiné par un modèle MVSEP dédié** : basse (41), batterie (44), piano (29), guitare (31). Moins de mélange entre instruments, lecture plus claire.
- Réglage « Affinage » (Profil) : 2 = les quatre instruments (défaut), 1 = basse seulement, 0 = aucun (plus rapide).
- Plus de « plan B » : si MVSEP est indisponible, l'app le dit au lieu d'analyser le morceau complet (tous instruments mélangés).
- Basse : détection dédiée (filtre passe-bas 450 Hz, plage Mi1–Sol4, notes plus longues et plus nettes) pour éviter les fausses notes dues aux fuites de grosse caisse ou aux aigus.
- Les pistes « Instrumental » (mélange) ne sont plus cochées par défaut.

## Touba
Le Touba (congas) est séparé de la batterie : vue dédiée (Touba grave à gauche, Touba aigu à droite, deux mains), carte et piste .mid à part. La batterie ne garde que grosse caisse, caisse claire, charleston et cymbales.

## Icône
Les fichiers `ic_launcher*.png/.xml` à la racine sont copiés dans `res/` au moment de la compilation (voir `build.gradle`), pour garder le dépôt sans sous-dossier.

## Bibliothèque hors ligne
- Après la séparation, les pistes sont **enregistrées automatiquement** dans `Musique/Separonsta/<titre>/<instrument>/`.
- Après l'analyse, les notes (et les paroles si générées) sont enregistrées automatiquement dans la mémoire de l'app.
- La carte « Morceaux déjà séparés · hors ligne » (accueil et onglet Pistes) liste les morceaux ; un appui rouvre pistes, notes et paroles **sans internet**. « analysé ✓ » indique que les notes sont aussi disponibles.
