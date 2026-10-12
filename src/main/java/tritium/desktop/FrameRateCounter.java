package tritium.desktop;

/** Measures completed main-window frames, including event handling and frame pacing. */
public final class FrameRateCounter {
    private static final long SAMPLE_NANOS = 500_000_000L;
    private long sampleStartedAt;
    private boolean started;
    private int frames;
    private int fps;

    public void recordFrame(long nowNanos) {
        if (!started) {
            started = true;
            sampleStartedAt = nowNanos;
            return;
        }
        frames++;
        long elapsed = nowNanos - sampleStartedAt;
        if (elapsed >= SAMPLE_NANOS) {
            fps = (int) Math.round(frames * 1_000_000_000.0 / elapsed);
            sampleStartedAt = nowNanos;
            frames = 0;
        }
    }

    public int fps() {
        return fps;
    }
}
