// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Folia's windowSlots, windowTiming, windowGeometry and lyricWindow.
package tritium.screens.ncm.lyrics;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.DoubleBinaryOperator;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Nearby lines exchange horizontal/vertical slots along individual, time-addressable curves. */
final class LumiereWindow {
    private record Point(double x, double y) {
        Point lerp(Point b, double t) { return new Point(mix(x, b.x, t), mix(y, b.y, t)); }
    }
    private record Flow(Point[] horizontal, Point[] vertical, double[] sizes,
                        double width, double height, double columnWidth, double columnHeight) { }
    private record Slot(double x, double y, double scale, double angle, double opacity, int orient) { }
    private record Pose(Point at, double size, double angle, double opacity, double flying) { }
    private final Map<Integer, Flow> flows = new HashMap<>();
    private LyricTimeline timeline;
    private double width, height;

    private JsonObject profile(LyricFrame f, int current) {
        var profiles = data("lumiere.json").getAsJsonArray();
        return profiles.get(variant(f.seed(), Math.max(0, current), profiles.size())).getAsJsonObject();
    }

    private Flow flow(LyricCanvas g, LyricFrame f, int index) {
        return flows.computeIfAbsent(index, key -> {
            var line = f.timeline().line(key);
            double font = f.contentHeight() * n(profile(f, key), "heroSize", .085);
            int count = line.glyphs().size();
            var horizontal = new Point[count]; var vertical = new Point[count];
            var sizes = new double[count]; var advances = new double[count]; var jags = new double[count];
            var words = LyricWords.split(line);
            int hero = 0, cursor = 0;
            for (int i = 1; i < words.size(); i++)
                if (words.get(i).glyphs().size() > words.get(hero).glyphs().size()) hero = i;
            for (int i = 0; i < words.size(); i++) {
                double scale = i == hero && words.size() > 1 ? 1.45 : .85 + random(f.seed() + key * 101 + i) * .35;
                if (words.get(i).text().isBlank()) scale = 1;
                double jag = i == hero ? 0 : (random(key * 51 + i * 19) - .5) * font * .4;
                for (var glyph : words.get(i).glyphs()) {
                    sizes[cursor] = font * scale;
                    advances[cursor] = g.width(glyph.text(), sizes[cursor], true) + font * .025;
                    jags[cursor++] = jag;
                }
            }
            double natural = Arrays.stream(advances).sum(), budget = f.width() * .78;
            // Keep two balanced rows/columns, then fit the entire line into the protected frame.
            int hBreak = natural * .8 > budget ? LyricTextFlow.balancedBreak(line.glyphs().stream().map(LyricTimeline.Glyph::text).toList(), advances) : count;
            int vBreak = Arrays.stream(sizes).sum() * .8 > f.contentHeight() * .64 ? LyricTextFlow.balancedBreak(line.glyphs().stream().map(LyricTimeline.Glyph::text).toList(), sizes) : count;
            double rowWidth = 0, colHeight = 0;
            for (int begin = 0; begin < count; ) {
                int end = begin == 0 ? hBreak : count;
                double length = 0; for (int i = begin; i < end; i++) length += advances[i];
                rowWidth = Math.max(rowWidth, length); double x = -length / 2;
                for (int i = begin; i < end; i++) {
                    horizontal[i] = new Point(x + advances[i] / 2,
                            (hBreak < count ? (begin == 0 ? -.8 : .8) * font : 0) + jags[i]);
                    x += advances[i];
                }
                begin = end;
            }
            for (int begin = 0; begin < count; ) {
                int end = begin == 0 ? vBreak : count;
                double length = 0; for (int i = begin; i < end; i++) length += sizes[i] * 1.08;
                colHeight = Math.max(colHeight, length); double y = -length / 2;
                for (int i = begin; i < end; i++) {
                    vertical[i] = new Point((vBreak < count ? (begin == 0 ? .8 : -.8) * font : 0) + jags[i], y + sizes[i] * .54);
                    y += sizes[i] * 1.08;
                }
                begin = end;
            }
            return new Flow(horizontal, vertical, sizes, rowWidth, font * (hBreak < count ? 3 : 1.45),
                    font * (vBreak < count ? 3 : 1.45), colHeight);
        });
    }

