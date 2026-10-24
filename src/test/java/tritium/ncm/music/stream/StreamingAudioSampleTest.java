package tritium.ncm.music.stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.ports.QueueDataEvent;
import repackage.com.jsyn.ports.UnitDataQueueCallback;
import repackage.com.jsyn.unitgen.VariableRateStereoReader;

import javax.sound.sampled.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.FloatBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MP3;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_ALAC;

class StreamingAudioSampleTest {
    @TempDir Path directory;

    @AfterEach void waitForCleanup() throws InterruptedException {
        await(() -> {
            try (var files = Files.list(directory)) { return files.noneMatch(p -> p.toString().endsWith(".part")); }
            catch (IOException e) { return false; }
        });
    }

    @Test void producesAudioBeforeDownloadCompletesAndFreezesClockWhileStarved() throws Exception {
        byte[] audio = wav(8);
        CountDownLatch remainder = new CountDownLatch(1);
        HttpServer server = slowServer(audio, remainder);
        Path cache = directory.resolve("stream.wav");
        StreamingAudioSample sample = null;
        SynthesisEngine synth = new SynthesisEngine();
        synth.setRealTime(false);
        synth.start(44_100, -1, 0, -1, 0);
        try {
            sample = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    StreamingAudioSample.open(HttpAudioCacheTest.url(server), cache, 8_000, () -> false));
            assertTrue(sample.isFrameAvailable(0));
            assertEquals(.5, sample.readDouble(0), 1e-5);
            assertEquals(.25, sample.readDouble(1), 1e-5);
            assertTrue(sample.downloading());
            assertFalse(Files.exists(cache), "Partial files cannot be mistaken for complete cached music");

            VariableRateStereoReader reader = new VariableRateStereoReader();
            synth.add(reader);
            reader.rate.set(44_100);
            synth.generateNextBuffer(); // Apply queued port commands before manual rendering.
            AtomicInteger finished = new AtomicInteger();
            var command = reader.dataQueue.createQueueDataCommand(sample);
            command.setCallback(new UnitDataQueueCallback() {
                public void started(QueueDataEvent event) { }
                public void looped(QueueDataEvent event) { }
                public void finished(QueueDataEvent event) { finished.incrementAndGet(); }
            });
            command.run();
            for (int i = 0; i < 12_000; i++) reader.generate(0, 8);
            long position = reader.dataQueue.getFrameCount();
            for (int i = 0; i < 2_000; i++) reader.generate(0, 8);
            assertEquals(position, reader.dataQueue.getFrameCount(), "An underrun must not consume audio frames");
            assertEquals(0, finished.get(), "Buffering is not EOF");
            assertEquals(0, reader.output.getValues(0)[7], 0, "Buffering must output silence rather than DC");
            remainder.countDown();
            StreamingAudioSample active = sample;
            await(() -> active.isComplete() && Files.exists(cache));
            assertArrayEquals(audio, Files.readAllBytes(cache));
            assertEquals(8 * 44_100, sample.getNumFrames());
            // Seek the sample window ahead and backward without doing disk I/O in the reader.
            await(() -> active.isFrameAvailable(7 * 44_100));
            assertEquals(.5, sample.readDouble(7 * 44_100 * 2), 1e-5);
            await(() -> active.isFrameAvailable((int) position));
            for (int i = 0; i < 1_000; i++) reader.generate(0, 8);
            assertTrue(reader.dataQueue.getFrameCount() > position);
            // Queue the final frames to verify the true EOF fires once.
            reader.dataQueue.clear();
            synth.generateNextBuffer();
            await(() -> active.isFrameAvailable(active.getNumFrames() - 100));
            var end = reader.dataQueue.createQueueDataCommand(sample, sample.getNumFrames() - 100, 100);
            end.setCallback(command.getCallback());
            end.run();
            for (int i = 0; i < 100; i++) reader.generate(0, 8);
            assertEquals(1, finished.get());
        } finally {
            remainder.countDown();
            if (sample != null) sample.cleanUp();
            server.stop(0);
            synth.stop();
        }
        await(() -> {
            try (var files = Files.list(directory)) { return files.noneMatch(p -> p.toString().endsWith(".part")); }
            catch (IOException e) { return false; }
        });
    }

    @Test void cancellingDuringInitialBufferingReturnsPromptlyAndCleansPartialFiles() throws Exception {
        CountDownLatch remainder = new CountDownLatch(1);
        HttpServer server = slowServer(wav(8), remainder);
        try {
            assertThrows(InterruptedIOException.class, () -> StreamingAudioSample.open(
                    HttpAudioCacheTest.url(server), directory.resolve("cancel.wav"), 8_000, () -> true));
        } finally { remainder.countDown(); server.stop(0); }
        await(() -> {
            try (var files = Files.list(directory)) { return files.findAny().isEmpty(); }
            catch (IOException e) { return false; }
        });
    }

    @Test void closingActiveStreamDoesNotWaitForStalledNetworkOrPublishPartialCache() throws Exception {
        CountDownLatch remainder = new CountDownLatch(1);
        HttpServer server = slowServer(wav(8), remainder);
        Path cache = directory.resolve("cancel-active.wav");
        StreamingAudioSample sample = null;
        try {
            sample = StreamingAudioSample.open(HttpAudioCacheTest.url(server), cache, 8_000, () -> false);
            StreamingAudioSample active = sample;
            assertTrue(sample.downloading());
            assertTimeoutPreemptively(Duration.ofMillis(250), active::cleanUp);
            assertFalse(Files.exists(cache));
        } finally {
            if (sample != null) sample.cleanUp();
            remainder.countDown();
            server.stop(0);
        }
    }

    @Test void streamsMp3AndAppleLosslessBeforeTheEntireResponseArrives() throws Exception {
        for (boolean lossless : List.of(false, true)) {
            String extension = lossless ? "m4a" : "mp3";
            Path fixture = directory.resolve("generated." + extension);
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(fixture.toFile(), 1)) {
                recorder.setFormat(lossless ? "ipod" : "mp3");
                recorder.setAudioCodec(lossless ? AV_CODEC_ID_ALAC : AV_CODEC_ID_MP3);
                if (lossless) recorder.setSampleFormat(org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16P);
                recorder.setAudioBitrate(192_000);
                recorder.setSampleRate(44_100);
                recorder.start();
                float[] sine = new float[44_100];
                for (int i = 0; i < sine.length; i++) sine[i] = (float) (.4 * Math.sin(i * 2 * Math.PI * 440 / 44_100));
                for (int i = 0; i < 30; i++) recorder.recordSamples(44_100, 1, FloatBuffer.wrap(sine));
                recorder.stop();
            }
            byte[] audio = Files.readAllBytes(fixture);
            CountDownLatch remainder = new CountDownLatch(1);
            HttpServer server = slowServer(audio, remainder, true);
            StreamingAudioSample sample = null;
            try {
                Path cache = directory.resolve("cache." + extension);
                sample = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                        StreamingAudioSample.open(HttpAudioCacheTest.url(server), cache, 30_000, () -> false));
                assertTrue(sample.downloading(), extension);
                assertFalse(Files.exists(cache));
                assertEquals(2, sample.getChannelsPerFrame(), "Mono source must be converted for the stereo output");
                double energy = 0;
                for (int frame = 0; frame < 5_000; frame++) {
                    assertTrue(sample.isFrameAvailable(frame));
                    energy += Math.abs(sample.readDouble(frame * 2));
                }
                assertTrue(energy > 1, extension);
                remainder.countDown();
                StreamingAudioSample active = sample;
                await(active::isComplete);
                assertEquals(30, sample.getNumFrames() / sample.getFrameRate(), .1);
                assertArrayEquals(audio, Files.readAllBytes(cache));
            } finally {
                if (sample != null) sample.cleanUp();
                remainder.countDown();
                server.stop(0);
            }
        }
    }

    @Test void decodesFlacAndBothRegularAndFragmentedAac() throws Exception {
        for (String fixture : List.of("automix-entry-stereo.flac", "tone.m4a", "tone-fragmented.m4a", "tone-fragmented-base-offset.m4a")) {
            byte[] bytes;
            try (var resource = getClass().getResourceAsStream("/audio/" + fixture)) { bytes = resource.readAllBytes(); }
            HttpServer server = HttpAudioCacheTest.serve(bytes, true, false, new AtomicInteger());
            StreamingAudioSample sample = null;
            try {
                sample = StreamingAudioSample.open(HttpAudioCacheTest.url(server), directory.resolve(fixture), 0, () -> false);
                StreamingAudioSample active = sample;
                await(active::isComplete);
                assertNull(sample.failure(), fixture);
                assertTrue(sample.getNumFrames() >= sample.getFrameRate() / 5, fixture);
                assertTrue(sample.isFrameAvailable(0));
                double energy = 0;
                for (int frame = 0; frame < 5_000; frame++) {
                    if (!sample.isFrameAvailable(frame)) break;
                    energy += Math.abs(sample.readDouble(frame * 2));
                }
                assertTrue(energy > 1, "Decoded PCM must be audible: " + fixture);
                assertArrayEquals(bytes, Files.readAllBytes(directory.resolve(fixture)));
            } finally { if (sample != null) sample.cleanUp(); server.stop(0); }
        }
    }

    static void await(BooleanSupplier predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean(), "Timed out waiting for streaming worker");
    }

    static byte[] wav(int seconds) throws IOException {
        byte[] pcm = new byte[44_100 * seconds * 4];
        for (int i = 0; i < pcm.length; i += 4) { pcm[i + 1] = 64; pcm[i + 3] = 32; }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var input = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(44_100, 16, 2, true, false), pcm.length / 4)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
        }
        return output.toByteArray();
    }

    static HttpServer slowServer(byte[] bytes, CountDownLatch remainder) throws IOException {
        return slowServer(bytes, remainder, false);
    }

    static HttpServer slowServer(byte[] bytes, CountDownLatch remainder, boolean ranges) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/audio", exchange -> {
            int start = 0;
            if (ranges) {
                String range = exchange.getRequestHeaders().getFirst("Range");
                start = Integer.parseInt(range.substring(6, range.length() - 1));
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (bytes.length - 1) + "/" + bytes.length);
            }
            exchange.sendResponseHeaders(ranges ? 206 : 200, bytes.length - start);
            try (var output = exchange.getResponseBody()) {
                int first = Math.min(128 * 1024, bytes.length - start);
                output.write(bytes, start, first);
                output.flush();
                if (start + first < bytes.length) remainder.await(15, TimeUnit.SECONDS);
                output.write(bytes, start + first, bytes.length - start - first);
            } catch (IOException ignored) { }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        server.start();
        return server;
    }
}
