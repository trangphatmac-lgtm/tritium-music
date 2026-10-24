// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import org.lwjgl.opengl.GL11;
import tritium.desktop.DesktopAppState;
import tritium.i18n.I18n;
import tritium.ncm.music.AudioEnergy;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.screens.ncm.LyricLine;
import tritium.utils.cursor.CursorUtils;
import java.util.List;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Native Folia scene host: owns caches and resource lifetime, never starts another player or clock. */
public final class FoliaLyrics implements AutoCloseable {
    private final LyricCanvas canvas = new LyricCanvas();
    private final LyricSurface surface = new LyricSurface();
    private LyricStyle style;
    private LyricScene scene;
    private List<LyricLine> source = List.of();
    private LyricTimeline timeline = new LyricTimeline(List.of(), 0);
    private Music song;
    private double previousTime = Double.NaN;
    private long previousFrame;
    private double x, y, scale = 1, width, height;
    private boolean backHovered;

    public void render(LyricStyle selected, double x, double y, double width, double height,
                       double mouseX, double mouseY, float opacity, double bottomInset) {
        if (width <= 0 || height <= 0) return;
        boolean changed = selected != style;
        if (changed) { close(); style = selected; scene = selected.createScene(); }
        double time = CloudMusic.player == null ? 0 : CloudMusic.player.getCurrentTimeMillis() / 1000.0;
        if (!Double.isFinite(time)) time = 0;
        double duration = CloudMusic.player == null ? 0 : CloudMusic.player.getTotalTimeMillis() / 1000.0;
        if (song != CloudMusic.currentlyPlaying || !source.equals(CloudMusic.lyrics)) {
            song = CloudMusic.currentlyPlaying;
            source = List.copyOf(CloudMusic.lyrics);
            timeline = new LyricTimeline(source, duration);
            scene.reset(); changed = true;
        }
        long now = System.nanoTime();
        double dt = previousFrame == 0 ? 1 / 60.0 : Math.min(.1, (now - previousFrame) / 1e9);
        previousFrame = now;
        boolean discontinuity = changed || !Double.isFinite(previousTime) || time < previousTime - .05 || Math.abs(time - previousTime) > .6;
        previousTime = time;
        boolean paused = CloudMusic.player == null || CloudMusic.player.isPausing();
        AudioEnergy energy = paused ? AudioEnergy.SILENT : CloudMusic.player.getEnergy();
        this.x = x; this.y = y; this.width = width; this.height = height; scale = height / 720;
        var prefs = DesktopAppState.preferences();
        var frame = new LyricFrame(timeline, time, dt, discontinuity, paused, energy, song,
                song == null ? 0 : song.getId(), width / scale, 720, bottomInset / scale,
                prefs.accentColor().getValue() | 0xFF000000, prefs.showTranslation().getValue(), prefs.showRoman().getValue());
        canvas.begin(1);
        surface.render(opacity, () -> {
            GL11.glPushMatrix(); GL11.glTranslated(x, y, 0); GL11.glScaled(scale, scale, 1);
            try {
                scene.render(canvas, frame);
                if (prefs.showBottomLyrics().getValue() && !scene.ownSubtitles()) subtitles(frame);
                if (timeline.lines().isEmpty()) {
                    canvas.centered(song == null ? I18n.text("player.idle") : song.getName(),
                            frame.width() / 2, frame.contentHeight() * .48, 34, 0xDFFFFFFF, true);
                    canvas.centered(I18n.text("lyrics.empty"), frame.width() / 2,
                            frame.contentHeight() * .48 + 52, 19, 0x99FFFFFF, false);
                }
                backHovered = mouseX >= x + 8 && mouseX <= x + 44 && mouseY >= y + 8 && mouseY <= y + 44;
                canvas.circle(30 / scale, 28 / scale, 15 / scale, backHovered ? 0x66000000 : 0x30000000);
                canvas.line(33 / scale, 22 / scale, 27 / scale, 28 / scale, 1.5 / scale, 0xDDFFFFFF);
                canvas.line(27 / scale, 28 / scale, 33 / scale, 34 / scale, 1.5 / scale, 0xDDFFFFFF);
                if (backHovered || canvas.seekAt((mouseX - x) / scale, (mouseY - y) / scale) != null)
                    CursorUtils.setOverride(CursorUtils.HAND);
            } finally { GL11.glPopMatrix(); }
        });
    }

    private void subtitles(LyricFrame frame) {
        var line = frame.current();
        if (line == null || frame.time() > line.end() + 3) return;
        double y = frame.contentHeight() - 70;
        if (frame.romanization() && line.romanization() != null && !line.romanization().isBlank()) {
            canvas.centered(line.romanization(), frame.width() / 2, y - 26,
                    Math.min(19, (frame.width() - 64) / Math.max(1, canvas.width(line.romanization(), 1, false))), 0xBFFFFFFF, false);
        }
        if (frame.translation() && line.translation() != null && !line.translation().isBlank()) {
            var rows = canvas.wrap(line.translation(), 22, frame.width() - 100, false);
            for (int i = 0; i < Math.min(2, rows.size()); i++) canvas.centered(rows.get(i), frame.width() / 2,
                    y + i * 26, 22, 0xE6FFFFFF, false);
        }
        var next = frame.next();
        if (next != null) canvas.centered(next.text(), frame.width() / 2, frame.contentHeight() - 20,
                Math.min(15, (frame.width() - 100) / Math.max(1, canvas.width(next.text(), 1, false))), 0x50FFFFFF, false);
    }

    public boolean backAt(double mouseX, double mouseY) {
        return mouseX >= x + 8 && mouseX <= x + 44 && mouseY >= y + 8 && mouseY <= y + 44;
    }
    public void click(double mouseX, double mouseY) {
        Double seconds = canvas.seekAt((mouseX - x) / scale, (mouseY - y) / scale);
        if (seconds != null && CloudMusic.player != null) CloudMusic.player.setPlaybackTime((float) (seconds * 1000));
    }
    @Override public void close() {
        if (scene != null) scene.close();
        surface.close();
        scene = null; style = null; previousTime = Double.NaN; previousFrame = 0;
    }
}
