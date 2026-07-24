package com.nps.audiosplitpro;

/**
 * Séparation voix / instrumental basée sur analyse spectrale (STFT) en mid/side
 * avec masque adaptatif dépendant de la fréquence, très supérieure à une simple
 * soustraction de phase L-R ("vocal remover" basique) :
 *
 *  1. Décomposition Mid = (L+R)/2, Side = (L-R)/2 par trame STFT (fenêtre de Hann,
 *     recouvrement 75%, reconstruction overlap-add).
 *  2. Calcul d'un ratio de "centrage" par bin fréquentiel : sim = |Side| / (|Mid|+eps)
 *  3. Masque vocal = pondération du centrage x pondération de la bande de fréquence
 *     typique de la voix (200 Hz - 5 kHz), lissé temporellement pour éviter les artefacts.
 *  4. Reconstruction :
 *       - Piste voix   = Mid * masque_vocal            (mono, recentré en L/R)
 *       - Piste instru = Mid * (1 - masque_vocal) + Side (stéréo, largeur restaurée)
 */
public class AudioSeparator {

    private static final int FRAME_SIZE = 4096;
    private static final int HOP = FRAME_SIZE / 4;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static class Result {
        public short[] vocalLeft, vocalRight;
        public short[] instruLeft, instruRight;
    }

    public static Result separate(short[] left, short[] right, int sampleRate, ProgressListener listener) {
        int n = Math.min(left.length, right.length);
        double[] window = hannWindow(FRAME_SIZE);

        double[] vocalOut = new double[n + FRAME_SIZE];
        double[] instruLOut = new double[n + FRAME_SIZE];
        double[] instruROut = new double[n + FRAME_SIZE];
        double[] normSum = new double[n + FRAME_SIZE];

        int numFrames = Math.max(1, (n - FRAME_SIZE) / HOP + 1);

        double[] prevMask = null;

        for (int f = 0; f < numFrames; f++) {
            int start = f * HOP;

            double[] midRe = new double[FRAME_SIZE];
            double[] midIm = new double[FRAME_SIZE];
            double[] sideRe = new double[FRAME_SIZE];
            double[] sideIm = new double[FRAME_SIZE];

            for (int i = 0; i < FRAME_SIZE; i++) {
                int idx = start + i;
                double l = (idx < n) ? left[idx] / 32768.0 : 0.0;
                double r = (idx < n) ? right[idx] / 32768.0 : 0.0;
                double w = window[i];
                midRe[i] = ((l + r) * 0.5) * w;
                sideRe[i] = ((l - r) * 0.5) * w;
            }

            FFT.transform(midRe, midIm, false);
            FFT.transform(sideRe, sideIm, false);

            double[] mask = new double[FRAME_SIZE];
            double eps = 1e-6;

            for (int k = 0; k < FRAME_SIZE; k++) {
                double magMid = Math.hypot(midRe[k], midIm[k]);
                double magSide = Math.hypot(sideRe[k], sideIm[k]);

                double sim = magSide / (magMid + eps);
                double centerScore = 1.0 / (1.0 + sim * sim); // proche de 1 si peu de "side"

                double freqHz = (k <= FRAME_SIZE / 2)
                        ? (k * (double) sampleRate / FRAME_SIZE)
                        : ((FRAME_SIZE - k) * (double) sampleRate / FRAME_SIZE);
                double vocalBandWeight = vocalBandEmphasis(freqHz);

                double m = centerScore * vocalBandWeight;
                m = Math.max(0.0, Math.min(1.0, m));

                if (prevMask != null) {
                    m = 0.6 * m + 0.4 * prevMask[k]; // lissage temporel anti-artefacts
                }
                mask[k] = m;
            }
            prevMask = mask;

            double[] vocRe = new double[FRAME_SIZE];
            double[] vocIm = new double[FRAME_SIZE];
            double[] instMidRe = new double[FRAME_SIZE];
            double[] instMidIm = new double[FRAME_SIZE];

            for (int k = 0; k < FRAME_SIZE; k++) {
                vocRe[k] = midRe[k] * mask[k];
                vocIm[k] = midIm[k] * mask[k];
                instMidRe[k] = midRe[k] * (1.0 - mask[k]);
                instMidIm[k] = midIm[k] * (1.0 - mask[k]);
            }

            FFT.transform(vocRe, vocIm, true);
            FFT.transform(instMidRe, instMidIm, true);
            // sideRe/sideIm already in frequency domain -> back to time domain
            FFT.transform(sideRe, sideIm, true);

            for (int i = 0; i < FRAME_SIZE; i++) {
                int idx = start + i;
                if (idx >= vocalOut.length) break;
                double w = window[i];
                vocalOut[idx] += vocRe[i] * w;
                double instMid = instMidRe[i];
                double side = sideRe[i];
                instruLOut[idx] += (instMid + side) * w;
                instruROut[idx] += (instMid - side) * w;
                normSum[idx] += w * w;
            }

            if (listener != null) {
                int pct = (int) (((f + 1) / (double) numFrames) * 100);
                listener.onProgress(Math.min(99, pct));
            }
        }

        short[] vLeft = new short[n];
        short[] vRight = new short[n];
        short[] iLeft = new short[n];
        short[] iRight = new short[n];

        for (int i = 0; i < n; i++) {
            double norm = normSum[i] > 1e-8 ? normSum[i] : 1.0;
            vLeft[i] = clampToShort(vocalOut[i] / norm);
            vRight[i] = clampToShort(vocalOut[i] / norm);
            iLeft[i] = clampToShort(instruLOut[i] / norm);
            iRight[i] = clampToShort(instruROut[i] / norm);
        }

        Result result = new Result();
        result.vocalLeft = vLeft;
        result.vocalRight = vRight;
        result.instruLeft = iLeft;
        result.instruRight = iRight;

        if (listener != null) listener.onProgress(100);
        return result;
    }

    /** Pondération douce qui privilégie la bande de fréquence typique de la voix humaine. */
    private static double vocalBandEmphasis(double freqHz) {
        double lowCut = 180.0;
        double lowFull = 300.0;
        double highFull = 3500.0;
        double highCut = 6000.0;

        if (freqHz < lowCut || freqHz > highCut) return 0.35; // laisse passer un peu (voix graves/aiguës)
        if (freqHz < lowFull) {
            return 0.35 + 0.65 * (freqHz - lowCut) / (lowFull - lowCut);
        }
        if (freqHz > highFull) {
            return 0.35 + 0.65 * (highCut - freqHz) / (highCut - highFull);
        }
        return 1.0;
    }

    private static double[] hannWindow(int size) {
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1));
        }
        return w;
    }

    private static short clampToShort(double v) {
        double s = v * 32768.0;
        if (s > 32767) s = 32767;
        if (s < -32768) s = -32768;
        return (short) s;
    }
}
