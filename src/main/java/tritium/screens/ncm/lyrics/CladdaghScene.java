// SPDX-License-Identifier: AGPL-3.0-only
// Only for the you who has yet to exist in this world.
// Native adaptation of Folia claddaghLayout.ts / CladdaghRingLine.tsx.
package tritium.screens.ncm.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import static tritium.screens.ncm.lyrics.LyricMath.*;

final class CladdaghScene implements LyricScene {
    private final Spring turn = new Spring();
    private record Letter(String text, double x, double y, double size, double angle, double depth, int color, double glow) { }
    @Override public void render(LyricCanvas c, LyricFrame f) {
        SceneBackdrop.ambient(c, f, 0xFF10131D);
        int focus = Math.max(0, f.index());
        if (f.discontinuity()) turn.snap(focus * Math.PI);
        double offset = turn.step(focus * Math.PI, f.dt(), 90, 20);
        double power = f.energy().power(), radius = Math.min(Math.min(f.width() * .44, f.contentHeight() * .55), 560) * Math.min(1 + power * .35, 1.35);
        double centerX = f.width() / 2, centerY = f.contentHeight() * .46;
        double axis = radius * .58 * (1 + f.energy().bass() * f.energy().bass() * 1.5);
        c.push(centerX, centerY, 1, 45);
        for (int i = 0; i < 24; i++) {
            double x = (i / 24.0 - .5) * axis;
            c.rect(x, 0, axis / 24 + 1, 1 + f.energy().bass(), alpha(f.accent(), .2 + .55 * Math.sin(Math.PI * i / 24)));
        }
        c.pop();
        var letters = new ArrayList<Letter>();
        var active = f.timeline().line(focus);
        double baseSize = Math.min(72, f.contentHeight() * .085);
        double activeWord = active == null ? 0 : wordOffset(c, active, f.time(), radius, baseSize);
        for (int index = Math.max(0, focus - 1); index <= Math.min(f.timeline().lines().size() - 1, focus + 1); index++) {
            var line = f.timeline().line(index);
            double own = wordOffset(c, line, f.time(), radius, baseSize);
            double distance = Math.abs(offset - index * Math.PI) / Math.PI;
            double back = index > focus ? 1 : clamp(distance);
            if (index >= focus && active != null) own += (activeWord * .28 + Math.PI * .52 * (1 - Math.pow(1 - active.progress(f.time()), 1.35))) * back;
            double[] angles = spacing(c, line, radius, baseSize);
            for (int i = 0; i < line.glyphs().size(); i++) {
                var glyph = line.glyphs().get(i);
                double psi = index * Math.PI + angles[i] - offset - own;
                // Keep a readable moving arc; compressing an entire long line stacks its letters.
                if (Math.abs(psi) > Math.PI * .8) continue;
                double cos = Math.cos(psi), depth = (cos + 1) / 2;
                double rawX = Math.sin(psi) * radius * (.35 + .65 * Math.pow(depth, 1.2));
                double rawY = cos * radius * .09;
                double x = (rawX + rawY) / Math.sqrt(2), y = (-rawX + rawY) / Math.sqrt(2);
                double focal = Math.max(0, 1 - distance) * Math.pow(1 - Math.min(1, Math.abs(psi) / .48), 1.8);
                double opacity = (.35 + .65 * Math.pow(depth, 1.5) * (.35 + .65 * focal)) * (.68 + .32 * Math.pow(depth, 1.9));
                opacity *= clamp(2 - distance);
                if (index < focus) opacity *= Math.max(0, 1 - distance);
                if (index > focus) opacity *= .18 + .82 * clamp((f.time() - line.start() + .34) / .34);
                if (distance > .02) {
                    double threshold = 1 - 1.2 * clamp((distance - .3) / .6);
                    if (cos > threshold) opacity *= clamp(1 - (cos - threshold) / .15);
                }
                double tangent = readableAngle(Math.toDegrees(Math.atan2(-Math.sin(psi) * .09, Math.cos(psi))) - 45);
                double angle = Math.max(-38, Math.min(38, tangent * (.4 + .6 * depth)));
                double size = baseSize * (.22 + .98 * Math.pow(depth, 1.5)) * (1 + .65 * focal);
                int color = alpha(color(0xFFBBBBCC, f.accent(), glyph.progress(f.time())), opacity);
                letters.add(new Letter(glyph.text(), centerX + x, centerY + y, size, angle, depth, color, 24 * focal * focal));
            }
        }
        letters.sort(Comparator.comparingDouble(Letter::depth));
        for (var l : letters) {
            c.push(l.x, l.y, 1, l.angle);
            c.glowText(l.text, -c.width(l.text, l.size, true) / 2, -l.size * .55, l.size, l.color, l.glow, true);
            c.pop();
        }
    }
    static double[] spacing(LyricCanvas c, LyricTimeline.Line line, double radius, double baseSize) {
        double[] offsets = new double[line.glyphs().size()];
        double cursor = 0;
        for (int i = 0; i < offsets.length; i++) {
            double width = c.width(line.glyphs().get(i).text(), baseSize, true) * 2.05;
            offsets[i] = (cursor + width / 2) / radius;
            cursor += width + baseSize * .22;
        }
        return offsets;
    }
    private static double wordOffset(LyricCanvas c, LyricTimeline.Line line, double time, double radius, double baseSize) {
        double[] offsets = spacing(c, line, radius, baseSize);
        if (offsets.length == 0) return 0;
        double p = line.characterProgress(time);
        int i = Math.min(offsets.length - 1, (int) p);
        return mix(offsets[i], offsets[Math.min(i + 1, offsets.length - 1)], p - i);
    }
}
