package com.stemnotes;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/** Décode un fichier audio (mp3, wav, flac, m4a...) en PCM mono flottant. */
final class AudioDecoder {

    private AudioDecoder() {}

    static float[] decodeMono(File file, int targetRate) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("Aucune piste audio dans " + file.getName());
            extractor.selectTrack(track);

            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            float[] buf = new float[1 << 20];
            int n = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int size = extractor.readSampleData(ib, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        while (sb.remaining() >= channels) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) sum += sb.get();
                            if (n == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                            buf[n++] = sum / channels / 32768f;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
            }
            return resample(buf, n, rate, targetRate);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Rééchantillonnage par moyenne de surface (évite le repliement lors d'un sous-échantillonnage). */
    private static float[] resample(float[] in, int n, int srcRate, int dstRate) {
        if (srcRate == dstRate) return Arrays.copyOf(in, n);
        double ratio = srcRate / (double) dstRate;
        int outLen = (int) (n / ratio);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double a = i * ratio;
            double b = (i + 1) * ratio;
            int s = (int) Math.floor(a);
            int e = Math.min(n, (int) Math.ceil(b));
            double sum = 0;
            double wsum = 0;
            for (int k = s; k < e; k++) {
                double w = Math.min(k + 1, b) - Math.max(k, a);
                if (w <= 0) continue;
                sum += in[k] * w;
                wsum += w;
            }
            out[i] = wsum > 0 ? (float) (sum / wsum) : 0f;
        }
        return out;
    }
}
