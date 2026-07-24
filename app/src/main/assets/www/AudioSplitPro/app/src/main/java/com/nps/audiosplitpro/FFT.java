package com.nps.audiosplitpro;

/**
 * FFT/iFFT radix-2 itérative (Cooley-Tukey) sur tableaux de doubles (partie réelle / imaginaire).
 * n doit être une puissance de 2.
 */
public class FFT {

    public static void transform(double[] re, double[] im, boolean invert) {
        int n = re.length;

        // Bit-reversal
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (invert ? 1 : -1);
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curWRe = 1.0, curWIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i + j];
                    double uIm = im[i + j];
                    double vRe = re[i + j + len / 2] * curWRe - im[i + j + len / 2] * curWIm;
                    double vIm = re[i + j + len / 2] * curWIm + im[i + j + len / 2] * curWRe;

                    re[i + j] = uRe + vRe;
                    im[i + j] = uIm + vIm;
                    re[i + j + len / 2] = uRe - vRe;
                    im[i + j + len / 2] = uIm - vIm;

                    double nextWRe = curWRe * wRe - curWIm * wIm;
                    double nextWIm = curWRe * wIm + curWIm * wRe;
                    curWRe = nextWRe;
                    curWIm = nextWIm;
                }
            }
        }

        if (invert) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    public static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}
