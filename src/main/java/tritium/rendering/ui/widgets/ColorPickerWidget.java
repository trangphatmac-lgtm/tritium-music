package tritium.rendering.ui.widgets;

import org.lwjgl.input.Mouse;
import tritium.management.FontManager;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.Shaders;
import tritium.rendering.ui.AbstractWidget;

import java.awt.Color;
import java.util.Locale;
import java.util.function.IntConsumer;

/** A saturation/value plane, a hue slider and an optional alpha slider. */
public final class ColorPickerWidget extends AbstractWidget<ColorPickerWidget> {
    private static final double PLANE_Y = 22;
    private static final double PLANE_HEIGHT = 120;
    private static final double HUE_Y = 174;
    private static final double ALPHA_Y = 252;
    private static final double TRACK_HEIGHT = 14;
    private final HsvColor color;
    private final boolean alphaEnabled;
    private final IntConsumer onChange;
    private DragTarget dragging;

    private enum DragTarget { PLANE, HUE, ALPHA }

    public ColorPickerWidget(int argb, boolean alphaEnabled, IntConsumer onChange) {
        this.color = new HsvColor(argb);
        this.alphaEnabled = alphaEnabled;
        this.onChange = onChange;
        if (!alphaEnabled) color.setOpacity(1);
        setBounds(0, 0, 100, alphaEnabled ? 282 : 216);
        setShouldOverrideMouseCursor(true);
    }

    public void setArgb(int argb) {
        if (color.argb() != argb) color.setArgb(argb);
        if (!alphaEnabled) color.setOpacity(1);
    }

    @Override
    public boolean onMouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        if (inTrack(x, y, PLANE_Y, PLANE_HEIGHT)) dragging = DragTarget.PLANE;
        else if (inTrack(x, y, HUE_Y - 5, TRACK_HEIGHT + 10)) dragging = DragTarget.HUE;
        else if (alphaEnabled && inTrack(x, y, ALPHA_Y - 5, TRACK_HEIGHT + 10)) dragging = DragTarget.ALPHA;
        else return false;
        updateColor(x, y);
        return true;
    }

    @Override
    public void onMouseReleased(double x, double y, int button) {
        if (button == 0 && dragging != null) {
            updateColor(x, y);
            dragging = null;
        }
    }

    private boolean inTrack(double x, double y, double top, double height) {
        return x >= 0 && x <= getWidth() && y >= top && y <= top + height;
    }

    private void updateColor(double x, double y) {
        double horizontal = x / Math.max(1, getWidth());
        switch (dragging) {
            case PLANE -> color.setSaturationAndBrightness(horizontal, 1 - (y - PLANE_Y) / PLANE_HEIGHT);
            case HUE -> color.setHue(horizontal);
            case ALPHA -> color.setOpacity(horizontal);
        }
        onChange.accept(color.argb());
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        if (dragging != null) {
            if (Mouse.isButtonDown(0)) updateColor(mouseX - getX(), mouseY - getY());
            else dragging = null;
        }
        double x = getX(), y = getY(), width = Math.max(1, getWidth());
        int textColor = reAlpha(0xFFFFFFFF, getAlpha() * .65f);
        FontManager.pf12bold.drawString("饱和度 / 明度", x, y, textColor);
        Shaders.RQG_SHADER.draw(x, y + PLANE_Y, width, PLANE_HEIGHT, 4, getAlpha(),
                Color.BLACK, Color.WHITE, Color.BLACK, Color.getHSBColor(color.hue(), 1, 1));
        drawMarker(x + color.saturation() * width, y + PLANE_Y + (1 - color.brightness()) * PLANE_HEIGHT,
                color.argb() | 0xFF000000);

        FontManager.pf12bold.drawString("色相", x, y + 152, textColor);
        for (int i = 0; i < 6; i++) {
            RenderSystem.drawGradientRectLeftToRight(x + width * i / 6, y + HUE_Y,
                    x + width * (i + 1) / 6, y + HUE_Y + TRACK_HEIGHT,
                    reAlpha(Color.HSBtoRGB(i / 6f, 1, 1), getAlpha()),
                    reAlpha(Color.HSBtoRGB((i + 1) / 6f, 1, 1), getAlpha()));
        }
        drawMarker(x + color.hue() * width, y + HUE_Y + TRACK_HEIGHT / 2,
                Color.HSBtoRGB(color.hue(), 1, 1));
        FontManager.pf12bold.drawString(String.format(Locale.ROOT, "H %d°   S %d%%   V %d%%",
                Math.round(color.hue() * 360), Math.round(color.saturation() * 100),
                Math.round(color.brightness() * 100)), x, y + 200, textColor);

        if (alphaEnabled) {
            FontManager.pf12bold.drawString("不透明度  " + Math.round(color.opacity() * 100) + "%", x, y + 228, textColor);
            drawCheckerboard(x, y + ALPHA_Y, width, TRACK_HEIGHT);
            RenderSystem.drawGradientRectLeftToRight(x, y + ALPHA_Y, x + width, y + ALPHA_Y + TRACK_HEIGHT,
                    reAlpha(color.argb(), 0), reAlpha(color.argb(), getAlpha()));
            drawMarker(x + color.opacity() * width, y + ALPHA_Y + TRACK_HEIGHT / 2, color.argb() | 0xFF000000);
        }
    }

    private void drawMarker(double x, double y, int fill) {
        roundedRect(x - 6, y - 6, 12, 12, 6, reAlpha(0xFF101010, getAlpha()));
        roundedRect(x - 5, y - 5, 10, 10, 5, reAlpha(0xFFFFFFFF, getAlpha()));
        roundedRect(x - 3, y - 3, 6, 6, 3, reAlpha(fill, getAlpha()));
    }

    public void drawCheckerboard(double x, double y, double width, double height) {
        for (int row = 0; row * 7 < height; row++) {
            for (int column = 0; column * 7 < width; column++) {
                Rect.draw(x + column * 7, y + row * 7, Math.min(7, width - column * 7),
                        Math.min(7, height - row * 7),
                        reAlpha((row + column) % 2 == 0 ? 0xFF777777 : 0xFF444444, getAlpha()));
            }
        }
    }
}