    private Slot slot(LyricCanvas g, LyricFrame f, int index, int current) {
        JsonObject p = profile(f, current), region = object(p, "region");
        String kind = s(p, "typography", "crossed");
        int relative = Math.max(-2, Math.min(2, index - current)), sign = relative < 0 ? -1 : 1;
        boolean far = Math.abs(relative) > 1, vertical = kind.equals("vertical");
        Flow flow = flow(g, f, index);
        double font = f.contentHeight() * n(p, "heroSize", .085), dx = 0, dy = 0, angle = 0;
        double scale = relative == 0 ? 1 : far ? .44 : vertical ? .58 : .52;
        double a = far ? 0 : relative < 0 ? .9 : relative > 0 ? .95 : 1;
        if (kind.equals("crossed") && relative != 0) {
            double seed = f.seed() + index * 773 + sign * 33;
            double theta = Math.PI * ((sign < 0 ? 1.12 : .12) + random(seed) * .76);
            dx = Math.cos(theta) * f.width() * n(region, "w", .72) * (.24 + random(seed + 1) * .22);
            dy = Math.sin(theta) * f.contentHeight() * n(region, "h", .5) * (.3 + random(seed + 2) * .18);
            vertical = random(seed + 3) < .5; scale = .5 + random(seed + 4) * .2;
            angle = (random(seed + 5) - .5) * (vertical ? .3 : .7);
            if (vertical) scale = Math.min(scale, f.contentHeight() * .23 / Math.max(1, flow.columnHeight));
            double half = (vertical ? flow.columnHeight : flow.height) * scale / 2;
            dy = sign * Math.max(Math.abs(dy), font * 1.2 + half);
            if (far) { dx *= 1.45; dy *= 1.45; scale *= .85; }
        } else if (relative != 0) {
            Flow active = flow(g, f, Math.max(0, Math.min(f.timeline().lines().size() - 1, current)));
            double thickness = vertical ? flow.columnWidth : flow.height;
            double activeThickness = vertical ? active.columnWidth : active.height;
            double distance = activeThickness / 2 + font * (sign < 0 ? .93 : .735) + thickness * scale / 2;
            if (far) distance += thickness * .52 + font * .56;
            if (vertical) { dx = -sign * distance; dy = sign * f.contentHeight() * (far ? .07 : .03); }
            else { dx = sign * f.width() * n(region, "w", .72) * (far ? .24 : .16); dy = sign * distance; }
        }
        double fit = vertical ? f.contentHeight() * .64 / Math.max(1, flow.columnHeight * scale)
                : f.width() * .78 / Math.max(1, flow.width * scale);
        scale *= Math.min(1, fit);
        double x = f.width() * n(region, "cx", .5) + dx;
        double y = f.contentHeight() * n(region, "cy", .5) + dy;
        if (relative == 0) {
            double hw = (vertical ? flow.columnWidth : flow.width) * scale / 2;
            double hh = (vertical ? flow.columnHeight : flow.height) * scale / 2;
            x = Math.max(f.width() * .1 + hw, Math.min(f.width() * .9 - hw, x));
            y = Math.max(f.contentHeight() * .12 + hh, Math.min(f.contentHeight() * .79 - hh, y));
        }
        return new Slot(x, y, scale, angle, a, vertical ? 1 : 0);
    }

