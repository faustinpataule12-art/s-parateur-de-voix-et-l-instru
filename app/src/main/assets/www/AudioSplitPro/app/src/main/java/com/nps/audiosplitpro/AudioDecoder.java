package com.nps.audiosplitpro;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Décode n'importe quel fichier audio supporté par Android (mp3, m4a, wav, ogg, flac...)
 * en PCM 16 bits stéréo, quel que soit le format source, via MediaExtractor + MediaCodec.
 */
public class AudioDecoder {

    public static class DecodedAudio {
        public short[] left;
        public short[] right;
        public int sampleRate;
    }

    public static DecodedAudio decode(String filePath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(filePath);

        int audioTrackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                format = f;
                break;
            }
        }

        if (audioTrackIndex == -1 || format == null) {
            throw new IOException("Aucune piste audio trouvée dans le fichier.");
        }

        extractor.selectTrack(audioTrackIndex);

        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        ArrayList<Short> pcmLeft = new ArrayList<>();
        ArrayList<Short> pcmRight = new ArrayList<>();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone) {
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(inBuf, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long presentationTime = extractor.getSampleTime();
                        codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTime, 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = codec.dequeueOutputBuffer(info, 10000);
            if (outIndex >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                byte[] chunk = new byte[info.size];
                if (outBuf != null && info.size > 0) {
                    outBuf.get(chunk);
                    outBuf.clear();

                    ByteBuffer bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
                    int samplesInChunk = chunk.length / 2; // 16-bit
                    if (channelCount == 1) {
                        for (int i = 0; i < samplesInChunk; i++) {
                            short s = bb.getShort();
                            pcmLeft.add(s);
                            pcmRight.add(s);
                        }
                    } else {
                        for (int i = 0; i < samplesInChunk / 2; i++) {
                            pcmLeft.add(bb.getShort());
                            pcmRight.add(bb.getShort());
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        DecodedAudio result = new DecodedAudio();
        result.sampleRate = sampleRate;
        result.left = toShortArray(pcmLeft);
        result.right = toShortArray(pcmRight);
        return result;
    }

    private static short[] toShortArray(ArrayList<Short> list) {
        short[] arr = new short[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }
}
