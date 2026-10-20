package tritium.rendering.ui.container;

import lombok.Getter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.ui.AbstractWidget;
import tritium.utils.cursor.CursorUtils;

import java.util.List;

/**
 * 一个从上至下排列的可以滚动的容器
 * @author IzumiiKonata
 * Date: 2025/7/8 21:45
 */
public class ScrollPanel extends AbstractWidget<ScrollPanel> {

    @Getter
    private double spacing = 0;
    public double actualScrollOffset = 0, targetScrollOffset = 0;
    private final OverlayScrollbar scrollbar = new OverlayScrollbar();

    @Getter
    private Alignment alignment = Alignment.VERTICAL;

    /**
     * 子组件的排列方式
     */
    public enum Alignment {
        /**
         * 仅垂直排列 (默认)
         */
        VERTICAL,
        /**
         * 仅水平排列
         */
        HORIZONTAL,
        /**
         * 垂直排列并水平填充
         */
        VERTICAL_WITH_HORIZONTAL_FILL
    }

    public ScrollPanel setAlignment(Alignment alignment) {

        if (alignment == null)
            throw new IllegalArgumentException("Alignment cannot be null!");

        if (this.alignment != alignment) scrollbar.reset();
        this.alignment = alignment;
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        clampScrollOffsets();
        if (scrollbar.isDragging()) {
            if (Mouse.isCreated() && !Mouse.isButtonDown(0)) scrollbar.endDrag();
            else dragScrollbar(mouseX - getX(), mouseY - getY());
        }
        this.actualScrollOffset = Interpolations.interpolate(this.actualScrollOffset, this.targetScrollOffset, 1f);
        if (Math.abs(actualScrollOffset - targetScrollOffset) < .01) actualScrollOffset = targetScrollOffset;

        // 设置子组件的垂直位置
        this.alignChildren();
        OverlayScrollbar.Geometry geometry = scrollbarGeometry();
        double pointer = scrollAxis(mouseX - getX(), mouseY - getY());
        boolean atEdge = isAtScrollbarEdge(mouseX, mouseY);
        scrollbar.update(geometry, actualScrollOffset, Math.abs(actualScrollOffset - targetScrollOffset) > .01,
                atEdge, atEdge && pointer >= geometry.start() && pointer <= geometry.start() + geometry.length(),
                System.nanoTime());
    }

