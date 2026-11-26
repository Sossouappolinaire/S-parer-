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

    /** Devine le vrai format d'après les premiers octets. Renvoie mp3, wav, flac, ogg, aac, ou null si inconnu/conteneur (mp4, 3gp...). */
    static String sniffExt(File f) {
        byte[] h = new byte[12];
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                if (in.read(h) < 12) return null;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'A') return "wav";
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "flac";
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "ogg";
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "mp3";
        int b0 = h[0] & 0xFF, b1 = h[1] & 0xFF;
        if (b0 == 0xFF && (b1 & 0xE0) == 0xE0) {
            if ((b1 & 0xF6) == 0xF0) return "aac";     // ADTS
            if (((b1 >> 1) & 3) != 0) return "mp3";   // trame MPEG audio
        }
        return null; // m4a/mp4/3gp/amr/opus... : on convertira en WAV
    }

    /** Décode n'importe quel audio pris en charge par Android et l'écrit en WAV 16 bits (stéréo, fréquence d'origine). */
    static void toWav(File in, File out) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        java.io.RandomAccessFile raf = null;
        try {
            extractor.setDataSource(in.getAbsolutePath());
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
            if (track < 0) throw new IOException("ce fichier ne contient pas d'audio lisible");
            extractor.selectTrack(track);

            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            long durUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            // MVSEP gratuit : 100 Mo maximum -> mono si le WAV stéréo dépasserait ~90 Mo
            boolean forceMono = durUs > 0 && (durUs / 1e6 * rate * 4.0) > 90e6;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            raf = new java.io.RandomAccessFile(out, "rw");
            raf.setLength(0);
            raf.write(new byte[44]);

            byte[] chunk = new byte[1 << 16];
            int cp = 0;
            long dataBytes = 0;
            int outCh = (channels == 1 || forceMono) ? 1 : 2;
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
                            short s0 = sb.get();
                            short s1 = s0;
                            if (channels >= 2) {
                                s1 = sb.get();
                                for (int c = 2; c < channels; c++) sb.get();
                            }
                            if (cp + 4 > chunk.length) {
                                raf.write(chunk, 0, cp);
                                dataBytes += cp;
                                cp = 0;
                            }
                            if (outCh == 1) {
                                short m = (short) ((s0 + s1) / 2);
                                chunk[cp++] = (byte) (m & 0xFF);
                                chunk[cp++] = (byte) ((m >> 8) & 0xFF);
                            } else {
                                chunk[cp++] = (byte) (s0 & 0xFF);
                                chunk[cp++] = (byte) ((s0 >> 8) & 0xFF);
                                chunk[cp++] = (byte) (s1 & 0xFF);
                                chunk[cp++] = (byte) ((s1 >> 8) & 0xFF);
                            }
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
                    outCh = (channels == 1 || forceMono) ? 1 : 2;
                }
            }
            if (cp > 0) {
                raf.write(chunk, 0, cp);
                dataBytes += cp;
            }
            if (dataBytes == 0) throw new IOException("aucun son décodé");

            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + dataBytes));
            h.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
            h.putShort((short) 1).putShort((short) outCh).putInt(rate);
            h.putInt(rate * outCh * 2).putShort((short) (outCh * 2)).putShort((short) 16);
            h.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) dataBytes);
            raf.seek(0);
            raf.write(h.array());
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignored) { }
            }
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
