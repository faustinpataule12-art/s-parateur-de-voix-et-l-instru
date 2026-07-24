package com.nps.audiosplitpro;

import android.content.Context;
import android.content.res.AssetManager;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Séparation voix / instrumental par réseau de neurones réel (HTDemucs, export ONNX
 * MIT-licensed, poids "StemSplitio/htdemucs-ft-vocals-onnx"), exécuté 100% sur l'appareil
 * via ONNX Runtime — pas de serveur, pas de connexion nécessaire après l'installation.
 *
 * Principe :
 *  1. Le modèle attend des segments fixes de 343980 échantillons (7.8 s) à 44100 Hz,
 *     stéréo, format planaire [1, 2, 343980] : d'abord tout le canal gauche, puis le droit.
 *  2. On découpe le morceau en segments qui se chevauchent à 75% (hop = segment/4),
 *     on pondère chaque segment par une fenêtre de Hann, on additionne (overlap-add)
 *     et on normalise par la somme des poids — technique standard pour éviter les
 *     "sauts" de volume aux jonctions entre segments.
 *  3. Le modèle ne sort QUE la voix. L'instrumental est obtenu par soustraction :
 *     instrumental = original - voix (dans le domaine temporel, phase exacte).
 *
 * ATTENTION performance : contrairement à l'ancien moteur DSP (quasi instantané),
 * un vrai passage réseau de neurones prend du temps sur un téléphone (de l'ordre de
 * plusieurs dizaines de secondes à quelques minutes selon la durée du morceau et la
 * puissance du CPU). C'est normal et attendu, pas un bug de lenteur.
 */
public class AudioSeparatorML {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHUNK = 343980; // 7.8s * 44100Hz, taille de segment attendue par le modèle
    private static final int HOP = CHUNK / 4;
    private static final String ASSET_MODEL_NAME = "htdemucs_vocals.onnx";

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static class Result {
        public short[] vocalLeft, vocalRight;
        public short[] instruLeft, instruRight;
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String outputName;

    public AudioSeparatorML(Context context) throws IOException, ai.onnxruntime.OrtException {
        byte[] modelBytes = readAsset(context, ASSET_MODEL_NAME);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors()));
        session = env.createSession(modelBytes, opts);

