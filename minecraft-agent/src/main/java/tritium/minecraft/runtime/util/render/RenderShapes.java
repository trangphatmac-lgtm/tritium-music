package tritium.minecraft.runtime.util.render;
import org.lwjgl.opengl.GL11;
public final class RenderShapes {
    public static final double[] SIN_0_90 = new double[31], COS_0_90 = new double[31];
    public static final double[] SIN_90_180 = new double[31], COS_90_180 = new double[31];

    static {
        for (int n = 0; n <= 90; n += 3) {
            double d = (float) n * ((float) Math.PI / 180);
            SIN_0_90[n / 3] = Math.sin(d);
            COS_0_90[n / 3] = Math.cos(d);
        }
        for (int n = 90; n <= 180; n += 3) {
            double d = (float) n * ((float) Math.PI / 180);
            SIN_90_180[(n - 90) / 3] = Math.sin(d);
            COS_90_180[(n - 90) / 3] = Math.cos(d);
        }
    }

    public static void roundedRect(float x, float y, float x2, float y2, float r, int color) {
        int n;
        r = Math.min(r, Math.min(((x2 *= 2.0f) - (x *= 2.0f)) / 2.0f, ((y2 *= 2.0f) - (y *= 2.0f)) / 2.0f));
        float f6 = x + r, f7 = y + r, f8 = x2 - r, f9 = y2 - r;
        GL11.glPushAttrib(0);
        GL11.glScaled(0.5, 0.5, 0.5);
        GL11.glEnable(3042);
        GL11.glDisable(3553);
        GL11.glEnable(2848);
        GL11.glBegin(9);
        setColor(color);
        for (n = 0; n <= 30; ++n) GL11.glVertex2d(f6 + SIN_0_90[n] * -r, f7 + COS_0_90[n] * -r);
        for (n = 0; n <= 30; ++n) GL11.glVertex2d(f6 + SIN_90_180[n] * -r, f9 + COS_90_180[n] * -r);
        for (n = 0; n <= 30; ++n) GL11.glVertex2d(f8 + SIN_0_90[n] * r, f9 + COS_0_90[n] * r);
        for (n = 0; n <= 30; ++n) GL11.glVertex2d(f8 + SIN_90_180[n] * r, f7 + COS_90_180[n] * r);
        GL11.glEnd();
        GL11.glEnable(3553);
        GL11.glDisable(3042);
        GL11.glDisable(2848);
        GL11.glEnable(3553);
        GL11.glScaled(2.0, 2.0, 2.0);
        GL11.glPopAttrib();
        resetColor();
    }
    public static void roundedRectBorder(float f, float f2, float f3, float f4, float f5, int n, int n2, int n3) {
        int i;
        f *= 2.0f;
        f2 *= 2.0f;
        f3 *= 2.0f;
        f4 *= 2.0f;
        GL11.glPushAttrib(1);
        GL11.glScaled(0.5, 0.5, 0.5);
        GL11.glEnable(3042);
        GL11.glDisable(3553);
        GL11.glEnable(2848);
        GL11.glBegin(9);
        setColor(n);
        float f6 = f + f5;
        float f7 = f2 + f5;
        float f8 = f3 - f5;
        float f9 = f4 - f5;
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f6 + SIN_0_90[i] * (double) (-f5), (double) f7 + COS_0_90[i] * (double) (-f5));
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f6 + SIN_90_180[i] * (double) (-f5), (double) f9 + COS_90_180[i] * (double) (-f5));
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f8 + SIN_0_90[i] * (double) f5, (double) f9 + COS_0_90[i] * (double) f5);
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f8 + SIN_90_180[i] * (double) f5, (double) f7 + COS_90_180[i] * (double) f5);
        GL11.glEnd();
        GL11.glPushMatrix();
        GL11.glShadeModel(7425);
        GL11.glLineWidth(2.0f);
        GL11.glBegin(2);
        if (n2 != 0) {
            setColor(n2);
        }
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f6 + SIN_0_90[i] * (double) (-f5), (double) f7 + COS_0_90[i] * (double) (-f5));
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f6 + SIN_90_180[i] * (double) (-f5), (double) f9 + COS_90_180[i] * (double) (-f5));
        if (n3 != 0) {
            setColor(n3);
        }
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f8 + SIN_0_90[i] * (double) f5, (double) f9 + COS_0_90[i] * (double) f5);
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f8 + SIN_90_180[i] * (double) f5, (double) f7 + COS_90_180[i] * (double) f5);
        GL11.glEnd();
        GL11.glPopMatrix();
        GL11.glEnable(3553);
        GL11.glDisable(3042);
        GL11.glDisable(2848);
        GL11.glEnable(3553);
        GL11.glScaled(2.0, 2.0, 2.0);
        GL11.glPopAttrib();
        GL11.glLineWidth(1.0f);
        GL11.glShadeModel(7424);
        resetColor();
    }
    public static void roundedRectGradient(float f, float f2, float f3, float f4, float f5, int n, int n2, int n3, int n4) {
        int i;
        f5 = Math.min(f5, Math.min(((f3 *= 2.0f) - (f *= 2.0f)) / 2.0f, ((f4 *= 2.0f) - (f2 *= 2.0f)) / 2.0f));
        float f6 = f + f5;
        float f7 = f2 + f5;
        float f8 = f3 - f5;
        float f9 = f4 - f5;
        GL11.glEnable(3042);
        GL11.glDisable(3553);
        GL11.glBlendFunc(770, 771);
        GL11.glEnable(2848);
        GL11.glShadeModel(7425);
        GL11.glPushAttrib(0);
        GL11.glScaled(0.5, 0.5, 0.5);
        GL11.glEnable(3042);
        GL11.glDisable(3553);
        GL11.glEnable(2848);
        GL11.glShadeModel(7425);
        GL11.glBegin(9);
        setColor(n);
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f6 + SIN_0_90[i] * (double) (-f5), (double) f7 + COS_0_90[i] * (double) (-f5));
        setColor(n2);
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f6 + SIN_90_180[i] * (double) (-f5), (double) f9 + COS_90_180[i] * (double) (-f5));
        setColor(n3);
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f8 + SIN_0_90[i] * (double) f5, (double) f9 + COS_0_90[i] * (double) f5);
        setColor(n4);
        for (i = 0; i <= 30; ++i) GL11.glVertex2d((double) f8 + SIN_90_180[i] * (double) f5, (double) f7 + COS_90_180[i] * (double) f5);
        GL11.glEnd();
        GL11.glEnable(3553);
        GL11.glDisable(3042);
        GL11.glDisable(2848);
        GL11.glDisable(3042);
        GL11.glEnable(3553);
        GL11.glScaled(2.0, 2.0, 2.0);
        GL11.glPopAttrib();
        GL11.glEnable(3553);
        GL11.glDisable(3042);
        GL11.glDisable(2848);
        GL11.glShadeModel(7424);
        resetColor();
    }
    public static int blendInt(int n, int n2, double d) {
        int n3 = (int) (d * 255.0);
        int n4 = 255 - n3;
        int a = (n >> 24 & 0xFF) * n3 + (n2 >> 24 & 0xFF) * n4 >> 8;
        int r = (n >> 16 & 0xFF) * n3 + (n2 >> 16 & 0xFF) * n4 >> 8;
        int g = (n >> 8 & 0xFF) * n3 + (n2 >> 8 & 0xFF) * n4 >> 8;
        int b = (n & 0xFF) * n3 + (n2 & 0xFF) * n4 >> 8;
        return a << 24 | r << 16 | g << 8 | b;
    }
    public static int withAlpha(int rgb, int alpha) {
        return rgb & 0xFFFFFF | alpha << 24;
    }
    public static int clampAlpha(int n) {
        if (n > 255) return 255;
        if (n < 4) return 4;
        return n;
    }
    public static void resetColor() {
        RawState.color(1.0f, 1.0f, 1.0f, 1.0f);
    }
    public static void setColor(int n) {
        float a = (float) (n >> 24 & 0xFF) / 255.0f;
        float r = (float) (n >> 16 & 0xFF) / 255.0f;
        float g = (float) (n >> 8 & 0xFF) / 255.0f;
        float b = (float) (n & 0xFF) / 255.0f;
        RawState.color(r, g, b, a);
    }
}
