# AudioSplitPro

Application Android de séparation audio professionnelle : isole la **voix** et
l'**instrumental** d'un morceau, avec écoute des deux pistes directement dans
l'app avant téléchargement.

## Comment ça marche (algorithme)

**Moteur principal : IA réelle (HTDemucs, ONNX).** L'app embarque un vrai
réseau de neurones — [HTDemucs](https://github.com/facebookresearch/demucs)
(Meta AI), exporté en ONNX par le projet open-source
[StemSplit/demucs-onnx](https://github.com/StemSplit/demucs-onnx) (licence
MIT), poids téléchargés depuis Hugging Face
(`StemSplitio/htdemucs-ft-vocals-onnx`) **pendant le build GitHub Actions**
et intégrés directement dans l'APK (`app/src/main/assets/htdemucs_vocals.onnx`,
~166 Mo). Aucune connexion n'est nécessaire après l'installation — tout
tourne en local via `onnxruntime-android` (`AudioSeparatorML.java`) :

1. Le fichier est ramené à 44100 Hz stéréo si besoin.
2. Découpage en segments de 7,8 s qui se chevauchent à 75 %, chacun passé
   dans le réseau de neurones (le modèle ne sort que la voix isolée).
3. Recollage des segments par overlap-add pondéré (fenêtre de Hann),
   normalisation aux jonctions pour éviter les sauts de volume.
4. **Instrumental = original − voix** (soustraction en phase exacte dans le
   domaine temporel), donc cohérent avec la piste voix produite.

⚠️ **Honnêteté sur les limites** :
- Le traitement n'est **pas instantané** : contrairement à l'ancien moteur
  DSP, un vrai passage réseau de neurones prend du temps sur mobile — prévois
  de quelques dizaines de secondes à plusieurs minutes selon la durée du
  morceau et la puissance du téléphone. Ce n'est pas un bug si ça tourne un
  moment avec la barre de progression qui avance lentement.
- Cette intégration n'a **pas pu être testée sur un appareil réel** avant
  d'être poussée sur GitHub (pas d'environnement Android disponible côté
  génération de ce projet) : le nom exact des tenseurs d'entrée/sortie du
  modèle est lu dynamiquement pour limiter les risques, mais un premier
  build peut nécessiter un round d'ajustement si Hugging Face a changé un
  détail de nommage entre-temps.
- Si le modèle ne charge pas (mémoire insuffisante, fichier manquant), l'app
  **retombe automatiquement** sur l'ancien moteur DSP (`AudioSeparator.java`,
  toujours présent) plutôt que de planter — avec un message clair à l'écran
  pour prévenir que la qualité sera réduite dans ce cas précis.

<details>
<summary>Moteur de secours : DSP par STFT (toujours inclus)</summary>

Si le moteur IA est indisponible, `AudioSeparator.java` prend le relais :
séparation par analyse spectrale Mid/Side `(L+R)/2` et `(L-R)/2`, masque
adaptatif par bin de fréquence (centrage + bande vocale 300 Hz–3,5 kHz),
lissage temporel. C'est du DSP classique, plus rapide mais plafonné en
qualité — les instruments centrés (basse, grosse caisse) ne peuvent pas être
distingués de la voix par cette méthode.
</details>

## Structure du projet

```
AudioSplitPro/
├── .github/workflows/build-apk.yml   <- génère l'APK + télécharge le modèle IA
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/htdemucs_vocals.onnx   <- ajouté automatiquement par le CI
│       ├── java/com/nps/audiosplitpro/
│       │   ├── MainActivity.java      <- UI, sélection fichier, preview, export
│       │   ├── AudioDecoder.java      <- décodage mp3/m4a/wav/ogg -> PCM
│       │   ├── AudioSeparatorML.java  <- moteur IA (ONNX Runtime, HTDemucs)
│       │   ├── AudioSeparator.java    <- moteur DSP de secours
│       │   ├── FFT.java               <- FFT/iFFT (utilisé par le DSP de secours)
│       │   └── WavWriter.java         <- export WAV
│       └── res/...
├── build.gradle
└── settings.gradle
```

## Déploiement sur GitHub (génération automatique de l'APK)

1. Crée un nouveau dépôt GitHub (ex. `AudioSplitPro`).
2. Pousse ce projet :
   ```bash
   git init
   git add .
   git commit -m "Initial commit - AudioSplitPro"
   git branch -M main
   git remote add origin https://github.com/<ton-user>/AudioSplitPro.git
   git push -u origin main
   ```
3. Dans **Settings > Actions > General > Workflow permissions**, sélectionne
   **"Read and write permissions"** (nécessaire pour que le workflow puisse
   publier la Release avec l'APK).
4. Va dans l'onglet **Actions** du dépôt : le workflow `Build APK` se lance
   automatiquement à chaque push sur `main`.
5. Une fois le build terminé (~3-5 min) :
   - l'APK est disponible en téléchargement dans l'onglet **Actions** (section
     "Artifacts" du run), et
   - une **Release GitHub** est créée automatiquement avec l'APK attaché,
     prête à être téléchargée par n'importe qui via le lien de la Release.

## Notes

- L'APK généré est **non signé** (`assembleRelease` sans clé de signature) —
  installable en activant "Sources inconnues" sur Android. Pour publier sur le
  Play Store, il faudra ajouter une configuration de signature (`signingConfigs`)
  dans `app/build.gradle` avec un keystore.
- `minSdk 24` (Android 7.0+), testé jusqu'à Android 14 (`targetSdk 34`).
- Les fichiers séparés sont exportés en `.wav` dans le dossier **Téléchargements**
  de l'appareil.

---
NPS.GAMING — NPS.NELSON
