package tritium.ncm.music;

import lombok.Getter;
import com.google.gson.JsonObject;
import repackage.processing.sound.*;
import tritium.ncm.music.automix.AutoMixAutomation.FilterSetting;
import tritium.ncm.music.automix.AutoMixAutomation.FilterType;
import tritium.ncm.music.automix.AutoMixPlan;
import tritium.widget.impl.SpectrumVisualizer;
import tritium.ncm.music.stream.StreamingAudioSample;
import tritium.ncm.music.effects.AudioEffectDsp;
import tritium.ncm.music.effects.AudioEffectTarget;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One independently controlled voice on the shared JSyn output. */
public class AudioPlayer implements AutoMixTransition.Deck, AudioEffectTarget {
    public volatile SoundFile player;
    public volatile Runnable afterPlayed;
    @Getter public volatile float volume = 0.25f;
    @Getter private volatile double mixGain = 1;
    @Getter private volatile double mixLowPassHz;
    @Getter private volatile double mixHighPassHz;
    private volatile boolean analysisEnabled;
    private volatile boolean closed;
    private TransitionFilter filter;
    private AdvancedTransitionFilter advancedFilter;
    private float preparedCue = Float.NaN;
    @Getter private volatile List<FilterSetting> mixEqualizer = List.of();
    @Getter private volatile boolean finished;
    private final AudioEnergy.Analyzer energyAnalyzer = new AudioEnergy.Analyzer(JSynFFT.FFT_SIZE);
    @Getter private volatile AudioEnergy energy = AudioEnergy.SILENT;
    @Getter private FFT fft;
    private StreamingAudioSample stream;
    private final Object audioEffectLock = new Object();
    private volatile AudioEffectDsp audioEffect;

    public AudioPlayer(File file) { this(file, true); }

    AudioPlayer(StreamingAudioSample stream) {
        this.stream = stream;
        player = new SoundFile(stream);
        installAudioEffect();
        setListeners();
        setAnalysisEnabled(true);
    }

    public java.io.IOException streamingFailure() { return stream == null ? null : stream.failure(); }
    public boolean isDownloading() { return stream != null && stream.downloading(); }
    public double downloadProgress() { return stream == null ? 1 : stream.downloadProgress(); }
    public double downloadBytesPerSecond() { return stream == null ? 0 : stream.downloadBytesPerSecond(); }

    /** A preloaded voice stays silent and does not overwrite the current song's spectrum. */
    AudioPlayer(File file, boolean analyze) {
        player = new SoundFile(file.getAbsolutePath());
        installAudioEffect();
        setListeners();
        setAnalysisEnabled(analyze);
    }

    public synchronized void setAudio(File file) {
        close();
        stream = null;
        player = new SoundFile(file.getAbsolutePath());
        installAudioEffect();
        closed = false;
        finished = false;
        mixGain = 1;
        setListeners();
        setAnalysisEnabled(true);
    }

