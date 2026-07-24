package com.nps.audiosplitpro;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavWriter {

    public static void write(File outFile, short[] left, short[] right, int sampleRate) throws IOException {
        int n = Math.min(left.length, right.length);
        int byteRate = sampleRate * 2 * 2; // stereo, 16-bit
        int dataSize = n * 2 * 2;

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes());
            header.putInt(36 + dataSize);
            header.put("WAVE".getBytes());
            header.put("fmt ".getBytes());
            header.putInt(16);
            header.putShort((short) 1); // PCM
            header.putShort((short) 2); // channels
            header.putInt(sampleRate);
            header.putInt(byteRate);
            header.putShort((short) 4); // block align
            header.putShort((short) 16); // bits per sample
            header.put("data".getBytes());
            header.putInt(dataSize);
            fos.write(header.array());

            ByteBuffer body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                body.putShort(left[i]);
                body.putShort(right[i]);
            }
            fos.write(body.array());
        }
    }
}
