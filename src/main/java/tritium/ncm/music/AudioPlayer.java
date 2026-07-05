package tritium.ncm.music;

import lombok.Getter;
import lombok.SneakyThrows;
import repackage.processing.sound.*;
import tritium.widget.impl.SpectrumVisualizer;

import java.io.File;

/**
 * 播放视频里面的音频
 */
public class AudioPlayer {
    public SoundFile player;
    public Runnable afterPlayed;

    @Getter
    public float volume = 0.25f;

    public AudioPlayer(File file) {
        finished = false;

        this.player = new SoundFile(file.getAbsolutePath());
        this.setListeners();
    }

    public void setAudio(File file) {
        this.close();

        this.player = new SoundFile(file.getAbsolutePath());
        this.setListeners();
        finished = false;
    }

    @Getter
    FFT fft = new FFT(128, callback);

    public static float[] bandValues = new float[1];
    public static final SpectrumVisualizer visualizer = new SpectrumVisualizer(48000, JSynFFT.FFT_SIZE, 1024);


    static int skipCount = 0;

    public static final JSynFFT.FFTCalcCallback callback = fft -> {
        int skipAmount = 4;

        if (skipCount < skipAmount) {
            skipCount++;
        } else {
            skipCount = 0;
            bandValues = visualizer.processFFT(fft);
        }
    };

    public void setListeners() {
        fft.removeInput();
        fft.input(this.player);

        player.setOnFinished(() -> finished = true);
    }

    public void play() {
        finished = false;
        this.player.play();
        this.player.amp(volume);
    }

    @SneakyThrows
    public void setPlaybackTime(float millis) {
        this.player.jump(millis / 1000F);
        this.player.amp(volume);
    }

    @SneakyThrows
    public void close() {
        this.player.jump(0);
        player.stop();
        player.cleanUp();
    }

    @Getter
    private boolean finished;

    public void setAfterPlayed(Runnable runnable) {
        this.afterPlayed = runnable;
        this.player.setOnFinished(() -> {
            finished = true;
            runnable.run();
        });
    }

    public float getTotalTimeSeconds() {
        return (int) this.player.duration();
    }

    public float getCurrentTimeSeconds() {
        return (int) (getCurrentTimeMillis() / 1000);
    }

    public float getTotalTimeMillis() {
        return getTotalTimeSeconds() * 1000;
    }

    public float getCurrentTimeMillis() {
        return this.player.position() * 1000;
    }

    public boolean isPausing() {
        return !this.player.isPlaying();
    }

    public void setVolume(float volume) {
        this.volume = volume;
        this.player.amp(this.getVolume());
    }

    public void pause() {
        this.player.pause();
    }

    public void unpause() {
        this.play();
    }
}