    public static volatile float[] bandValues = new float[1];
    public static volatile SpectrumVisualizer.Frame spectrumFrame;
    public static final SpectrumVisualizer visualizer = new SpectrumVisualizer(48000, JSynFFT.FFT_SIZE, 1024);
    static int skipCount;
    public static final JSynFFT.FFTCalcCallback callback = fft -> {
        if (skipCount < 4) skipCount++;
        else {
            skipCount = 0;
            var preferences = tritium.desktop.DesktopAppState.preferences();
            SpectrumVisualizer.Frame frame = visualizer.processFrame(fft, preferences.volume().getValue());
            spectrumFrame = frame;
            bandValues = preferences.spectrumAbsoluteVolume().getValue() ? frame.absolute : frame.relative;
        }
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
            prepareAnalyzer();
            energyAnalyzer.reset();
            fft.input(player);
        } else if (fft != null) fft.removeInput();
        energy = AudioEnergy.SILENT;
    }

    private void prepareAnalyzer() {
        if (fft == null) fft = new FFT(128, magnitudes -> {
            if (!analysisEnabled) return;
            callback.onFFT(magnitudes);
            if (skipCount == 0) energy = energyAnalyzer.analyze(magnitudes, Sound.sampleRate(), System.nanoTime());
        });
    }

    /** Called only for an unpublished incoming deck by the preparation worker. */
    synchronized void prepareAutoMix(AutoMixPlan plan, BooleanSupplier cancelled) {
        if (closed || player.isPlaying()) throw new IllegalStateException("Incoming deck is unavailable");
        setMixGain(0);
        float cue = clampPosition((float) plan.startBMillis());
        // Cover the entry/fade and one second beyond it, capped by the PCM cache's byte budget.
        float seconds = (float) (Math.max(1_000, plan.endBMillis() - plan.startBMillis()) + 1_000) / 1000;
        player.prepareCue(cue / 1000F, seconds, cancelled);
        prepareAnalyzer(); // Allocate the FFT now; attach it only when B becomes the current song.
        if (plan.fullMode()) setMixEqualizer(plan.automation().b().filtersAt(plan.startBMillis()));
        else setMixFilter(plan.incomingLowPassHz(plan.startBMillis()), plan.incomingHighPassHz(plan.startBMillis()));
        preparedCue = cue;
    }

    public synchronized void play() {
        if (closed || player.isPlaying()) return;
        preparedCue = Float.NaN;
        finished = false;
        // Keep dynamics independent of listening volume; fade the complete effect output.
        applyGain();
        player.play(1, 1);
        if (filter != null && !filter.isProcessing()) filter.process(player);
        if (advancedFilter != null && !advancedFilter.isProcessing()) advancedFilter.process(player);
    }

    @Override public synchronized void startAt(float millis) {
        if (closed) return;
        float cue = clampPosition(millis);
        if (cue == preparedCue) {
            preparedCue = Float.NaN;
            play();
            return;
        }
        preparedCue = Float.NaN;
        // cue() removes the circuit's effects without clearing Effect.inputs. Detach through
        // the wrappers first so play() can reconnect the configured entry filters afterward.
        if (filter != null) filter.stop();
        if (advancedFilter != null) advancedFilter.stop();
        audioEffect.reset();
        player.cue(cue / 1000F);
        play();
    }

    public synchronized void setPlaybackTime(float millis) {
        if (closed) return;
        preparedCue = Float.NaN;
        boolean paused = isPausing();
        clearFilter();
        audioEffect.reset();
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
        preparedCue = Float.NaN;
        setAnalysisEnabled(false);
        clearFilter();
        synchronized (audioEffectLock) {
            closed = true;
            audioEffect.setConfiguration(null);
        }
        sourceOutputGain(0);
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
        if (!closed) sourceOutputGain(volume * mixGain);
    }

    private void sourceOutputGain(double gain) { player.getUnitGenerator().setOutputGain(gain); }

    private void installAudioEffect() {
        audioEffect = new AudioEffectDsp(Sound.sampleRate());
        player.getUnitGenerator().installStereoStage(audioEffect.input, audioEffect.output);
        sourceOutputGain(volume * mixGain);
    }

    /** Atomically replaces the effect plan. Null/empty configurations smoothly bypass DSP.
     * Parsing and delay allocation run on this caller, never on the audio render thread. */
    @Override public void setAudioEffect(JsonObject config) {
        // The player monitor can be held by AutoMix's blocking PCM warm-up. Settings
        // only need the small effect lock, so changing mode never waits for audio I/O.
        synchronized (audioEffectLock) {
            if (!closed) audioEffect.setConfiguration(config);
        }
    }

    @Override public boolean isAudioEffectClosed() { return closed; }

    @Override public synchronized void setMixFilter(double lowPassHz, double highPassHz) {
        if (closed) return;
        clearAdvancedFilter();
        if (lowPassHz <= 0 && highPassHz <= 0) { clearFilter(); return; }
        if (filter == null) {
            filter = new TransitionFilter();
            filter.configure(lowPassHz, highPassHz);
            filter.process(player);
        } else filter.configure(lowPassHz, highPassHz);
        mixLowPassHz = Math.max(0, lowPassHz);
        mixHighPassHz = Math.max(0, highPassHz);
    }

    @Override public synchronized void setMixEqualizer(List<FilterSetting> settings) {
        if (closed) return;
        List<FilterSetting> copy = List.copyOf(settings);
        if (copy.size() > AdvancedTransitionFilter.MAX_STAGES)
            throw new IllegalArgumentException("Too many AutoMix EQ stages");
        if (copy.isEmpty()) { clearFilter(); return; }
        if (filter != null) { filter.stop(); filter = null; }
        if (advancedFilter == null) {
            advancedFilter = new AdvancedTransitionFilter();
            advancedFilter.configure(copy);
            advancedFilter.process(player);
        } else if (!copy.equals(mixEqualizer)) advancedFilter.configure(copy);
        mixEqualizer = copy;
        // These legacy summaries do not merge or replace the independent processing stages.
        mixLowPassHz = 0;
        mixHighPassHz = 0;
        for (FilterSetting setting : copy) {
            if (setting.frequencyHz() <= 0) continue;
            if (setting.type() == FilterType.LOW_PASS)
                mixLowPassHz = mixLowPassHz == 0 ? setting.frequencyHz() : Math.min(mixLowPassHz, setting.frequencyHz());
            else mixHighPassHz = Math.max(mixHighPassHz, setting.frequencyHz());
        }
    }

    private void clearAdvancedFilter() {
        if (advancedFilter != null) { advancedFilter.stop(); advancedFilter = null; }
        mixEqualizer = List.of();
    }

    private void clearFilter() {
        if (filter != null) { filter.stop(); filter = null; }
        clearAdvancedFilter();
        mixLowPassHz = mixHighPassHz = 0;
    }

    public synchronized void pause() {
        if (closed || !player.isPlaying()) return;
        // SoundObject.stop removes effects itself; dispose the wrapper before pausing.
        clearFilter();
        audioEffect.reset();
        player.pause();
    }
    public void unpause() { play(); }
}
