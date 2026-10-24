package tritium.rendering.ui.widgets;

import tritium.rendering.material.UiMaterials;
import lombok.Getter;

import tritium.rendering.animation.Interpolations;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;

import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;

/**
 * @author IzumiiKonata
 * Date: 2025/10/8 19:13
 */
public class IconWidget extends AbstractWidget<IconWidget> {

    @Getter
    private String icon;

    public CFontRenderer fr;

    public IconWidget(String icon, CFontRenderer fr, double x, double y, double width, double height) {
        this.icon = icon;
        this.fr = fr;
        this.setBounds(x, y, width, height);
        this.setShouldOverrideMouseCursor(true);
    }

    float alphaAnim = 0f, alphaAnim2 = 0f;
    public double fontOffsetX = 0, fontOffsetY = 0;

    boolean run = false;
    private boolean inheritMaterial;
    private boolean fitIconToBounds;
    private double fittedIconScale = 1;
    private CFontRenderer measuredFont;
    private String measuredIcon;
    private Rectangle2D inkBounds;
    private static final FontRenderContext ICON_METRICS = new FontRenderContext(null, true, true);

    /** Size the visible glyph, excluding the icon font's internal whitespace. */
    public IconWidget setFitIconToBounds(boolean fit) {
        return setFitIconToBounds(fit, 1);
    }

    /** Apply a linear scale after fitting, without changing the button's hit area. */
    public IconWidget setFitIconToBounds(boolean fit, double linearScale) {
        fitIconToBounds = fit;
        fittedIconScale = linearScale;
        return this;
    }

    /** Nested actions share their parent's surface instead of adding another glass disc. */
    public IconWidget setInheritMaterial(boolean inheritMaterial) {
        this.inheritMaterial = inheritMaterial;
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        api.getGLStateManager().disableAlpha();

        int alpha = (int) (this.getAlpha() * 255);

        double size = this.getWidth() * .5;

        boolean glassFace = UiMaterials.enabled() && !inheritMaterial;
        if (glassFace) {
            UiMaterials.icon(getX(), getY(), getWidth(), getHeight(), getAlpha(), Math.min(1f, (alphaAnim + alphaAnim2) * 6f));
        }

        if (!glassFace && alphaAnim != 0f) {
            float a = Math.min(alphaAnim, alpha);
            roundedRect(this.getX() + this.getWidth() * 0.5 - size, this.getY() + this.getHeight() * 0.5 - size, size * 2, size * 2, size - 0.5, reAlpha(this.getHexColor(), a));
        }

        if (!glassFace && alphaAnim2 != 0f) {
            float a = Math.min(alphaAnim2, alpha);
            roundedRect(this.getX() + this.getWidth() * 0.5 - size, this.getY() + this.getHeight() * 0.5 - size, size * 2, size * 2, size - 0.5, reAlpha(this.getHexColor(), a));
        }

        if (run) {
            alphaAnim2 = Interpolations.interpolate(alphaAnim2, 40 * RenderSystem.DIVIDE_BY_255, 0.2f);

            if (Math.abs(alphaAnim2 - 40 * RenderSystem.DIVIDE_BY_255) < 0.05f) {
                run = false;
            }
        } else {
            alphaAnim2 = Interpolations.interpolate(alphaAnim2, 0, 0.2f);
        }

        if (this.isHovering()) {
            alphaAnim = Interpolations.interpolate(alphaAnim, 40 * RenderSystem.DIVIDE_BY_255, 0.2f);
        } else {
            alphaAnim = Interpolations.interpolate(alphaAnim, 0, 0.2f);
        }

        int w = fr.getStringWidth(icon);
        double h = fr.getFontHeight();

        if (fitIconToBounds) {
            if (measuredFont != fr || !icon.equals(measuredIcon)) {
                inkBounds = fr.font.createGlyphVector(ICON_METRICS, icon).getVisualBounds();
                measuredFont = fr;
                measuredIcon = icon;
            }
            // Font glyphs render at half their measured size. Keep a half-unit antialiasing inset.
            double scale = inkBounds.isEmpty() ? 1 : Math.max(0, Math.min(
                    (getWidth() - 1) * 2 / inkBounds.getWidth(),
                    (getHeight() - 1) * 2 / inkBounds.getHeight())) * fittedIconScale;
            api.getGLStateManager().pushMatrix();
            scaleAtPos(getX() + getWidth() * .5, getY() + getHeight() * .5, scale);
        }
        fr.drawString(icon, this.getX() + this.getWidth() * 0.5 - w * 0.5 + fontOffsetX, this.getY() + this.getHeight() * 0.5 - h * 0.5 + fontOffsetY, this.getHexColor());
        if (fitIconToBounds) api.getGLStateManager().popMatrix();
    }

    @Override
    public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {

        this.run = true;

        return super.onMouseClicked(relativeX, relativeY, mouseButton);
    }

    public IconWidget setIcon(String icon) {
        this.icon = icon;
        return this;
    }
}
