package tritium.ncm.music;

import lombok.Getter;
import repackage.processing.sound.*;
import tritium.widget.impl.SpectrumVisualizer;

import java.io.File;

/** One independently controlled voice on the shared JSyn output. */
public class AudioPlayer implements AutoMixTransition.Deck {
    public volatile SoundFile player;
    public volatile Runnable afterPlayed;
    @Getter public volatile float volume = 0.25f;
    private double mixGain = 1;
    private volatile boolean analysisEnabled;
    private volatile boolean closed;
    private TransitionFilter filter;
    @Getter private volatile boolean finished;
    private final AudioEnergy.Analyzer energyAnalyzer = new AudioEnergy.Analyzer(JSynFFT.FFT_SIZE);
    @Getter private volatile AudioEnergy energy = AudioEnergy.SILENT;
    @Getter private FFT fft;

    public AudioPlayer(File file) { this(file, true); }

    /** A preloaded voice stays silent and does not overwrite the current song's spectrum. */
    AudioPlayer(File file, boolean analyze) {
        player = new SoundFile(file.getAbsolutePath());
        setListeners();
        setAnalysisEnabled(analyze);
    }

    public synchronized void setAudio(File file) {
        close();
        player = new SoundFile(file.getAbsolutePath());
        closed = false;
        finished = false;
        mixGain = 1;
        setListeners();
        setAnalysisEnabled(true);
    }

    public static float[] bandValues = new float[1];
    public static final SpectrumVisualizer visualizer = new SpectrumVisualizer(48000, JSynFFT.FFT_SIZE, 1024);
    static int skipCount;
    public static final JSynFFT.FFTCalcCallback callback = fft -> {
        if (skipCount < 4) skipCount++;
        else { skipCount = 0; bandValues = visualizer.processFFT(fft); }
    };

    public synchronized void setListeners() {
        SoundFile source = player;
        source.setOnFinished(() -> {
            if (!closed && player == source) {
                finished = true;
                Runnable done = afterPlayed;
                if (done != null) done.run();
            }
        });
    }

    @Override public synchronized void setAnalysisEnabled(boolean enabled) {
        if (analysisEnabled == enabled || closed) return;
        analysisEnabled = enabled;
        if (enabled) {
            if (fft == null) fft = new FFT(128, magnitudes -> {
                if (!analysisEnabled) return;
                callback.onFFT(magnitudes);
                if (skipCount == 0) energy = energyAnalyzer.analyze(magnitudes, Sound.sampleRate(), System.nanoTime());
            });
            energyAnalyzer.reset();
            fft.input(player);
        } else if (fft != null) fft.removeInput();
        energy = AudioEnergy.SILENT;
    }

    public synchronized void play() {
        if (closed || player.isPlaying()) return;
        finished = false;
        // AudioSample.play() otherwise resets its amplitude to 1 before the first audio block.
        player.play(1, (float) (volume * mixGain));
        applyGain();
        if (filter != null) filter.process(player);
    }

    @Override public synchronized void startAt(float millis) {
        if (closed) return;
        player.cue(clampPosition(millis) / 1000F);
        play();
    }

    public synchronized void setPlaybackTime(float millis) {
        if (closed) return;
        boolean paused = isPausing();
        clearFilter();
        player.cue(clampPosition(millis) / 1000F);
        finished = false;
        if (!paused) play();
        applyGain();
    }

    private float clampPosition(float millis) {
        return Math.max(0, Math.min(Float.isFinite(millis) ? millis : 0,
                Math.max(0, getTotalTimeMillis() - 1)));
    }

    public synchronized void close() {
        if (closed) return;
        setAnalysisEnabled(false);
        clearFilter();
        closed = true;
        afterPlayed = null;
        SoundFile source = player;
        source.setOnFinished(null);
        var synth = source.getUnitGenerator().getSynthesizer();
        source.stop();
        // Clear/disconnect commands must run before closing data still read by the audio thread.
        Engine owner = Engine.getEngine();
        Runnable dispose = () -> {
            // stop() may still see the queued analyzer/output disconnects and retain the circuit.
            owner.remove(source.getUnitGenerator());
            source.cleanUp();
        };
        if (synth != null && synth.isRunning()) synth.queueCommand(dispose::run);
        else dispose.run();
        finished = true;
    }

    public synchronized void setAfterPlayed(Runnable runnable) { afterPlayed = runnable; }
    public float getTotalTimeSeconds() { return player.duration(); }
    public float getCurrentTimeSeconds() { return (int) (getCurrentTimeMillis() / 1000); }
    public float getTotalTimeMillis() { return player.duration() * 1000; }
    public float getCurrentTimeMillis() { return finished ? getTotalTimeMillis() : player.position() * 1000; }
    public boolean isPausing() { return closed || !player.isPlaying(); }

    public synchronized void setVolume(float volume) {
        this.volume = Math.max(0, Math.min(1, Float.isFinite(volume) ? volume : 0));
        applyGain();
    }

    @Override public synchronized void setMixGain(double gain) {
        mixGain = Double.isFinite(gain) ? Math.max(0, Math.min(1, gain)) : 0;
        applyGain();
    }

    private void applyGain() {
        if (!closed) player.amp((float) (volume * mixGain));
    }

    @Override public synchronized void setMixFilter(double lowPassHz, double highPassHz) {
        if (closed) return;
        if (lowPassHz <= 0 && highPassHz <= 0) { clearFilter(); return; }
        if (filter == null) {
            filter = new TransitionFilter();
            filter.configure(lowPassHz, highPassHz);
            filter.process(player);
        } else filter.configure(lowPassHz, highPassHz);
    }

    private void clearFilter() {
        if (filter != null) { filter.stop(); filter = null; }
    }

    public synchronized void pause() {
        if (closed || !player.isPlaying()) return;
        // SoundObject.stop removes effects itself; dispose the wrapper before pausing.
        clearFilter();
        player.pause();
    }
    public void unpause() { play(); }
}