        // On lit les noms réels d'entrée/sortie du graphe plutôt que de les deviner en dur,
        // pour que ça ne casse pas si Hugging Face change légèrement la convention de nommage.
        inputName = session.getInputNames().iterator().next();
        outputName = session.getOutputNames().iterator().next();
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        AssetManager am = context.getAssets();
        try (InputStream is = am.open(name)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] tmp = new byte[1 << 16];
            int read;
            while ((read = is.read(tmp)) != -1) buffer.write(tmp, 0, read);
            return buffer.toByteArray();
        }
    }

    public void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
    }

    public Result separate(short[] leftIn, short[] rightIn, int sourceSampleRate, ProgressListener listener)
            throws ai.onnxruntime.OrtException {

        // 1) Remise à 44100 Hz si besoin (le modèle a été entraîné à ce taux précis)
        float[] leftF, rightF;
        if (sourceSampleRate != SAMPLE_RATE) {
            leftF = resampleLinear(shortToFloat(leftIn), sourceSampleRate, SAMPLE_RATE);
            rightF = resampleLinear(shortToFloat(rightIn), sourceSampleRate, SAMPLE_RATE);
        } else {
            leftF = shortToFloat(leftIn);
            rightF = shortToFloat(rightIn);
        }

        int n = Math.min(leftF.length, rightF.length);
        int pad = CHUNK - (n % CHUNK);
        if (pad == CHUNK) pad = 0;
        int paddedLen = n + pad + CHUNK; // marge de sécurité pour le dernier segment chevauchant

        float[] padL = new float[paddedLen];
        float[] padR = new float[paddedLen];
        System.arraycopy(leftF, 0, padL, 0, n);
        System.arraycopy(rightF, 0, padR, 0, n);

        float[] vocalOut = new float[paddedLen];
        float[] weightSum = new float[paddedLen];
        float[] hann = hannWindow(CHUNK);

        int numSegments = Math.max(1, (paddedLen - CHUNK) / HOP + 1);

        for (int seg = 0; seg < numSegments; seg++) {
            int start = seg * HOP;
            if (start + CHUNK > paddedLen) break;

            // Format attendu par le modèle : planaire [1, 2, CHUNK] (tout L, puis tout R)
            float[] chunkData = new float[2 * CHUNK];
            System.arraycopy(padL, start, chunkData, 0, CHUNK);
            System.arraycopy(padR, start, chunkData, CHUNK, CHUNK);

            long[] shape = new long[]{1, 2, CHUNK};
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunkData), shape)) {
                Map<String, OnnxTensor> inputs = Collections.singletonMap(inputName, inputTensor);
                try (OrtSession.Result output = session.run(inputs)) {
                    float[][][] raw = (float[][][]) output.get(0).getValue(); // [1][2][CHUNK]
                    float[] vocL = raw[0][0];
                    float[] vocR = raw[0][1];

                    for (int i = 0; i < CHUNK; i++) {
                        float w = hann[i];
                        // On ne garde que le canal gauche+droit moyennés en mono voix,
                        // cohérent avec le reste de l'app (piste voix affichée en mono).
                        float v = ((vocL[i] + vocR[i]) * 0.5f) * w;
                        vocalOut[start + i] += v;
                        weightSum[start + i] += w;
                    }
                }
            }

            if (listener != null) {
                int pct = (int) (((seg + 1) / (double) numSegments) * 100);
                listener.onProgress(Math.min(99, pct));
            }
        }

        // Normalisation overlap-add + retour à la longueur d'origine
        float[] vocalTrim = new float[n];
        for (int i = 0; i < n; i++) {
            float ws = weightSum[i] > 1e-6f ? weightSum[i] : 1f;
            vocalTrim[i] = vocalOut[i] / ws;
        }

        // Ré-échantillonnage vers le taux d'origine si nécessaire
        float[] vocalFinal = (sourceSampleRate != SAMPLE_RATE)
                ? resampleLinear(vocalTrim, SAMPLE_RATE, sourceSampleRate)
                : vocalTrim;

        int origN = Math.min(leftIn.length, rightIn.length);
        short[] vocalL = new short[origN];
        short[] vocalR = new short[origN];
        short[] instruL = new short[origN];
        short[] instruR = new short[origN];

        for (int i = 0; i < origN; i++) {
            float v = (i < vocalFinal.length) ? vocalFinal[i] : 0f;
            short vs = clampToShort(v);
            vocalL[i] = vs;
            vocalR[i] = vs;
            // Instrumental = original - voix (soustraction en phase, dans le domaine temporel)
            instruL[i] = clampToShort((leftIn[i] / 32768f) - v);
            instruR[i] = clampToShort((rightIn[i] / 32768f) - v);
        }

        Result result = new Result();
        result.vocalLeft = vocalL;
        result.vocalRight = vocalR;
        result.instruLeft = instruL;
        result.instruRight = instruR;
        if (listener != null) listener.onProgress(100);
        return result;
    }

    private static float[] shortToFloat(short[] s) {
        float[] f = new float[s.length];
        for (int i = 0; i < s.length; i++) f[i] = s[i] / 32768f;
        return f;
    }

    private static short clampToShort(double v) {
        double s = v * 32768.0;
        if (s > 32767) s = 32767;
        if (s < -32768) s = -32768;
        return (short) s;
    }

    private static float[] hannWindow(int size) {
        float[] w = new float[size];
        for (int i = 0; i < size; i++) {
            w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1)));
        }
        return w;
    }

    /** Ré-échantillonnage simple par interpolation linéaire (suffisant ici : le modèle
     *  tolère de petites imprécisions bien mieux qu'un décalage de fréquence pur). */
    private static float[] resampleLinear(float[] input, int fromRate, int toRate) {
        if (fromRate == toRate || input.length == 0) return input;
        int outLen = (int) ((long) input.length * toRate / fromRate);
        float[] out = new float[outLen];
        double ratio = (double) input.length / outLen;
        for (int i = 0; i < outLen; i++) {
            double srcPos = i * ratio;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, input.length - 1);
            float frac = (float) (srcPos - i0);
            out[i] = input[i0] * (1 - frac) + input[i1] * frac;
        }
        return out;
    }
}
