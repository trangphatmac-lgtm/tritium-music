// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Folia's LatentBackground.tsx; see THIRD_PARTY_NOTICES.md.
package tritium.desktop.background.latent;

import tritium.ncm.music.AudioEnergy;

/** Folia Latent's onset envelope, deformation and pause easing, normalized to elapsed time. */
public final class LatentMotion {
    private double playing, power, bass, mid, beatSpeed, previousEnergy, onset;
    private double meshTime, ditheringTime;
    private long sampledAt;
    private boolean initialized;

    public void update(AudioEnergy audio, boolean paused, boolean react, double seconds, double speed) {
        double dt = Math.max(0, Math.min(.1, seconds));
        if (!initialized) {
            playing = paused ? 0 : 1;
            initialized = true;
        }
        playing += ((paused ? 0 : 1) - playing) * (1 - Math.exp(-dt / .3));
        AudioEnergy signal = paused || !react ? AudioEnergy.SILENT : audio;
        power = ease(power, signal.power(), .12, dt);
        bass = ease(bass, signal.bass(), .16, dt);
        mid = ease(mid, Math.max(signal.mid(), signal.vocal()), .13, dt);
        double energy = broadband(signal);
        onset *= Math.pow(.84, dt * 60);
        // Each audio sample produces one onset, regardless of the display refresh rate.
        if (sampledAt != signal.sampledAt() || signal == AudioEnergy.SILENT) {
            onset = Math.max(onset, clamp((energy - previousEnergy) * 7));
            previousEnergy = energy;
            sampledAt = signal.sampledAt();
        }
        double target = react ? clamp(energy * .42 + onset * .85) : 0;
        beatSpeed = ease(beatSpeed, target, target > beatSpeed ? .42 : .14, dt);
        meshTime += dt * shaderSpeed(.3, 2) * speed;
        ditheringTime += dt * shaderSpeed(.1, 1.2) * speed;
    }

    public static double broadband(AudioEnergy audio) {
        return Math.pow(clamp(audio.bass() * .22 + audio.lowMid() * .18 + audio.mid() * .22
                + audio.vocal() * .28 + audio.treble() * .1), .55);
    }

    private static double clamp(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
    private static double ease(double current, double target, double amount, double dt) {
        return current + (clamp(target) - current) * (1 - Math.pow(1 - amount, dt * 60));
    }
    private double shaderSpeed(double base, double audio) {
        return base * .12 * (1 - playing) + (base + (audio - base) * beatSpeed) * playing;
    }
    public float meshTime() { return (float) meshTime; }
    public float ditheringTime() { return (float) ditheringTime; }
    public float power() { return (float) power; }
    public float bass() { return (float) bass; }
    public float mid() { return (float) mid; }
}
