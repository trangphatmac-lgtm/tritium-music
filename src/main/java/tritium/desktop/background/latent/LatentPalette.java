// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Folia's colorPalette.ts and LatentBackground.tsx; see THIRD_PARTY_NOTICES.md.
package tritium.desktop.background.latent;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Folia's population-weighted median-cut cover palette; retains neutral regions as well as accents. */
public final class LatentPalette {
    private LatentPalette() { }
    private record Sample(int r, int g, int b, int weight) {
        int channel(int channel) { return channel == 0 ? r : channel == 1 ? g : b; }
    }
    private record Bucket(List<Sample> samples, int weight) {
        Bucket(List<Sample> samples) { this(samples, samples.stream().mapToInt(Sample::weight).sum()); }
        int range(int channel) {
            return samples.stream().mapToInt(s -> s.channel(channel)).max().orElse(0)
                    - samples.stream().mapToInt(s -> s.channel(channel)).min().orElse(0);
        }
        int widest() {
            int channel = 0;
            for (int i = 1; i < 3; i++) if (range(i) > range(channel)) channel = i;
            return channel;
        }
        int average() {
            int[] sum = new int[3];
            for (Sample sample : samples) for (int i = 0; i < 3; i++) sum[i] += sample.channel(i) * sample.weight;
            return 0xFF000000 | (Math.round(sum[0] / (float) weight) << 16)
                    | (Math.round(sum[1] / (float) weight) << 8) | Math.round(sum[2] / (float) weight);
        }
    }

    public static int[] extract(BufferedImage image) {
        if (image == null) return new int[0];
        BufferedImage small = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = small.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, 50, 50, null);
        } finally { graphics.dispose(); }
        var histogram = new LinkedHashMap<Integer, int[]>();
        for (int y = 0; y < 50; y++) for (int x = 0; x < 50; x++) {
            int argb = small.getRGB(x, y);
            if ((argb >>> 24) < 128) continue;
            int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
            int[] entry = histogram.computeIfAbsent(((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4), key -> new int[4]);
            entry[0] += r; entry[1] += g; entry[2] += b; entry[3]++;
        }
        if (histogram.isEmpty()) return new int[0];
        List<Sample> samples = new ArrayList<>();
        for (int[] value : histogram.values()) samples.add(new Sample(Math.round(value[0] / (float) value[3]),
                Math.round(value[1] / (float) value[3]), Math.round(value[2] / (float) value[3]), value[3]));
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(new Bucket(samples));
        while (buckets.size() < Math.min(6, samples.size())) {
            int selected = -1;
            double score = -1;
            for (int i = 0; i < buckets.size(); i++) {
                Bucket candidate = buckets.get(i);
                double next = candidate.range(candidate.widest()) * Math.sqrt(candidate.weight);
                if (candidate.samples.size() > 1 && next > score) { score = next; selected = i; }
            }
            if (selected < 0) break;
            Bucket bucket = buckets.remove(selected);
            List<Sample> sorted = new ArrayList<>(bucket.samples);
            int channel = bucket.widest();
            sorted.sort(Comparator.comparingInt(s -> s.channel(channel)));
            int split = 1, accumulated = 0;
            for (int i = 0; i < sorted.size() - 1; i++) {
                accumulated += sorted.get(i).weight;
                split = i + 1;
                if (accumulated >= bucket.weight / 2.0) break;
            }
            buckets.add(selected, new Bucket(sorted.subList(split, sorted.size())));
            buckets.add(selected, new Bucket(sorted.subList(0, split)));
        }
        buckets.sort(Comparator.comparingInt(Bucket::weight).reversed());
        return buckets.stream().mapToInt(Bucket::average).toArray();
    }

    /** Background, dithering back/front, then the six mesh colors, in Folia's original order. */
    public static int[] resolve(int[] cover, int background, int primary, int accent, int secondary, String source) {
        if (source.equals("theme")) cover = new int[0];
        int a = at(cover, 0, secondary);
        if (source.equals("cover-only") && cover.length > 0) {
            int b = at(cover, 1, a), c = at(cover, 2, b);
            return new int[]{background, c, a, a, b, c, at(cover, 3, a), at(cover, 4, b), at(cover, 5, c)};
        }
        int b = at(cover, 1, primary);
        return new int[]{background, background, a, a, b, at(cover, 2, a), at(cover, 3, b), background, accent};
    }

    private static int at(int[] values, int index, int fallback) { return index < values.length ? values[index] : fallback; }
}