    @Override
    protected void renderDebugLayout() {
        super.renderDebugLayout();
        Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), reAlpha(0x00F50EE8, this.getAlpha() * .05f));
    }

    public ScrollPanel setSpacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    @Override
    public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
        if (scrollbar.isDragging()) return true;
        // 垂直滑动
        this.performScroll(dWheel);
        super.onDWheel(mouseX, mouseY, dWheel);

        return true;
    }

    private void performScroll(int dWheel) {

        if (dWheel != 0) {

            double strength = 12;

            if (Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_LSHIFT))
                strength *= 2;

            if (dWheel > 0)
                this.targetScrollOffset -= strength;
            else
                this.targetScrollOffset += strength;
        }

        this.targetScrollOffset = Math.max(0, Math.min(this.targetScrollOffset, maxScrollOffset()));
    }

    public void scrollToEnd() {
        this.targetScrollOffset = maxScrollOffset();
    }

    public void scrollToEndImmediately() {
        this.actualScrollOffset = this.targetScrollOffset = maxScrollOffset();
    }

    public boolean isScrolledToEnd() {
        return this.targetScrollOffset >= maxScrollOffset();
    }

    private double contentLength() {
        return switch (this.alignment) {
            case VERTICAL -> getChildrenHeightSum();
            case HORIZONTAL -> getChildrenWidthSum();
            case VERTICAL_WITH_HORIZONTAL_FILL -> getChildrenHeightSumHorizontalFill();
        };
    }

    private double viewportLength() {
        return Math.max(0, alignment == Alignment.HORIZONTAL ? getWidth() : getHeight());
    }

    private double maxScrollOffset() {
        return Math.max(0, contentLength() - viewportLength());
    }

    private void clampScrollOffsets() {
        double maximum = maxScrollOffset();
        targetScrollOffset = Math.max(0, Math.min(targetScrollOffset, maximum));
        actualScrollOffset = Math.max(0, Math.min(actualScrollOffset, maximum));
        if (maximum == 0) scrollbar.reset();
    }

    public void alignChildren() {
        clampScrollOffsets();
        double offsetX = 0;
        double offsetY = 0;

        if (this.alignment == Alignment.VERTICAL || this.alignment == Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
            offsetY = -this.actualScrollOffset;
        else
            offsetX = -this.actualScrollOffset;

        for (AbstractWidget<?> child : this.getChildren()) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            switch (this.alignment) {
                case VERTICAL -> {
                    child.setPosition(child.getRelativeX(), offsetY);
                    offsetY += height + spacing;
                }
                case HORIZONTAL -> {
                    child.setPosition(offsetX, child.getRelativeY());
                    offsetX += width + spacing;
                }
                case VERTICAL_WITH_HORIZONTAL_FILL -> {
                    if (offsetX + width > this.getWidth()) {
                        offsetX = 0;
                        offsetY += height + spacing;
                    }

                    child.setPosition(offsetX, offsetY);
                    offsetX += width + spacing;
                }
            }
        }
    }

    protected double getChildrenHeightSum() {
        double result = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            result += height + this.spacing;
        }

        if (result > 0)
            result -= this.spacing;

        return result;
    }

    protected double getChildrenWidthSum() {
        double result = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            result += width + this.spacing;
        }

        if (result > 0)
            result -= this.spacing;

        return result;
    }

    protected double getChildrenHeightSumHorizontalFill() {
        double result = 0;
        double offsetX = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            if (offsetX == 0 && result == 0) {
                result += height + spacing;
            }

            if (offsetX + width > this.getWidth()) {
                offsetX = 0;
                result += height + spacing;
            }

            offsetX += width + spacing;
        }

        if (result > 0)
            result -= this.spacing;

        return result;
    }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        if (isHidden()) {
            scrollbar.reset();
            return;
        }
        StencilClipManager.beginClip(() -> Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), -1));

        super.renderWidget(mouseX, mouseY, dWheel);
        renderScrollbar(mouseX, mouseY);

        StencilClipManager.endClip();
    }

    private OverlayScrollbar.Geometry scrollbarGeometry() {
        return OverlayScrollbar.geometry(viewportLength(), contentLength(), actualScrollOffset);
    }

    private double scrollAxis(double x, double y) {
        return alignment == Alignment.HORIZONTAL ? x : y;
    }

    private boolean isAtScrollbarEdge(double mouseX, double mouseY) {
        if (!testHovered(mouseX, mouseY)) return false;
        // Respect any outer scrolling clip as well.
        for (AbstractWidget<?> parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPanel scroll && !scroll.testHovered(mouseX, mouseY)) return false;
        }
        return alignment == Alignment.HORIZONTAL
                ? mouseY >= getY() + getHeight() - OverlayScrollbar.HIT_WIDTH
                : mouseX >= getX() + getWidth() - OverlayScrollbar.HIT_WIDTH;
    }

    private void renderScrollbar(double mouseX, double mouseY) {
        OverlayScrollbar.Geometry geometry = scrollbarGeometry();
        if (!geometry.scrollable() || scrollbar.alpha() <= 0) return;
        double width = scrollbar.width();
        double x = alignment == Alignment.HORIZONTAL ? getX() + geometry.start()
                : getX() + getWidth() - OverlayScrollbar.INSET - width;
        double y = alignment == Alignment.HORIZONTAL ? getY() + getHeight() - OverlayScrollbar.INSET - width
                : getY() + geometry.start();
        roundedRect(x, y, alignment == Alignment.HORIZONTAL ? geometry.length() : width,
                alignment == Alignment.HORIZONTAL ? width : geometry.length(), width / 2,
                reAlpha(0xFF808080, getAlpha() * scrollbar.alpha()));
        if (scrollbar.isDragging() || isAtScrollbarEdge(mouseX, mouseY)) {
            CursorUtils.setOverride(CursorUtils.ARROW);
        }
    }

    @Override
    protected boolean onMouseClickBeforeChildren(double mouseX, double mouseY, int mouseButton) {
        OverlayScrollbar.Geometry geometry = scrollbarGeometry();
        if (mouseButton != 0 || !geometry.scrollable() || !isAtScrollbarEdge(mouseX, mouseY)) return false;
        scrollbar.beginDrag(geometry, scrollAxis(mouseX - getX(), mouseY - getY()));
        dragScrollbar(mouseX - getX(), mouseY - getY());
        return true;
    }

    private void dragScrollbar(double relativeX, double relativeY) {
        actualScrollOffset = targetScrollOffset = scrollbar.dragOffset(scrollbarGeometry(), scrollAxis(relativeX, relativeY));
        alignChildren();
    }

    @Override
    public void onMouseReleased(double relativeX, double relativeY, int mouseButton) {
        if (mouseButton == 0 && scrollbar.isDragging()) {
            dragScrollbar(relativeX, relativeY);
            scrollbar.endDrag();
        }
    }

    @Override
    protected boolean shouldRenderChildren(AbstractWidget<?> child, double mouseX, double mouseY) {

        switch (this.getAlignment()) {
            case VERTICAL, VERTICAL_WITH_HORIZONTAL_FILL -> {
                if (child.getRelativeY() + child.getHeight() < 0)
                    return false;

                if (child.getRelativeY() > this.getHeight())
                    return false;
            }
            case HORIZONTAL -> {
                if (child.getRelativeX() + child.getWidth() < 0)
                    return false;

                if (child.getRelativeX() > this.getWidth())
                    return false;
            }
        }

        return true;
    }

    @Override
    protected boolean shouldClickChildren(double mouseX, double mouseY) {
//        System.out.println(this.testHovered(mouseX, mouseY));
        return this.testHovered(mouseX, mouseY);
    }

    @Override
    public boolean canBeScrolled() {
        return true;
    }
}
