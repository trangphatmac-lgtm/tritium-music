package tritium.desktop.background.latent;

import org.junit.jupiter.api.Test;
import tritium.ncm.music.AudioEnergy;

import static org.junit.jupiter.api.Assertions.*;

class LatentAudioTest {
    @Test
    void fftDistinguishesBassAndVoiceAndRejectsInvalidSamples() {
        for (int rate : new int[]{44100, 48000}) {
            AudioEnergy.Analyzer analyzer = new AudioEnergy.Analyzer(4096);
            float[] fft = new float[4096];
            fft[(int) (80.0 * 4096 / rate)] = .08f;
            AudioEnergy bass = analyzer.analyze(fft, rate, 1_000_000_000);
            assertTrue(bass.bass() > 0);
            assertEquals(0, bass.vocal());
            fft = new float[4096];
            for (int i = (int) (1300.0 * 4096 / rate); i < (int) (2500.0 * 4096 / rate); i++) fft[i] = .01f;
            AudioEnergy voice = new AudioEnergy.Analyzer(4096).analyze(fft, rate, 1_000_000_000);
            assertTrue(voice.vocal() > .1);
            assertEquals(0, voice.bass());
            fft[0] = Float.NaN;
            fft[5] = Float.POSITIVE_INFINITY;
            AudioEnergy safe = analyzer.analyze(fft, rate, 2_000_000_000);
            assertTrue(Double.isFinite(LatentMotion.broadband(safe)));
            analyzer.reset();
            assertEquals(0, LatentMotion.broadband(analyzer.analyze(new float[4096], rate, 3_000_000_000L)),
                    "A new track must not inherit the previous track's smoothed spectrum");
        }
    }

    @Test
    void onsetsAccelerateTheFlowAndPauseEasesToTwelvePercent() {
        LatentMotion motion = new LatentMotion();
        for (int i = 0; i < 120; i++) motion.update(AudioEnergy.SILENT, false, true, 1 / 60.0, 1);
        float before = motion.meshTime();
        AudioEnergy hit = new AudioEnergy(.8, .9, .8, .6, .5, .4, 1);
        for (int i = 0; i < 6; i++) motion.update(hit, false, true, 1 / 60.0, 1);
        assertTrue(motion.meshTime() - before > .09, "A musical onset must speed up the shader, not only change brightness");
        float power = motion.power();
        motion.update(hit, true, true, 1 / 60.0, 1);
        assertTrue(motion.power() > 0 && motion.power() < power, "Pausing eases the existing deformation");
        for (int i = 0; i < 300; i++) motion.update(hit, true, true, 1 / 60.0, 1);
        before = motion.meshTime();
        for (int i = 0; i < 60; i++) motion.update(hit, true, true, 1 / 60.0, 1);
        assertEquals(.3 * .12, motion.meshTime() - before, .00001);
        assertEquals(0, motion.power(), .00001);
    }

    @Test
    void disabledResponseIgnoresMusicAndRefreshRateDoesNotChangeSpeed() {
        AudioEnergy hit = new AudioEnergy(1, 1, 1, 1, 1, 1, 1);
        LatentMotion slow = new LatentMotion(), fast = new LatentMotion();
        for (int i = 0; i < 120; i++) slow.update(hit, false, false, 1 / 60.0, 1);
        for (int i = 0; i < 288; i++) fast.update(hit, false, false, 1 / 144.0, 1);
        assertEquals(.6, slow.meshTime(), .00001);
        assertEquals(slow.meshTime(), fast.meshTime(), .00001);
        assertEquals(0, slow.power());
    }
}
