package tritium.minecraft.runtime.lyric;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** World-space scene and character animation, independent of Minecraft and OpenGL. */
public final class WorldLyricScene {
    private static final double TRANSLATION_SCALE = .72;
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
        List<String> lines = wrap(entry.text, 200);
        int chars = 0; for (String line : lines) chars += line.length();
        long lineDuration = Math.max(next - entry.startMs, 400);
        long reveal = Math.min(Math.max(chars * 55L, 250), (long) (lineDuration * .6));
        Floating line = new Floating(lines, wrap(entry.translation, 230 / TRANSLATION_SCALE), entry.startMs, reveal,
                WorldLyricTimeline.characterTimes(entry, next), random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
        place(line, settings, camera);
        return line;
    }

    private void place(Floating line, HudConfig.WorldLyrics settings, Camera camera) {
        line.distance = settings.distance; line.offsetX = settings.offsetX; line.offsetY = settings.offsetY; line.spread = settings.spread;
        line.yaw = camera.yaw + line.spreadYaw * settings.spread;
        line.pitch = clamp(camera.pitch + line.spreadPitch * settings.spread * .35, -90, 90);
        double yr = Math.toRadians(line.yaw), pr = Math.toRadians(line.pitch);
        double viewYaw = Math.toRadians(camera.yaw), viewPitch = Math.toRadians(camera.pitch);
        // Camera right and up remain well-defined even when looking straight up or down.
        double rightX = -Math.cos(viewYaw), rightZ = -Math.sin(viewYaw);
        double upX = -Math.sin(viewYaw) * Math.sin(viewPitch), upZ = Math.cos(viewYaw) * Math.sin(viewPitch);
        line.x = camera.eyeX - Math.sin(yr) * Math.cos(pr) * settings.distance + rightX * settings.offsetX + upX * settings.offsetY;
        line.y = camera.eyeY - Math.sin(pr) * settings.distance + Math.cos(viewPitch) * settings.offsetY;
        line.z = camera.eyeZ + Math.cos(yr) * Math.cos(pr) * settings.distance + rightZ * settings.offsetX + upZ * settings.offsetY;
    }

    private List<String> wrap(String text, double maxWidth) {
        List<String> result = new ArrayList<String>(); StringBuilder line = new StringBuilder(); int space = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i); line.append(c); if (c == ' ') space = line.length() - 1;
            if (metrics.width(line.toString()) <= maxWidth || line.length() <= 1) continue;
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
        if (line == active && (line.distance != settings.distance || line.offsetX != settings.offsetX
                || line.offsetY != settings.offsetY || line.spread != settings.spread)) place(line, settings, camera);
        if (settings.stayOnScreen) follow(line, camera, scale, k, module.translation);
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
                glyphs(line, (long) songMillis - line.startMs, alpha * module.opacity, module, now));
    }

    private List<Glyph> glyphs(Floating floating, long elapsed, double alpha, HudConfig.Module module, long now) {
        HudConfig.WorldLyrics settings = module.world;
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
                    int color = settings.rainbow ? rainbow(k, total, now) : module.color;
                    result.add(new Glyph(c, x, sy, width, a, rot, sc, dx, dy, 1, color));
                }
                x += width; k++;
            }
            y += 11;
        }
        if (module.translation) addTranslation(result, floating, elapsed, alpha);
        return result;
    }

    private void addTranslation(List<Glyph> result, Floating floating, long elapsed, double alpha) {
        double appear = clamp(elapsed / 400.0, 0, 1);
        double opacity = alpha * appear * appear * (3 - 2 * appear) * .75;
        if (opacity <= .02) return;
        double y = floating.lines.size() * 11.0 / 2 + 2;
        for (String row : floating.translation) {
            double x = -metrics.width(row) * TRANSLATION_SCALE / 2;
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                double width = metrics.width(String.valueOf(c)) * TRANSLATION_SCALE;
                if (!Character.isWhitespace(c)) result.add(new Glyph(c, x, y, width, opacity, 0, 1, 0, 0, TRANSLATION_SCALE, 0xffffffff));
                x += width;
            }
            y += 11 * TRANSLATION_SCALE;
        }
    }

    private static int rainbow(int index, int total, long now) {
        double phase = now / 1e9 * .08 % 1;
        float hue = (float) (index / (double) Math.max(1, total - 1) * .6 + phase);
        return java.awt.Color.HSBtoRGB(hue, .55f, 1);
    }

    private void follow(Floating line, Camera camera, double scale, double k, boolean translation) {
        double rx = line.x - camera.x, ry = line.y - camera.y, rz = line.z - camera.z;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < .0001 || line.lines.isEmpty()) return;
        double verticalHalf = camera.verticalFov / 2;
        double horizontalHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(verticalHalf)) * camera.aspect));
        double widest = 0; for (String text : line.lines) widest = Math.max(widest, metrics.width(text));
        double height = line.lines.size() * 11.0 / 2;
        if (translation && !line.translation.isEmpty()) {
            for (String text : line.translation) widest = Math.max(widest, metrics.width(text) * TRANSLATION_SCALE);
            height += 2 + line.translation.size() * 11 * TRANSLATION_SCALE;
        }
        double halfWidth = Math.toDegrees(Math.atan2(widest * scale / 2, len));
        double halfHeight = Math.toDegrees(Math.atan2(height * scale, len));
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
        public final double fontScale;
        public final int color;
        Glyph(char character, double x, double y, double width, double alpha, double rotation, double scale, double dx, double dy,
              double fontScale, int color) {
            this.character = character; this.x = x; this.y = y; this.width = width; this.alpha = alpha;
            this.rotation = rotation; this.scale = scale; this.dx = dx; this.dy = dy;
            this.fontScale = fontScale; this.color = color;
        }
    }
    private static final class Floating {
        final List<String> lines, translation; final long startMs, revealMs; final long[] times;
        final double spreadYaw, spreadPitch;
        double distance, offsetX, offsetY, spread;
        double x, y, z, yaw, pitch; long fadeAt, resumedAt = Long.MIN_VALUE;
        Floating(List<String> lines, List<String> translation, long startMs, long revealMs, long[] times, double spreadYaw, double spreadPitch) {
            this.lines = lines; this.startMs = startMs; this.revealMs = revealMs; this.times = times;
            this.translation = translation; this.spreadYaw = spreadYaw; this.spreadPitch = spreadPitch;
        }
    }
}
