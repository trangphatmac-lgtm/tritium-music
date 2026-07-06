package tritium.desktop.hud;

import tritium.desktop.DesktopAppState;
import tritium.desktop.HudElementPreferences;
import tritium.desktop.MusicPreferences;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HudManager {
    private final List<HudRenderer> renderers = List.of(
            new MusicSpectrumHudRenderer(),
            new MusicLyricsHudRenderer(),
            new MusicInfoHudRenderer()
    );

    private JWindow window;
    private HudPanel panel;
    private Timer timer;
    private Rectangle screenBounds = new Rectangle(0, 0, 1, 1);
    private boolean smokeMode;
    private boolean lastPassThrough = true;
    private boolean requested;
    private boolean startPending;
    private long startAfterMillis;
    private DragState dragState;

    public void start(boolean smokeMode) {
        this.smokeMode = smokeMode;
        this.requested = true;
        if (!shouldShowOverlay()) {
            return;
        }
        scheduleStart(750L);
    }

    public void onPreferencesChanged() {
        if (!requested || window != null || !shouldShowOverlay()) {
            return;
        }
        scheduleStart(250L);
    }

    public void pump() {
        if (!startPending || window != null || !shouldShowOverlay()) {
            return;
        }

        if (System.currentTimeMillis() < startAfterMillis) {
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("[HUD] Headless environment detected; HUD overlay disabled.");
            startPending = false;
            return;
        }

        startPending = false;
        if (isMac()) {
            startOnEdt();
        } else {
            EventQueue.invokeLater(this::startOnEdt);
        }
    }

    private void scheduleStart(long delayMillis) {
        startPending = true;
        startAfterMillis = System.currentTimeMillis() + delayMillis;
    }

    private boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    public void stop() {
        requested = false;
        startPending = false;
        if (window == null && timer == null && panel == null) {
            return;
        }

        if (isMac()) {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            window = null;
            panel = null;
            dragState = null;
            return;
        }

        Runnable task = () -> {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            if (window != null) {
                window.setVisible(false);
                window.dispose();
                window = null;
            }
            panel = null;
            dragState = null;
        };

        if (isMac() || SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }

        try {
            EventQueue.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
        }
    }

    private void startOnEdt() {
        if (!requested || window != null || !shouldShowOverlay()) {
            return;
        }

        updateScreenBounds();
        DesktopAppState.preferences().hud().ensureLayoutInitialized(screenBounds);

        window = new JWindow();
        window.setType(Window.Type.UTILITY);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setAlwaysOnTop(true);
        window.setFocusable(false);
        window.setFocusableWindowState(false);
        window.setBounds(screenBounds);

        panel = new HudPanel();
        panel.setOpaque(false);
        MouseHandler mouseHandler = new MouseHandler();
        panel.addMouseListener(mouseHandler);
        panel.addMouseMotionListener(mouseHandler);
        window.setContentPane(panel);
        window.setVisible(shouldShowOverlay());
        if (window.isVisible()) {
            HudPlatformWindow.apply(window, true);
        }

        timer = new Timer(16, ignored -> tick());
        timer.setRepeats(true);
        timer.start();
    }

    private void tick() {
        updateScreenBounds();
        DesktopAppState.preferences().hud().ensureLayoutInitialized(screenBounds);

        boolean visible = shouldShowOverlay();
        if (window != null && window.isVisible() != visible) {
            window.setVisible(visible);
            if (visible) {
                HudPlatformWindow.apply(window, lastPassThrough);
            }
        }

        boolean passThrough = !DesktopAppState.preferences().hud().editMode().getValue();
        if (window != null && passThrough != lastPassThrough) {
            HudPlatformWindow.apply(window, passThrough);
            lastPassThrough = passThrough;
        }

        if (panel != null && visible) {
            panel.repaint();
        }
    }

    private boolean shouldShowOverlay() {
        MusicPreferences preferences = DesktopAppState.preferences();
        return smokeMode || preferences.hud().editMode().getValue()
                || renderers.stream().anyMatch(renderer -> renderer.layout(preferences).enabled().getValue());
    }

    private void updateScreenBounds() {
        Rectangle bounds = getDefaultScreenBounds();
        if (!bounds.equals(screenBounds)) {
            screenBounds = bounds;
            if (window != null) {
                window.setBounds(screenBounds);
            }
        }
    }

    private Rectangle getDefaultScreenBounds() {
        GraphicsConfiguration configuration = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();
        return configuration.getBounds();
    }

    private Rectangle2D rendererBounds(HudRenderer renderer) {
        return renderer.bounds(DesktopAppState.preferences(), screenBounds);
    }

    private boolean rendererVisibleForEditing(HudRenderer renderer) {
        MusicPreferences preferences = DesktopAppState.preferences();
        return smokeMode || preferences.hud().editMode().getValue()
                || renderer.layout(preferences).enabled().getValue();
    }

    private final class HudPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                HudRenderUtil.configure(g);
                MusicPreferences preferences = DesktopAppState.preferences();
                boolean editMode = preferences.hud().editMode().getValue();
                HudStateSnapshot snapshot = HudStateSnapshot.capture(smokeMode);

                for (HudRenderer renderer : renderers) {
                    if (!renderer.shouldRender(snapshot, preferences, editMode)) {
                        continue;
                    }
                    Rectangle2D bounds = rendererBounds(renderer);
                    HudRenderContext context = new HudRenderContext(preferences, snapshot, screenBounds, bounds, editMode);
                    renderer.render(g, context);
                }

                if (editMode) {
                    for (HudRenderer renderer : renderers) {
                        drawEditFrame(g, renderer, renderer.layout(preferences).enabled().getValue());
                    }
                }
            } finally {
                g.dispose();
            }
        }

        private void drawEditFrame(Graphics2D graphics, HudRenderer renderer, boolean enabled) {
            Rectangle2D bounds = rendererBounds(renderer);
            int frameColor = enabled ? 0xD0C30218 : 0x80777777;
            HudRenderUtil.strokeRound(graphics, bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(),
                    6, frameColor, 1.5f);
            HudRenderUtil.fillRound(graphics, bounds.getX(), bounds.getY() - 22, 112, 20, 6,
                    enabled ? 0xD0C30218 : 0x80606060);
            Font font = HudRenderUtil.fontBold(12);
            FontMetrics metrics = HudRenderUtil.metrics(graphics, font);
            HudRenderUtil.drawText(graphics, renderer.displayName(), font, bounds.getX() + 7,
                    bounds.getY() - 22 + 10 + metrics.getAscent() * .35, 0xFFFFFFFF);
            HudRenderUtil.fillRound(graphics, bounds.getMaxX() - 12, bounds.getMaxY() - 12, 12, 12, 4,
                    enabled ? 0xE0FFFFFF : 0xA0FFFFFF);
        }
    }

    private final class MouseHandler extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent event) {
            if (!DesktopAppState.preferences().hud().editMode().getValue()) {
                return;
            }

            double x = event.getX();
            double y = event.getY();
            List<HudRenderer> reversed = new ArrayList<>(renderers);
            Collections.reverse(reversed);
            for (HudRenderer renderer : reversed) {
                if (!rendererVisibleForEditing(renderer)) {
                    continue;
                }
                Rectangle2D bounds = rendererBounds(renderer);
                if (resizeHandle(bounds).contains(x, y)) {
                    dragState = DragState.resize(renderer, event, screenBounds);
                    panel.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
                    return;
                }
                if (bounds.contains(x, y)) {
                    dragState = DragState.move(renderer, event, screenBounds);
                    panel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (dragState == null) {
                return;
            }

            HudElementPreferences layout = dragState.renderer.layout(DesktopAppState.preferences());
            double mouseScreenX = screenBounds.getX() + event.getX();
            double mouseScreenY = screenBounds.getY() + event.getY();
            double dx = mouseScreenX - dragState.startMouseX;
            double dy = mouseScreenY - dragState.startMouseY;

            if (dragState.resize) {
                double width = Math.max(layout.minWidth(), dragState.startWidth + dx);
                double height = Math.max(layout.minHeight(), dragState.startHeight + dy);
                layout.setBounds(dragState.startX, dragState.startY, width, height);
            } else {
                layout.setBounds(dragState.startX + dx, dragState.startY + dy, dragState.startWidth, dragState.startHeight);
            }
            layout.clampTo(screenBounds);
            panel.repaint();
        }

        @Override
        public void mouseMoved(MouseEvent event) {
            if (!DesktopAppState.preferences().hud().editMode().getValue()) {
                return;
            }

            for (HudRenderer renderer : renderers) {
                Rectangle2D bounds = rendererBounds(renderer);
                if (resizeHandle(bounds).contains(event.getX(), event.getY())) {
                    panel.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
                    return;
                }
                if (bounds.contains(event.getX(), event.getY())) {
                    panel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
            }
            panel.setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            dragState = null;
            if (panel != null) {
                panel.setCursor(Cursor.getDefaultCursor());
            }
            DesktopAppState.savePreferences();
        }

        private Rectangle2D resizeHandle(Rectangle2D bounds) {
            return new Rectangle2D.Double(bounds.getMaxX() - 18, bounds.getMaxY() - 18, 18, 18);
        }
    }

    private static final class DragState {
        final HudRenderer renderer;
        final boolean resize;
        final double startMouseX;
        final double startMouseY;
        final double startX;
        final double startY;
        final double startWidth;
        final double startHeight;

        private DragState(HudRenderer renderer, boolean resize, MouseEvent event, Rectangle screenBounds) {
            this.renderer = renderer;
            this.resize = resize;
            this.startMouseX = screenBounds.getX() + event.getX();
            this.startMouseY = screenBounds.getY() + event.getY();
            HudElementPreferences layout = renderer.layout(DesktopAppState.preferences());
            this.startX = layout.x().getValue();
            this.startY = layout.y().getValue();
            this.startWidth = layout.width().getValue();
            this.startHeight = layout.height().getValue();
        }

        static DragState move(HudRenderer renderer, MouseEvent event, Rectangle screenBounds) {
            return new DragState(renderer, false, event, screenBounds);
        }

        static DragState resize(HudRenderer renderer, MouseEvent event, Rectangle screenBounds) {
            return new DragState(renderer, true, event, screenBounds);
        }
    }
}
