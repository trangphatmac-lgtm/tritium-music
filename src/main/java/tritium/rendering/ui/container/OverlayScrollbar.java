package tritium.rendering.ui.container;

/** Geometry and animation for an unstyled, draggable overlay scrollbar (in UI units). */
final class OverlayScrollbar {
    static final double INSET = 3;
    static final double HIT_WIDTH = 14;
    static final double THIN_WIDTH = 3;
    static final double HOVER_WIDTH = 6;
    static final double MIN_LENGTH = 32;
    static final float OPACITY = .65f;
    private static final long HIDE_DELAY_NANOS = 600_000_000L;

    private long lastFrame;
    private long lastScroll;
    private boolean scrolled;
    private double previousOffset;
    private double opacity;
    private double width = THIN_WIDTH;
    private boolean dragging;
    private double grabFraction;

    record Geometry(double start, double length, double travel, double maxScroll) {
        boolean scrollable() {
            return maxScroll > 0 && travel > 0;
        }
    }

    static Geometry geometry(double viewport, double content, double offset) {
        double track = Math.max(0, viewport - INSET * 2);
        double maxScroll = Math.max(0, content - viewport);
        double length = Math.min(track, Math.max(MIN_LENGTH, track * viewport / Math.max(1, content)));
        double travel = track - length;
        double start = INSET + (maxScroll > 0 ? clamp(offset / maxScroll, 0, 1) * travel : 0);
        return new Geometry(start, length, travel, maxScroll);
    }

    void update(Geometry geometry, double offset, boolean moving, boolean atEdge, boolean overThumb, long now) {
        double elapsedMs = lastFrame == 0 ? 0 : Math.max(0, (now - lastFrame) / 1_000_000.0);
        lastFrame = now;
        if (!geometry.scrollable()) {
            reset();
            previousOffset = offset;
            return;
        }
        if (moving || Math.abs(offset - previousOffset) > .01 || dragging) {
            scrolled = true;
            lastScroll = now;
        }
        previousOffset = offset;
        boolean visible = atEdge || dragging || (scrolled && now - lastScroll < HIDE_DELAY_NANOS);
        double fadeStep = elapsedMs / 160;
        opacity = visible ? Math.min(1, opacity + fadeStep) : Math.max(0, opacity - fadeStep);
        double targetWidth = overThumb || dragging ? HOVER_WIDTH : THIN_WIDTH;
        width += (targetWidth - width) * -Math.expm1(-elapsedMs / 55);
        if (Math.abs(width - targetWidth) < .01) width = targetWidth;
    }

    void beginDrag(Geometry geometry, double pointer) {
        dragging = true;
        // Preserve the point grabbed on the thumb; a click on the track centers it.
        grabFraction = pointer >= geometry.start() && pointer <= geometry.start() + geometry.length()
                ? (pointer - geometry.start()) / geometry.length() : .5;
    }

    double dragOffset(Geometry geometry, double pointer) {
        if (!geometry.scrollable()) return 0;
        return clamp((pointer - INSET - grabFraction * geometry.length()) / geometry.travel(), 0, 1)
                * geometry.maxScroll();
    }

    void endDrag() {
        dragging = false;
    }

    void reset() {
        dragging = false;
        scrolled = false;
        opacity = 0;
        width = THIN_WIDTH;
    }

    boolean isDragging() {
        return dragging;
    }

    double width() {
        return width;
    }

    float alpha() {
        return (float) opacity * OPACITY;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
