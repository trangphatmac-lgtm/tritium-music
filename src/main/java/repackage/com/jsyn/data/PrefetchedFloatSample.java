package repackage.com.jsyn.data;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** A bounded, immutable PCM window prepared off the audio thread; other positions use the source.
 * Ownership of the source transfers only when preparation succeeds. */
public final class PrefetchedFloatSample extends FloatSample {
    public static final int MAX_CACHE_BYTES = 16 * 1024 * 1024;
    private final FloatSample source;
    private final int firstSample;
    private float[] cached;
    private boolean closed;

    private PrefetchedFloatSample(FloatSample source, int firstSample, float[] cached) {
        this.source = source;
        this.firstSample = firstSample;
        this.cached = cached;
        numFrames = source.getNumFrames();
        channelsPerFrame = source.getChannelsPerFrame();
        setFrameRate(source.getFrameRate());
        setPitch(source.getPitch());
        setSustainBegin(source.getSustainBegin());
        setSustainEnd(source.getSustainEnd());
        setReleaseBegin(source.getReleaseBegin());
        setReleaseEnd(source.getReleaseEnd());
    }

    public static FloatSample prepare(FloatSample source, int startFrame, int frameCount, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        // MP3/AAC decoders already return in-memory PCM. Avoid another copy of it.
        if (source.getBuffer() != null) return source;
        int channels = source.getChannelsPerFrame();
        if (startFrame < 0 || startFrame >= source.getNumFrames() || frameCount <= 0 || channels <= 0)
            throw new IllegalArgumentException("Invalid PCM prefetch range");
        int frames = Math.min(frameCount, Math.min(source.getNumFrames() - startFrame,
                MAX_CACHE_BYTES / Float.BYTES / channels));
        int first = Math.multiplyExact(startFrame, channels);
        float[] cache = new float[Math.multiplyExact(frames, channels)];
        for (int offset = 0; offset < cache.length; offset++) {
            if ((offset & 4095) == 0 && cancelled.getAsBoolean()) throw new CancellationException();
            cache[offset] = (float) source.readDouble(Math.addExact(first, offset));
        }
        if (cancelled.getAsBoolean()) throw new CancellationException();
        return new PrefetchedFloatSample(source, first, cache);
    }

    @Override public double readDouble(int index) {
        if (closed) return 0;
        int offset = index - firstSample;
        return offset >= 0 && offset < cached.length ? cached[offset] : source.readDouble(index);
    }

    @Override public void read(int startFrame, float[] data, int startIndex, int frameCount) {
        int first = Math.multiplyExact(startFrame, channelsPerFrame);
        int count = Math.multiplyExact(frameCount, channelsPerFrame);
        for (int i = 0; i < count; i++) data[startIndex + i] = (float) readDouble(first + i);
    }

    @Override public void cleanUp() {
        if (closed) return;
        closed = true;
        cached = null;
        source.cleanUp();
    }
}
