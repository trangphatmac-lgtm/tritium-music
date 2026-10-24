package tritium.ncm.music.stream;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.data.ProgressiveData;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT;

/** Decode as bytes arrive, keeping PCM on temporary disk and a two-second window in RAM.
 * Network, decoding and disk reads never execute on JSyn's audio thread. */
public final class StreamingAudioSample extends FloatSample implements ProgressiveData {
    private record Window(int start, float[] samples, int channels) {
        int end() { return start + samples.length / channels; }
        boolean contains(int frame) { return frame >= start && frame < end(); }
    }
    private final HttpAudioCache input;
    private final Path pcm;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CountDownLatch workers = new CountDownLatch(2);
    private volatile int decodedFrames, requestedFrame, estimatedFrames;
    private volatile boolean complete, closed;
    private volatile IOException failure;
    private volatile Window window;
    private Window renderWindow;
    private final Thread decoder, prefetch;

    private StreamingAudioSample(String url, Path cache, long durationMillis) throws IOException {
        input = new HttpAudioCache(url, cache);
        try { pcm = Files.createTempFile(cache.toAbsolutePath().getParent(), "stream-pcm-", ".part"); }
        catch (IOException e) { input.close(); throw e; }
        decoder = Thread.ofPlatform().daemon().name("music-stream-decoder").unstarted(() -> decode(durationMillis));
        prefetch = Thread.ofPlatform().daemon().name("music-stream-buffer").unstarted(this::prefetch);
        decoder.start();
        prefetch.start();
    }

    public static StreamingAudioSample open(String url, Path cache, long durationMillis,
                                            BooleanSupplier cancelled) throws IOException, InterruptedException {
        StreamingAudioSample sample = new StreamingAudioSample(url, cache, durationMillis);
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Playback cancelled");
                try { sample.ready.get(50, TimeUnit.MILLISECONDS); return sample; }
                catch (TimeoutException ignored) { }
                catch (ExecutionException e) { throw new IOException("Cannot stream audio", e.getCause()); }
            }
        } catch (IOException | InterruptedException | RuntimeException e) { sample.cleanUp(); throw e; }
    }

    private void decode(long durationMillis) {
        try (FileChannel output = FileChannel.open(pcm, StandardOpenOption.WRITE)) {
            input.open();
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input, input.seekable() ? Integer.MAX_VALUE - 8 : 0)) {
                grabber.setCloseInputStream(false);
                grabber.setSampleFormat(AV_SAMPLE_FMT_FLT);
                grabber.setAudioChannels(2);
                grabber.setOption("probesize", "32768");
                grabber.setOption("analyzeduration", "100000");
                grabber.setOption("protocol_whitelist", "file,pipe");
                grabber.start();
                int rate = grabber.getSampleRate();
                if (rate <= 0 || rate > 384_000 || !grabber.hasAudio()) throw new IOException("Unsupported audio stream");
                setFrameRate(rate);
                channelsPerFrame = 2;
                double seconds = durationMillis > 0 ? durationMillis / 1000.0 : grabber.getLengthInTime() / 1e6;
                estimatedFrames = (int) Math.min(Integer.MAX_VALUE / 2, Math.max(rate, Math.ceil(seconds * rate)));
                Frame frame;
                while (!closed && (frame = grabber.grabSamples()) != null) {
                    if (frame.samples == null) continue;
                    FloatBuffer values = ((FloatBuffer) frame.samples[0]).duplicate();
                    int count = values.remaining();
                    if (count % 2 != 0 || (long) decodedFrames + count / 2 >= Integer.MAX_VALUE / 2)
                        throw new IOException("Audio stream is too long");
                    ByteBuffer bytes = ByteBuffer.allocate(count * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                    bytes.asFloatBuffer().put(values);
                    while (bytes.hasRemaining()) output.write(bytes);
                    decodedFrames += count / 2; // Volatile publication happens after the complete PCM write.
                }
                if (closed) return;
                if (decodedFrames == 0) throw new IOException("No audio frames decoded");
            }
            // Finish and validate the compressed cache before declaring a successful EOF.
            input.commit();
            complete = true;
        } catch (Exception | LinkageError e) {
            if (!closed) fail(e);
        } finally {
            try { input.close(); } catch (IOException ignored) { }
            workers.countDown();
        }
    }

    private void prefetch() {
        try (FileChannel reader = FileChannel.open(pcm, StandardOpenOption.READ)) {
            while (!closed && failure == null) {
                int rate = (int) getFrameRate();
                int target = requestedFrame;
                int available = decodedFrames;
                Window current = window;
                boolean needsWindow = current == null || !current.contains(target)
                        || current.end() - target < rate / 2;
                int ahead = available - target;
                if (needsWindow && ahead > 0 && (ahead >= rate / 4 || complete)) {
                    int start = Math.max(0, target - rate / 4);
                    int end = Math.min(available, start + rate * 2);
                    if (current == null || start != current.start() || end != current.end()) {
                        ByteBuffer bytes = ByteBuffer.allocate((end - start) * 2 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                        long offset = (long) start * 2 * Float.BYTES;
                        while (bytes.hasRemaining()) {
                            int n = reader.read(bytes, offset + bytes.position());
                            if (n < 0) throw new EOFException("PCM buffer truncated");
                        }
                        bytes.flip();
                        float[] samples = new float[(end - start) * 2];
                        bytes.asFloatBuffer().get(samples);
                        window = new Window(start, samples, 2);
                        ready.complete(null);
                    }
                }
                Thread.sleep(10);
            }
        } catch (Exception e) { if (!closed) fail(e); }
        finally { workers.countDown(); }
    }

    private void fail(Throwable e) {
        failure = e instanceof IOException io ? io : new IOException("Audio decoding failed", e);
        ready.completeExceptionally(failure);
    }

    @Override public int getNumFrames() { return complete ? decodedFrames : Math.max(estimatedFrames, decodedFrames); }
    @Override public boolean isComplete() { return complete; }
    @Override public boolean isFrameAvailable(int frame) {
        requestedFrame = frame;
        Window current = window;
        if (closed || current == null || !current.contains(frame)) return false;
        renderWindow = current; // Pin both channels to the same immutable window for this frame.
        return true;
    }
    @Override public double readDouble(int index) {
        Window current = renderWindow;
        int offset = current == null ? -1 : index - current.start() * 2;
        return offset >= 0 && offset < current.samples().length ? current.samples()[offset] : 0;
    }
    public IOException failure() { return failure; }
    public boolean downloading() { return !closed && failure == null && !input.committed(); }
    public double downloadProgress() { return input.progress(); }
    public double downloadBytesPerSecond() { return input.bytesPerSecond(); }

    @Override public synchronized void cleanUp() {
        if (closed) return;
        closed = true;
        input.cancel();
        decoder.interrupt();
        prefetch.interrupt();
        // Joining or closing an active network/native decoder on JSyn's thread can deadlock playback.
        Thread.startVirtualThread(() -> {
            try { workers.await(); Files.deleteIfExists(pcm); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException e) { System.err.println("Cannot remove streaming PCM: " + e.getMessage()); }
            window = renderWindow = null;
        });
    }
}
