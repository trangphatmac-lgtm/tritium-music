package tritium.minecraft.runtime;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** World-space scene and character animation, independent of Minecraft and OpenGL. */
public final class WorldLyricScene {
    public interface Metrics { double width(String text); }
    private final Metrics metrics;
    private final Random random;
    private final List<Floating> fading = new ArrayList<Floating>();
    private PlaybackState.Track current;
    private WorldLyricTimeline timeline;
    private Floating active;
    private Object worldIdentity;
    private boolean splitLines, wasPaused;
    private long lastSeen, previousFrame;
    private int lastIndex = -1;
    private double songMillis;

    public WorldLyricScene(Metrics metrics) { this(metrics, new Random()); }
    public WorldLyricScene(Metrics metrics, Random random) { this.metrics = metrics; this.random = random; }

    public void reset() {
        current = null; timeline = null; active = null; fading.clear(); worldIdentity = null;
        lastSeen = 0; previousFrame = 0; lastIndex = -1; wasPaused = false; songMillis = 0;
    }

    /** Build one immutable frame; both the glow mask and crisp text pass must consume this same frame. */
    public Frame update(PlaybackState.Track track, double positionMs, boolean playing,
                        HudConfig.Module module, Camera camera, long nowNanos) {
        if (module == null || !module.enabled || camera == null) { reset(); return new Frame(0, Collections.<Text>emptyList()); }
        if (worldIdentity != camera.worldIdentity) { reset(); worldIdentity = camera.worldIdentity; }
        HudConfig.WorldLyrics settings = module.world;
        double dt = previousFrame == 0 ? .016 : clamp((nowNanos - previousFrame) / 1e9, 0, .1);
        previousFrame = nowNanos;
        if (track != null) {
            lastSeen = nowNanos;
            double position = (Double.isNaN(positionMs) || Double.isInfinite(positionMs) ? 0 : positionMs) + settings.offset;
            boolean changed = track != current || timeline == null || splitLines != settings.splitLines;
            if (changed) {
                timeline = new WorldLyricTimeline(track.lyrics, settings.splitLines);
                splitLines = settings.splitLines; current = track; lastIndex = -1;
                fadeActive(nowNanos);
            } else if (Math.abs(position - songMillis) > 300) lastIndex = -1;
            songMillis = position;
            int index = timeline.indexAt((long) songMillis);
            if (!playing) {
                fadeActive(nowNanos); lastIndex = index; wasPaused = true;
            } else if (index != lastIndex || wasPaused) {
                boolean resumed = wasPaused && index == lastIndex;
                wasPaused = false; lastIndex = index; fadeActive(nowNanos);
                if (index >= 0 && !timeline.lines.get(index).text.trim().isEmpty()) {
                    active = spawn(timeline.lines.get(index), timeline.nextMs(index), settings, camera);
                    if (resumed) active.resumedAt = nowNanos;
                }
            }
        } else if (current != null && nowNanos - lastSeen > 3_000_000_000L) {
            current = null; timeline = null; lastIndex = -1; fadeActive(nowNanos);
        }
        List<Text> visible = new ArrayList<Text>();
        double scale = .03 * module.scale * settings.distance / 6.5;
        double k = 1 - Math.exp(-dt / .12);
        for (Iterator<Floating> it = fading.iterator(); it.hasNext();) {
            Floating line = it.next(); double t = Math.max(0, (nowNanos - line.fadeAt) / 1e9);
            double alpha = 1 - t / .4;
            if (alpha <= 0) { it.remove(); continue; }
            visible.add(frame(line, module, settings, camera, scale, k, alpha, t * .4, nowNanos));
        }
        if (active != null) {
            double alpha = active.resumedAt == Long.MIN_VALUE ? 1 : clamp((nowNanos - active.resumedAt) / 3e8, 0, 1);
            visible.add(frame(active, module, settings, camera, scale, k, alpha, 0, nowNanos));
        }
        return new Frame((long) songMillis, visible);
    }

    private void fadeActive(long now) {
        if (active == null) return;
        active.fadeAt = now; fading.add(active); active = null;
    }