    private Pose pose(LyricCanvas g, LyricFrame f, int index, int glyph, double time) {
        int current = f.timeline().indexAt(time + 1.2), first = current;
        while (first >= 0 && f.timeline().line(first).start() + 1.35 > time) first--;
        Slot rest = slot(g, f, index, first); Flow flow = flow(g, f, index);
        double x = rest.x, y = rest.y, scale = rest.scale, angle = rest.angle, opacity = rest.opacity, orient = rest.orient;
        Point point = (rest.orient == 1 ? flow.vertical : flow.horizontal)[glyph];
        double seed = f.seed() + index * 811 + glyph * 47, flying = 0;
        for (int c = first + 1; c <= current; c++) {
            Slot from = slot(g, f, index, c - 1), to = slot(g, f, index, c);
            int relative = Math.max(-2, Math.min(2, index - c));
            double lag = switch (relative) { case -2 -> 0; case -1 -> .15; case 0 -> .3; case 1 -> .5; default -> .6; };
            double phase = clamp((time - f.timeline().line(c).start() + 1.2 - lag) / 1.5), k = (1 - Math.cos(phase * Math.PI)) / 2;
            double fade = to.opacity < from.opacity ? inOut(phase / .6) : to.opacity > from.opacity ? inOut((phase - .4) / .6) : k;
            x += (to.x - from.x) * k; y += (to.y - from.y) * k; scale += (to.scale - from.scale) * k;
            angle += (to.angle - from.angle) * k; opacity += (to.opacity - from.opacity) * fade;
            Point target = (to.orient == 1 ? flow.vertical : flow.horizontal)[glyph];
            double duration = .45 + random(seed) * .4, delay = random(seed + 1) * (1 - duration), travel = inOut((phase - delay) / duration);
            boolean fly = from.orient != to.orient || s(profile(f, c), "typography", "crossed").equals("crossed");
            if (fly) {
                double theta = random(seed + 2) * Math.PI * 2, theta2 = theta + Math.PI * (random(seed + 3) < .5 ? .5 : 1.5);
                double size = flow.sizes[glyph], u = 1 - travel;
                point = new Point(u*u*u*point.x + 3*u*u*travel*(point.x + Math.cos(theta)*size*3) + 3*u*travel*travel*(target.x + Math.cos(theta2)*size*2) + travel*travel*travel*target.x,
                        u*u*u*point.y + 3*u*u*travel*(point.y + Math.sin(theta)*size*3) + 3*u*travel*travel*(target.y + Math.sin(theta2)*size*2) + travel*travel*travel*target.y);
                flying = Math.max(flying, Math.sin(Math.PI * travel));
            } else point = point.lerp(target, k);
            orient = mix(orient, to.orient, fly ? travel : k);
        }
        var line = f.timeline().line(index); var timing = line.glyphs().get(glyph);
        JsonObject decay = object(profile(f, index), "decay");
        double age = Math.max(0, time - timing.start() - n(decay, "delay", 1));
        double amount = n(decay, "strength", 1) * .08 * Math.pow(age, 1.5) * (.6 + random(seed + 4) * .9);
        double theta = -Math.PI / 2 + (random(seed + 5) - .5) * 2.2, gather = smooth((time - line.start() + 2.8) / 1.8);
        double scatter = Math.pow(1 - gather, 2) * n(decay, "strength", 1), font = flow.sizes[glyph];
        double px = point.x + font * (Math.cos(theta) * amount + (random(seed + 6) - .5) * scatter * 3 + Math.sin(time*.9+seed) * .022);
        double py = point.y + font * (Math.sin(theta) * amount + (random(seed + 7) - .5) * scatter * 3 + Math.cos(time*1.13+seed) * .022);
        x += Math.sin(time*.52+index) * f.contentHeight() * .015;
        y += Math.cos(time*.41+index) * f.contentHeight() * .012;
        double rotation = angle + (random(seed + 8) - .5) * flying * 2.4 + (random(seed + 9) - .5) * amount * .36;
        if (timing.text().codePoints().allMatch(c -> c < 0x3000)) rotation += orient * Math.PI / 2;
        return new Pose(new Point(x + (px*Math.cos(angle)-py*Math.sin(angle))*scale,
                y + (px*Math.sin(angle)+py*Math.cos(angle))*scale), font*scale*(1-.28*flying), rotation,
                clamp(opacity) * (1-clamp((amount-1)/2)) * (.35+.65*gather), flying);
    }

    void render(LyricCanvas g, LyricFrame f, DoubleBinaryOperator light) {
        if (timeline != f.timeline() || width != f.width() || height != f.contentHeight()) {
            flows.clear(); timeline = f.timeline(); width = f.width(); height = f.contentHeight();
        }
        if (timeline.lines().isEmpty()) return;
        int current = timeline.indexAt(f.time() + 1.2);
        for (int index = Math.max(0, current-3); index <= Math.min(timeline.lines().size()-1, current+2); index++) {
            var line = timeline.line(index);
            for (int i = 0; i < line.glyphs().size(); i++) {
                var glyph = line.glyphs().get(i); if (glyph.text().isBlank()) continue;
                Pose p = pose(g, f, index, i, f.time()); if (p.opacity < .003) continue;
                double lit = smooth((f.time()-glyph.start())/Math.max(.3,glyph.end()-glyph.start()));
                double illumination = light.applyAsDouble(p.at.x/f.height(), p.at.y/f.height());
                double flash = Math.exp(-Math.max(0,f.time()-glyph.start())*9)*smooth((f.time()-glyph.start())/.06);
                double alpha = p.opacity * mix(Math.min(1,.22+illumination*.45+p.flying*.4), Math.min(1,.55+illumination*.52+flash*.08+p.flying*.3),lit);
                if (p.flying > .05) {
                    Point previous = pose(g,f,index,i,f.time()-.45).at;
                    for (int j=1;j<=12;j++) {
                        Point at = pose(g,f,index,i,f.time()-.45+j*.45/12).at;
                        g.line(previous.x,previous.y,at.x,at.y,.9,alpha(0xffffe2ac,alpha*p.flying*j/12*.3)); previous=at;
                    }
                }
                g.glow(p.at.x,p.at.y,p.size*1.4,p.size*1.2,alpha(0xffffdda1,p.opacity*lit*(.03+.14*illumination+.08*flash)));
                g.push(p.at.x,p.at.y,1,Math.toDegrees(p.angle));
                g.centered(glyph.text(),0,0,p.size,alpha(color(0xff91a8c6,0xffffe4b0,lit),alpha),true); g.pop();
                g.star(p.at.x+p.size*.2,p.at.y-p.size*.35,p.size*.45,10,alpha(0xffffeccb,p.opacity*flash*.55));
            }
        }
        flows.keySet().removeIf(i -> i < current-5 || i > current+4);
    }
}
