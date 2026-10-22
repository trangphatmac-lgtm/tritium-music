package tritium.screens.ncm.navigation;

import java.util.ArrayList;
import java.util.List;

/** Pixel-aligned gutters. Each cover owns only the gutter to its right and below. */
final class FoliaWallSeams {
    private FoliaWallSeams() { }

    static FoliaGeometry.Box snap(FoliaGeometry.Box b, double gap, double pixelsPerUnit) {
        double x = pixel(b.x(), pixelsPerUnit), y = pixel(b.y(), pixelsPerUnit);
        double gutter = pixel(gap, pixelsPerUnit);
        // Round the shared grid boundary, then subtract one identical gutter. Rounding
        // each cover's right edge independently makes adjacent seams vary by a pixel.
        double right = pixel(b.x() + b.width() + gap, pixelsPerUnit) - gutter;
        double bottom = pixel(b.y() + b.height() + gap, pixelsPerUnit) - gutter;
        return new FoliaGeometry.Box(b.index(), x, y, right - x, bottom - y);
    }

    static List<FoliaGeometry.Box> paperEdges(FoliaGeometry.Box b, double gap, double pixelsPerUnit, double hover) {
        int gapPixels = (int)Math.round(gap * pixelsPerUnit);
        // A narrow seam has room for one sheet; never squeeze two strokes together.
        int layers = gapPixels >= 6 ? 2 : gapPixels >= 3 ? 1 : 0;
        if (layers == 0 || hover >= .995) return List.of();
        double stroke = 1 / pixelsPerUnit;
        List<FoliaGeometry.Box> edges = new ArrayList<>(layers * 2);
        for (int layer = 1; layer <= layers; layer++) {
            double offset = Math.floor(gapPixels * layer / (double)(layers + 1) * (1 - hover)) / pixelsPerUnit;
            if (offset < stroke) continue;
            // End flush inside the face bounds, not at a diagonally shifted corner.
            // This leaves T/cross junctions clear and cannot paint over a neighbour.
            edges.add(new FoliaGeometry.Box(b.index(), pixel(b.x() + b.width() + offset, pixelsPerUnit),
                    pixel(b.y(), pixelsPerUnit) + stroke, stroke, Math.max(0, pixel(b.height(), pixelsPerUnit) - 2 * stroke)));
            edges.add(new FoliaGeometry.Box(b.index(), pixel(b.x(), pixelsPerUnit) + stroke,
                    pixel(b.y() + b.height() + offset, pixelsPerUnit), Math.max(0, pixel(b.width(), pixelsPerUnit) - 2 * stroke), stroke));
        }
        return edges;
    }

    private static double pixel(double value, double pixelsPerUnit) {
        return Math.round(value * pixelsPerUnit) / pixelsPerUnit;
    }
}
