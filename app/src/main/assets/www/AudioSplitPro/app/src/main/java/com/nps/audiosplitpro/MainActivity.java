package com.nps.audiosplitpro;

import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioSplitPro";

    private TextView tvSelectedFile, tvStatus;
    private Button btnPick, btnSeparate, btnPlayVocal, btnDownloadVocal, btnPlayInstru, btnDownloadInstru;
    private View resultBlockVocal, resultBlockInstru;
    private ProgressBar progressBar;

    private Uri selectedUri;
    private File localInputFile;

    private File vocalWavFile, instruWavFile;
    private MediaPlayer vocalPlayer, instruPlayer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> filePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    selectedUri = uri;
                    tvSelectedFile.setText(getFileName(uri));
                    btnSeparate.setEnabled(true);
                    resetResultBlocks();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvSelectedFile = findViewById(R.id.tvSelectedFile);
        tvStatus = findViewById(R.id.tvStatus);
        btnPick = findViewById(R.id.btnPick);
        btnSeparate = findViewById(R.id.btnSeparate);
        progressBar = findViewById(R.id.progressBar);

        resultBlockVocal = findViewById(R.id.resultBlockVocal);
        resultBlockInstru = findViewById(R.id.resultBlockInstru);
        btnPlayVocal = findViewById(R.id.btnPlayVocal);
        btnDownloadVocal = findViewById(R.id.btnDownloadVocal);
        btnPlayInstru = findViewById(R.id.btnPlayInstru);
        btnDownloadInstru = findViewById(R.id.btnDownloadInstru);

        btnPick.setOnClickListener(v -> filePicker.launch("audio/*"));
        btnSeparate.setOnClickListener(v -> runSeparation());

        btnPlayVocal.setOnClickListener(v -> togglePlay(vocalWavFile, true));
        btnPlayInstru.setOnClickListener(v -> togglePlay(instruWavFile, false));

        btnDownloadVocal.setOnClickListener(v -> exportToDownloads(vocalWavFile, "AudioSplitPro_Voix"));
        btnDownloadInstru.setOnClickListener(v -> exportToDownloads(instruWavFile, "AudioSplitPro_Instrumental"));
    }

    private void resetResultBlocks() {
        resultBlockVocal.setVisibility(View.GONE);
        resultBlockInstru.setVisibility(View.GONE);
        stopPlayers();
    }

    private void runSeparation() {
        if (selectedUri == null) return;

        btnSeparate.setEnabled(false);
        btnPick.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(getString(R.string.processing) + " 0%");

        new Thread(() -> {
            try {
                File input = copyUriToCache(selectedUri);

                AudioDecoder.DecodedAudio decoded = AudioDecoder.decode(input.getAbsolutePath());

                short[] vLeft, vRight, iLeft, iRight;

                AudioSeparatorML ml = null;
                try {
                    mainHandler.post(() -> tvStatus.setText("Chargement du modèle IA…"));
                    ml = new AudioSeparatorML(MainActivity.this);

                    mainHandler.post(() -> tvStatus.setText(
                            "Analyse IA en cours (peut prendre plusieurs minutes)… 0%"));

                    AudioSeparatorML.Result mlResult = ml.separate(
                            decoded.left, decoded.right, decoded.sampleRate,
                            percent -> mainHandler.post(() -> tvStatus.setText(
                                    "Analyse IA en cours (peut prendre plusieurs minutes)… " + percent + "%"))
                    );
                    vLeft = mlResult.vocalLeft; vRight = mlResult.vocalRight;
                    iLeft = mlResult.instruLeft; iRight = mlResult.instruRight;

                } catch (Exception mlError) {
                    // Le modèle IA n'a pas pu se charger ou tourner (mémoire insuffisante,
                    // modèle absent des assets, etc.) -> on retombe sur le moteur DSP plutôt
                    // que de planter l'app. On informe clairement l'utilisateur du repli.
                    Log.e(TAG, "Moteur IA indisponible, repli sur le DSP", mlError);
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                            "Modèle IA indisponible, utilisation du moteur de secours (qualité réduite).",
                            Toast.LENGTH_LONG).show());
                    mainHandler.post(() -> tvStatus.setText(getString(R.string.processing) + " 0%"));

                    AudioSeparator.Result dspResult = AudioSeparator.separate(
                            decoded.left, decoded.right, decoded.sampleRate,
                            percent -> mainHandler.post(() ->
                                    tvStatus.setText(getString(R.string.processing) + " " + percent + "%"))
                    );
                    vLeft = dspResult.vocalLeft; vRight = dspResult.vocalRight;
                    iLeft = dspResult.instruLeft; iRight = dspResult.instruRight;
                } finally {
                    if (ml != null) ml.close();
                }

                File outDir = new File(getExternalFilesDir(null), "separated");
                if (!outDir.exists()) outDir.mkdirs();

                File vocalFile = new File(outDir, "vocal.wav");
                File instruFile = new File(outDir, "instrumental.wav");

                WavWriter.write(vocalFile, vLeft, vRight, decoded.sampleRate);
                WavWriter.write(instruFile, iLeft, iRight, decoded.sampleRate);

                mainHandler.post(() -> {
                    vocalWavFile = vocalFile;
                    instruWavFile = instruFile;
                    onSeparationComplete();
                });

            } catch (Exception e) {
                Log.e(TAG, "Erreur de séparation", e);
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this,
                            "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    btnSeparate.setEnabled(true);
                    btnPick.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void onSeparationComplete() {
        progressBar.setVisibility(View.GONE);
        tvStatus.setVisibility(View.GONE);
        btnSeparate.setEnabled(true);
        btnPick.setEnabled(true);
        resultBlockVocal.setVisibility(View.VISIBLE);
        resultBlockInstru.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Séparation terminée !", Toast.LENGTH_SHORT).show();
    }

    private void togglePlay(File file, boolean isVocal) {
        if (file == null) return;
        try {
            MediaPlayer current = isVocal ? vocalPlayer : instruPlayer;
            Button btn = isVocal ? btnPlayVocal : btnPlayInstru;

            if (current != null && current.isPlaying()) {
                current.pause();
                btn.setText(R.string.play);
                return;
            }

            if (current == null) {
                current = new MediaPlayer();
                current.setDataSource(file.getAbsolutePath());
                current.prepare();
                current.setOnCompletionListener(mp -> btn.setText(R.string.play));
                if (isVocal) vocalPlayer = current; else instruPlayer = current;
            }
            current.start();
            btn.setText(R.string.pause);
        } catch (IOException e) {
            Toast.makeText(this, "Impossible de lire le fichier", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayers() {
        if (vocalPlayer != null) { vocalPlayer.release(); vocalPlayer = null; }
        if (instruPlayer != null) { instruPlayer.release(); instruPlayer = null; }
        btnPlayVocal.setText(R.string.play);
        btnPlayInstru.setText(R.string.play);
    }

    private void exportToDownloads(File source, String baseName) {
        if (source == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, baseName + ".wav");
            values.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Impossible de créer le fichier de destination");

            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 InputStream in = new java.io.FileInputStream(source)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            Toast.makeText(this, "Enregistré dans Téléchargements : " + baseName + ".wav", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Erreur d'export : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private File copyUriToCache(Uri uri) throws IOException {
        String name = getFileName(uri);
        File out = new File(getCacheDir(), "input_" + System.currentTimeMillis() + "_" + name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Lecture impossible du fichier sélectionné");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        }
        return out;
    }

    private String getFileName(Uri uri) {
        String result = "audio";
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
            }
        } catch (Exception ignored) { }
        return result;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayers();
    }
}
