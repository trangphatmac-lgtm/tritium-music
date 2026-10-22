// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Folia; see tritium/licenses/Folia-NOTICE.txt.
package tritium.screens.ncm.navigation;

import java.util.ArrayList;
import java.util.List;

/** Java port of Folia's hexViewport, hexCardFalloff and Bravais blockTemplates/blockReservedSlots. */
public final class FoliaGeometry {
    private FoliaGeometry() { }
    public record Box(int index, double x, double y, double width, double height) {
        public double centerX() { return x + width / 2; }
        public double centerY() { return y + height / 2; }
        public boolean contains(double px, double py) { return px >= x && px <= x + width && py >= y && py <= y + height; }
    }
    public record Falloff(double scale, float opacity) { }
    public static Falloff falloff(double distance, double maxDistance, double minScale, double minOpacity) {
        double progress = Math.min(1, Math.max(0, distance / Math.max(1, maxDistance)));
        return new Falloff(1.1 - (1.1 - minScale) * progress, (float) (1 - (1 - minOpacity) * progress));
    }
    public static List<Box> cards(int count, double width, double height) {
        List<Box> result = new ArrayList<>();
        if (count == 0) return result;
        result.add(new Box(0, -width / 2, -height / 2, width, height));
        int[][] directions = {{0,-1},{-1,0},{-1,1},{0,1},{1,0},{1,-1}};
        for (int radius = 1; result.size() < count; radius++) {
            int x = radius, z = 0;
            for (int[] direction : directions) for (int step = 0; step < radius && result.size() < count; step++) {
                x += direction[0]; z += direction[1];
                result.add(new Box(result.size(), (x + z / 2.0) * width * 1.14 - width / 2,
                        z * height * .97 - height / 2, width, height));
            }
        }
        return result;
    }
    static boolean[] reserved(int column, int row, int count) {
        int state = column * 0x27d4eb2d ^ row * 0x165667b1 ^ 0x5bd1e995;
        state = (state ^ (state >>> 15)) * 0x85ebca6b;
        state = (state ^ (state >>> 13)) * 0xc2b2ae35;
        state ^= state >>> 16;
        int[] order = new int[12];
        for (int i = 0; i < 12; i++) order[i] = i;
        for (int i = 11; i > 0; i--) {
            state += 0x6d2b79f5;
            int t = (state ^ (state >>> 15)) * (1 | state);
            t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
            double random = Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
            int swap = (int) (random * (i + 1));
            int old = order[i]; order[i] = order[swap]; order[swap] = old;
        }
        boolean[] mask = new boolean[12];
        for (int i = 0; i < Math.clamp(count, 0, 6); i++) mask[order[i]] = true;
        return mask;
    }
    static final int[][][] TEMPLATES = {
        {{0,0,3,2},{3,0,6,4},{9,0,3,2},{0,2,3,2},{9,2,3,4},{0,4,4,4},{4,4,2,2},{6,4,3,2},{4,6,2,2},{6,6,2,2},{8,6,2,2},{10,6,2,2}},
        {{0,0,2,3},{2,0,2,3},{4,0,2,3},{6,0,6,4},{0,3,6,2},{6,4,2,4},{8,4,2,4},{10,4,2,2},{0,5,2,3},{2,5,2,3},{4,5,2,3},{10,6,2,2}},
        {{0,0,6,4},{6,0,6,3},{6,3,4,3},{10,3,2,2},{0,4,4,2},{4,4,2,2},{10,5,2,3},{0,6,2,2},{2,6,2,2},{4,6,2,2},{6,6,2,2},{8,6,2,2}},
        {{0,0,2,2},{2,0,2,2},{4,0,4,3},{8,0,2,4},{10,0,2,4},{0,2,2,3},{2,2,2,2},{4,3,4,3},{2,4,2,2},{8,4,4,4},{0,5,2,3},{2,6,6,2}},
    };
}
