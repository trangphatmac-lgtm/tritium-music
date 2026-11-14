package repackage.com.jsyn.util.soundfile;

import com.beatofthedrum.alacdecoder.AlacInputStream;
import com.beatofthedrum.alacdecoder.spi.AlacAudioFileReader;
import net.sourceforge.jaad.mp4.MP4InputStream;
import net.sourceforge.jaad.spi.javasound.AACAudioFileReader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import repackage.com.jsyn.data.FloatSample;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT;

/** Decodes M4A/MP4 audio into seekable JSyn samples. */
public final class M4aSampleLoader {
    public FloatSample loadFloatSample(File file) throws IOException {
        try {
            return loadWithJavaSound(file);
        } catch (IOException | RuntimeException | AssertionError primaryFailure) {
            // Cloud M4A files can contain FLAC and other codecs accepted by the streaming
            // decoder. mp4parser also throws AssertionError for unsupported sample entries.
            try {
                return loadWithFFmpeg(file);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private FloatSample loadWithJavaSound(File file) throws IOException {
        if (FragmentedMp4.isFragmented(file)) {
            // JAAD reads conventional sample tables, not samples stored in moof fragments.
            // Repackage the compressed audio without re-encoding or changing the cached original.
            Path normalized = Files.createTempFile("tritium-m4a-", ".m4a");
            try {
                FragmentedMp4.remux(file, normalized);
                return loadUnfragmented(normalized.toFile());
            } finally {
                Files.deleteIfExists(normalized);
            }
        }
        return loadUnfragmented(file);
    }

    private FloatSample loadWithFFmpeg(File file) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.setSampleFormat(AV_SAMPLE_FMT_FLT);
            grabber.setAudioChannels(2);
            grabber.setOption("protocol_whitelist", "file,pipe");
            grabber.start();
            int rate = grabber.getSampleRate();
            if (!grabber.hasAudio() || rate <= 0 || rate > 384_000)
                throw new IOException("Unsupported M4A audio stream");
            float[] samples = new float[8192];
            int size = 0;
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples == null) continue;
                FloatBuffer values = ((FloatBuffer) frame.samples[0]).duplicate();
                int count = values.remaining();
                if (count % 2 != 0) throw new IOException("Incomplete PCM frame in M4A audio");
                int needed = Math.addExact(size, count);
                if (needed > samples.length)
                    samples = Arrays.copyOf(samples, Math.max(needed, Math.addExact(samples.length, samples.length / 2)));
                values.get(samples, size, count);
                size = needed;
            }
            if (size == 0) throw new IOException("M4A audio contains no samples");
            FloatSample sample = new FloatSample(size / 2, 2);
            sample.write(0, samples, 0, size / 2);
            sample.setFrameRate(rate);
            sample.setPitch(60.0);
            return sample;
        } catch (FFmpegFrameGrabber.Exception | RuntimeException | LinkageError e) {
            throw new IOException("Cannot decode M4A audio: " + file.getName(), e);
        }
    }

    private FloatSample loadUnfragmented(File file) throws IOException {
        // Own seekable inputs so rejected codec probes also close their file handles.
        // MP4 metadata can follow the audio data, so a forward-only stream is insufficient.
        try (MP4InputStream input = MP4InputStream.open(new RandomAccessFile(file, "r"));
             AudioInputStream encoded = new AACAudioFileReader().getAudioInputStream(input);
             AudioInputStream pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
            return readPcm(pcm);
        } catch (UnsupportedAudioFileException e) {
            return loadAppleLossless(file);
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot decode M4A audio: " + file.getName(), e);
        }
    }

    private FloatSample loadAppleLossless(File file) throws IOException {
        try (AlacInputStream input = AlacInputStream.open(new RandomAccessFile(file, "r"));
             AudioInputStream encoded = new AlacAudioFileReader().getAudioInputStream(input);
             AudioInputStream pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
            return readPcm(pcm);
        } catch (UnsupportedAudioFileException | IllegalArgumentException e) {
            throw new IOException("Cannot decode M4A audio: " + file.getName(), e);
        }
    }

    private FloatSample readPcm(AudioInputStream pcm) throws IOException {
        AudioFormat format = pcm.getFormat();
        int channels = format.getChannels();
        int bits = format.getSampleSizeInBits();
        int bytesPerSample = bits / 8;
        int frameSize = format.getFrameSize();
        if ((channels != 1 && channels != 2) || (bits != 16 && bits != 24 && bits != 32)
                || frameSize != channels * bytesPerSample || format.getSampleRate() <= 0) {
            throw new IOException("Unsupported decoded M4A format: " + format);
        }

        byte[] bytes = new byte[4096 * frameSize];
        float[] samples = new float[4096 * channels];
        double scale = Math.scalb(1.0, 1 - bits);
        int size = 0;
        int count;
        while ((count = pcm.read(bytes)) != -1) {
            if (count % frameSize != 0) {
                throw new IOException("Incomplete PCM frame in M4A audio");
            }
            int needed = Math.addExact(size, count / bytesPerSample);
            if (needed > samples.length) {
                samples = Arrays.copyOf(samples, Math.max(needed, Math.addExact(samples.length, samples.length / 2)));
            }
            for (int offset = 0; offset < count; offset += bytesPerSample) {
                int value = 0;
                for (int b = 0; b < bytesPerSample; b++) {
                    int index = format.isBigEndian() ? offset + b : offset + bytesPerSample - 1 - b;
                    value = (value << 8) | (bytes[index] & 0xff);
                }
                // Sign-extend 16/24-bit samples before normalizing; retain ALAC's bit depth.
                value = (value << (32 - bits)) >> (32 - bits);
                samples[size++] = (float) (value * scale);
            }
        }
        if (size == 0) {
            throw new IOException("M4A audio contains no samples");
        }
        FloatSample sample = new FloatSample(size / channels, channels);
        sample.write(0, samples, 0, size / channels);
        sample.setFrameRate(format.getSampleRate());
        sample.setPitch(60.0);
        return sample;
    }
}
