package tritium.minecraft.runtime.util;

import org.lwjgl.opengl.GL11;

import tritium.minecraft.runtime.hook.ExtendedCombatAccess;
import tritium.minecraft.runtime.lyric.NativeFontCache;

/** Native OpenGL rendering for the packet modules' server-position copies and timer. */
public final class NetworkGhostRender {
    private final ExtendedCombatAccess game;
    private NativeFontCache fontCache;

    public NetworkGhostRender(ExtendedCombatAccess game) {
        this.game = game;
    }

    public void box(Object entity, float partialTicks, int color, boolean depthTested) {
        if (entity == null) return;
        Object manager = game.invoke(game.minecraft(), "getRenderManager/func_175598_ae");
        double[] b = game.box(entity);
        String[]
                last =
                        {
                            "lastTickPosX/field_70142_S",
                            "lastTickPosY/field_70137_T",
                            "lastTickPosZ/field_70136_U"
                        },
                camera =
                        {
                            "viewerPosX/field_78730_l",
                            "viewerPosY/field_78731_m",
                            "viewerPosZ/field_78728_n"
                        };
        for (int i = 0; i < 3; i++) {
            double p = game.coordinate(entity, i),
                    interp =
                            game.num(entity, last[i])
                                    + (p - game.num(entity, last[i])) * partialTicks
                                    - game.num(manager, camera[i]);
            b[i] += interp - p - .1;
            b[i + 3] += interp - p + .1;
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (!depthTested) {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
            }
            GL11.glColor4f(
                    (color >> 16 & 255) / 255f,
                    (color >> 8 & 255) / 255f,
                    (color & 255) / 255f,
                    .37f);
            double[][] v = {
                {b[0], b[1], b[2]},
                {b[3], b[1], b[2]},
                {b[3], b[1], b[5]},
                {b[0], b[1], b[5]},
                {b[0], b[4], b[2]},
                {b[3], b[4], b[2]},
                {b[3], b[4], b[5]},
                {b[0], b[4], b[5]}
            };
            GL11.glBegin(GL11.GL_QUADS);
            for (int i :
                    new int[] {
                        0, 1, 2, 3, 4, 7, 6, 5, 0, 4, 5, 1, 1, 5, 6, 2, 2, 6, 7, 3, 3, 7, 4, 0
                    }) vertex(v[i]);
            GL11.glEnd();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void vertex(double[] p) {
        GL11.glVertex3d(p[0], p[1], p[2]);
    }

    public void timer(String text, int color, int alpha) {
        if (text == null || alpha <= 0) return;
        Object resolution = game.make("client.gui.ScaledResolution", game.minecraft()),
                font = game.field(game.minecraft(), "fontRendererObj/field_71466_p");
        float
                x =
                        (float)
                                (game.result(resolution, "getScaledWidth/func_78326_a") / 2
                                        - game.result(font, "getStringWidth/func_78256_a", text) / 2
                                        + .5),
                y = (float) (game.result(resolution, "getScaledHeight/func_78328_b") / 2 + 9);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (fontCache == null)
                fontCache = new NativeFontCache(game.minecraft().getClass().getClassLoader());
            try (NativeFontCache.Snapshot ignored = fontCache.save()) {
                game.invoke(
                        game.clazz("client.renderer.GlStateManager"), "enableBlend/func_179147_l");
                game.invoke(
                        game.clazz("client.renderer.GlStateManager"),
                        "tryBlendFuncSeparate/func_179120_a",
                        770,
                        771,
                        1,
                        0);
                game.invoke(
                        font,
                        "drawString/func_175065_a",
                        text,
                        x,
                        y,
                        (color & 0xffffff) | (Math.max(0, Math.min(255, alpha)) << 24),
                        true);
                game.invoke(
                        game.clazz("client.renderer.GlStateManager"), "disableBlend/func_179084_k");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot restore native timer font state", e);
        } finally {
            GL11.glPopAttrib();
        }
    }
}