    private Floating spawn(WorldLyricTimeline.Entry entry, long next, HudConfig.WorldLyrics settings, Camera camera) {
        List<String> lines = wrap(entry.text);
        int chars = 0; for (String line : lines) chars += line.length();
        long lineDuration = Math.max(next - entry.startMs, 400);
        long reveal = Math.min(Math.max(chars * 55L, 250), (long) (lineDuration * .6));
        double yaw = camera.yaw + (random.nextFloat() * 2 - 1) * settings.spread;
        double pitch = clamp(camera.pitch - 3 - random.nextFloat() * 10, -60, 30);
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch), d = settings.distance;
        return new Floating(lines, entry.startMs, reveal, WorldLyricTimeline.characterTimes(entry, next),
                camera.eyeX - Math.sin(yr) * Math.cos(pr) * d, camera.eyeY - Math.sin(pr) * d,
                camera.eyeZ + Math.cos(yr) * Math.cos(pr) * d, yaw, pitch);
    }

    private List<String> wrap(String text) {
        List<String> result = new ArrayList<String>(); StringBuilder line = new StringBuilder(); int space = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i); line.append(c); if (c == ' ') space = line.length() - 1;
            if (metrics.width(line.toString()) <= 200 || line.length() <= 1) continue;
            if (space > 0) {
                result.add(line.substring(0, space).trim()); String rest = line.substring(space + 1); line.setLength(0); line.append(rest);
            } else {
                result.add(line.substring(0, line.length() - 1).trim()); line.setLength(0); line.append(c);
            }
            space = -1;
        }
        if (!line.toString().trim().isEmpty()) result.add(line.toString().trim());
        result.removeIf(String::isEmpty); return result;
    }

    private Text frame(Floating line, HudConfig.Module module, HudConfig.WorldLyrics settings,
                       Camera camera, double scale, double k, double alpha, double lift, long now) {
        if (settings.stayOnScreen) follow(line, camera, scale, k);
        double rx = line.x - camera.x, ry = line.y - camera.y, rz = line.z - camera.z;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz), minD = settings.distance * .6;
        if (settings.keepAway && len >= .0001 && len <= minD) {
            double push = minD / len; rx *= push; ry *= push; rz *= push;
            line.x = camera.x + rx; line.y = camera.y + ry; line.z = camera.z + rz;
        }
        double yaw = line.yaw, pitch = line.pitch;
        if (settings.facePlayer && len > .0001) {
            yaw = Math.toDegrees(Math.atan2(-rx, rz)); pitch = Math.toDegrees(Math.atan2(-ry, Math.sqrt(rx * rx + rz * rz)));
        }
        return new Text(line.x, line.y + lift, line.z, yaw, pitch, scale,
                glyphs(line, (long) songMillis - line.startMs, alpha * module.opacity, settings, now));
    }

    private List<Glyph> glyphs(Floating floating, long elapsed, double alpha, HudConfig.WorldLyrics settings, long now) {
        List<String> lines = floating.lines; List<Glyph> result = new ArrayList<Glyph>();
        boolean pop = settings.wordPop && floating.times.length > 0;
        int total = 0; for (String line : lines) total += line.length(); total = Math.max(total, 1);
        int k = 0, n = 0, seed = lines.hashCode(); double y = -lines.size() * 11.0 / 2;
        long animationFrame = (long) (now / 1e9 * Math.max(1, (int) settings.wiggleFps));
        boolean wiggle = settings.wiggle && settings.wiggleAmount > .001;
        for (String line : lines) {
            double x = -metrics.width(line) / 2;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i); double width = metrics.width(String.valueOf(c)), a = 0, sy = y, popScale = 1;
                if (pop && !Character.isWhitespace(c)) {
                    long at = floating.times[Math.min(n++, floating.times.length - 1)]; double t = (elapsed - at) / 160.0;
                    if (t >= 0) { a = clamp(t * 3, 0, 1) * alpha; double back = clamp(1 - t, 0, 1); popScale += .55 * back * back; }
                } else if (!pop) {
                    double appear = k / (double) total * floating.revealMs;
                    double t = clamp((elapsed - appear) / 180, 0, 1); t = t * t * (3 - 2 * t);
                    a = t * alpha; sy += (1 - t) * 5;
                }
                if (a > .02 && !Character.isWhitespace(c)) {
                    double amt = settings.wiggleAmount;
                    double rot = wiggle ? hash(k, animationFrame, 1, seed) * 9 * amt : 0;
                    double dx = wiggle ? hash(k, animationFrame, 2, seed) * .8 * amt : 0;
                    double dy = wiggle ? hash(k, animationFrame, 3, seed) * 1.6 * amt : 0;
                    double sc = (wiggle ? 1 + hash(k, animationFrame, 4, seed) * .12 * amt : 1) * popScale;
                    result.add(new Glyph(c, x, sy, width, a, rot, sc, dx, dy));
                }
                x += width; k++;
            }
            y += 11;
        }
        return result;
    }

    private void follow(Floating line, Camera camera, double scale, double k) {
        double rx = line.x - camera.x, ry = line.y - camera.y, rz = line.z - camera.z;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < .0001 || line.lines.isEmpty()) return;
        double verticalHalf = camera.verticalFov / 2;
        double horizontalHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(verticalHalf)) * camera.aspect));
        double widest = 0; for (String text : line.lines) widest = Math.max(widest, metrics.width(text));
        double halfWidth = Math.toDegrees(Math.atan2(widest * scale / 2, len));
        double halfHeight = Math.toDegrees(Math.atan2(line.lines.size() * 11 * scale / 2, len));
        double hLimit = Math.max(0, horizontalHalf - halfWidth - 2), vLimit = Math.max(0, verticalHalf - halfHeight - 2);
        double yaw = Math.toDegrees(Math.atan2(-rx, rz)), pitch = Math.toDegrees(Math.asin(clamp(-ry / len, -1, 1)));
        double dYaw = wrapDegrees(yaw - camera.yaw), dPitch = pitch - camera.pitch;
        double tYaw = clamp(dYaw, -hLimit, hLimit), tPitch = clamp(dPitch, -vLimit, vLimit);
        if (tYaw == dYaw && tPitch == dPitch) return;
        double newYaw = camera.yaw + dYaw + (tYaw - dYaw) * k;
        double newPitch = clamp(camera.pitch + dPitch + (tPitch - dPitch) * k, -89, 89);
        double yr = Math.toRadians(newYaw), pr = Math.toRadians(newPitch);
        line.x = camera.x - Math.sin(yr) * Math.cos(pr) * len;
        line.y = camera.y - Math.sin(pr) * len;
        line.z = camera.z + Math.cos(yr) * Math.cos(pr) * len;
        line.yaw += wrapDegrees(newYaw - yaw); line.pitch = clamp(line.pitch + newPitch - pitch, -90, 90);
    }

    private static double hash(int k, long frame, int salt, int seed) {
        int h = k * 73856093 ^ (int) (frame * 19349663L) ^ salt * 83492791 ^ seed;
        h ^= h >>> 13; h *= 1540483477; h ^= h >>> 15; return (h & 0xffff) / 32767.5 - 1;
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static double wrapDegrees(double degrees) { double d = degrees % 360; if (d >= 180) d -= 360; if (d < -180) d += 360; return d; }

    public static final class Camera {
        public double x, y, z, eyeX, eyeY, eyeZ, yaw, pitch, verticalFov = 70, aspect = 16.0 / 9;
        public Object worldIdentity;
    }
    public static final class Frame {
        public final long songMillis; public final List<Text> texts;
        Frame(long songMillis, List<Text> texts) { this.songMillis = songMillis; this.texts = Collections.unmodifiableList(texts); }
    }
    public static final class Text {
        public final double x, y, z, yaw, pitch, scale; public final List<Glyph> glyphs;
        Text(double x, double y, double z, double yaw, double pitch, double scale, List<Glyph> glyphs) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.scale = scale;
            this.glyphs = Collections.unmodifiableList(glyphs);
        }
    }
    public static final class Glyph {
        public final char character; public final double x, y, width, alpha, rotation, scale, dx, dy;
        Glyph(char character, double x, double y, double width, double alpha, double rotation, double scale, double dx, double dy) {
            this.character = character; this.x = x; this.y = y; this.width = width; this.alpha = alpha;
            this.rotation = rotation; this.scale = scale; this.dx = dx; this.dy = dy;
        }
    }
    private static final class Floating {
        final List<String> lines; final long startMs, revealMs; final long[] times;
        double x, y, z, yaw, pitch; long fadeAt, resumedAt = Long.MIN_VALUE;
        Floating(List<String> lines, long startMs, long revealMs, long[] times, double x, double y, double z, double yaw, double pitch) {
            this.lines = lines; this.startMs = startMs; this.revealMs = revealMs; this.times = times;
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }
}
