package tritium.rendering.ui.widgets;

import java.awt.Color;

/** Retains hue at gray and hue/saturation at black so dragging stays continuous. */
public final class HsvColor {
    private float hue;
    private float saturation;
    private float brightness;
    private float opacity;

    public HsvColor(int argb) {
        setArgb(argb);
    }

    public void setArgb(int argb) {
        float[] hsv = Color.RGBtoHSB((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, null);
        if (hsv[1] > 0) hue = hsv[0];
        if (hsv[2] > 0) saturation = hsv[1];
        brightness = hsv[2];
        opacity = (argb >>> 24) / 255f;
    }

    public void setHue(double hue) {
        this.hue = clamp(hue);
    }

    public void setSaturationAndBrightness(double saturation, double brightness) {
        this.saturation = clamp(saturation);
        this.brightness = clamp(brightness);
    }

    public void setOpacity(double opacity) {
        this.opacity = clamp(opacity);
    }

    public int argb() {
        return (Math.round(opacity * 255) << 24) | (Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF);
    }

    public float hue() { return hue; }
    public float saturation() { return saturation; }
    public float brightness() { return brightness; }
    public float opacity() { return opacity; }

    private static float clamp(double value) {
        return (float) Math.max(0, Math.min(1, value));
    }
}
