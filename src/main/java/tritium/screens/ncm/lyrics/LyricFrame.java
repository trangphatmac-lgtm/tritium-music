package tritium.screens.ncm.lyrics;

import tritium.ncm.music.AudioEnergy;
import tritium.ncm.music.dto.Music;

/** Per-frame input shared by native scenes; all animation clocks use seconds. */
public record LyricFrame(LyricTimeline timeline, double time, double dt, boolean discontinuity,
                         boolean paused, AudioEnergy energy, Music song, long seed,
                         double width, double height, double safeBottom, int accent,
                         boolean translation, boolean romanization) {
    public int index() { return timeline.indexAt(time); }
    public LyricTimeline.Line current() { return timeline.line(index()); }
    public LyricTimeline.Line next() { return timeline.line(index() + 1); }
    public double contentHeight() { return height - safeBottom; }
    public double sceneTime() { return Math.max(0, time); }
    public double lyricAlpha() {
        var line = current();
        return line == null ? 0 : LyricMath.smooth((time - line.start()) / .25)
                * (1 - LyricMath.smooth((time - line.end() - .6) / .8));
    }
}
