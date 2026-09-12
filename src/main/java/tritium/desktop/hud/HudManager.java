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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class HudManager {
    private static final int EDIT_PADDING = 8;
    private static final int EDIT_TOP_PADDING = 28;

    private final List<HudRenderer> renderers = List.of(
            new MusicSpectrumHudRenderer(),
            new MusicLyricsHudRenderer(),
            new MusicInfoHudRenderer()
    );
    private final List<RendererWindow> rendererWindows = new ArrayList<>();

    private final AtomicReference<String> pendingToast = new AtomicReference<>();
    private final MusicToastHud toastHud = new MusicToastHud();

    public void pushMusicToast(String text) {
        if (text != null && !text.isBlank() && DesktopAppState.preferences().musicToast().getValue()) {
            pendingToast.set(text);
        }
    }

    private Timer timer;
    private Rectangle screenBounds = new Rectangle(0, 0, 1, 1);
    private HudStateSnapshot currentSnapshot = HudStateSnapshot.capture(false);
    private boolean smokeMode;
    private boolean requested;
    private boolean startPending;
    private long startAfterMillis;
    private long startupRefreshAtNanos;
    private boolean startupWindowsHidden;
    private DragState dragState;

    public void start(boolean smokeMode) {
        this.smokeMode = smokeMode;
        this.requested = true;
        if (!shouldRunHud()) {
            return;
        }
        scheduleStart(750L);
    }

    public void onPreferencesChanged() {
        if (!requested) {
            return;
        }
        if (timer == null && shouldRunHud()) {
            scheduleStart(250L);
        }
    }

    public void pump() {
        if (!startPending || timer != null || !shouldRunHud()) {
            return;
        }

        if (System.currentTimeMillis() < startAfterMillis) {
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("[HUD] Headless environment detected; HUD disabled.");
            startPending = false;
            return;
        }

        startPending = false;
        EventQueue.invokeLater(this::startOnEdt);
    }

    private void scheduleStart(long delayMillis) {
        startPending = true;
        startAfterMillis = System.currentTimeMillis() + delayMillis;
    }

    public void stop() {
        requested = false;
        startPending = false;
        pendingToast.set(null);
        if (timer == null && toastHud.isDisposed() && rendererWindows.stream().allMatch(RendererWindow::isDisposed)) {
            return;
        }

        Runnable task = () -> {
            startupRefreshAtNanos = 0;
            startupWindowsHidden = false;
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            for (RendererWindow rendererWindow : rendererWindows) {
                rendererWindow.dispose();
            }
            toastHud.dispose();
            dragState = null;
        };

        if (SwingUtilities.isEventDispatchThread()) {
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
        if (!requested || timer != null || !shouldRunHud()) {
            return;
        }

        updateScreenBounds();
        DesktopAppState.preferences().hud().ensureLayoutInitialized(screenBounds);
        ensureRendererWindows();
        currentSnapshot = HudStateSnapshot.capture(smokeMode);
        syncWindows();

        // Repeat the settings off/on workaround once native startup has settled.
        startupWindowsHidden = false;
        startupRefreshAtNanos = System.nanoTime() + 1_000_000_000L;

        timer = new Timer(16, ignored -> tick());
        timer.setRepeats(true);
        timer.start();
    }

    private void tick() {
        updateScreenBounds();
        DesktopAppState.preferences().hud().ensureLayoutInitialized(screenBounds);
        currentSnapshot = HudStateSnapshot.capture(smokeMode);
        toastHud.tick(pendingToast.getAndSet(null), DesktopAppState.preferences().musicToast().getValue(),
                System.nanoTime());
        if (refreshStartupWindows()) {
            return;
        }
        syncWindows();
        repaintWindows();
    }

    private boolean refreshStartupWindows() {
        if (startupRefreshAtNanos == 0) {
            return false;
        }
        if (System.nanoTime() - startupRefreshAtNanos < 0) {
            return startupWindowsHidden;
        }
        if (!startupWindowsHidden) {
            for (RendererWindow rendererWindow : rendererWindows) {
                rendererWindow.hide();
            }
            startupWindowsHidden = true;
            // Leave time for the native hide to finish before syncWindows shows
            // the currently enabled HUDs and reapplies their mouse flags.
            startupRefreshAtNanos = System.nanoTime() + 150_000_000L;
            return true;
        }
        startupWindowsHidden = false;
        startupRefreshAtNanos = 0;
        return false;
    }

    private void syncWindows() {
        MusicPreferences preferences = DesktopAppState.preferences();
        ensureRendererWindows();
        for (RendererWindow rendererWindow : rendererWindows) {
            rendererWindow.sync(preferences);
        }
    }

    private void repaintWindows() {
        for (RendererWindow rendererWindow : rendererWindows) {
            rendererWindow.repaint();
        }
    }

    private void ensureRendererWindows() {
        if (!rendererWindows.isEmpty()) {
            return;
        }
        for (HudRenderer renderer : renderers) {
            rendererWindows.add(new RendererWindow(renderer));
        }
    }

    private JWindow createHudWindow() {
        JWindow window = new HudWindow();
        window.setType(Window.Type.UTILITY);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setAlwaysOnTop(true);
        window.setFocusable(false);
        window.setFocusableWindowState(false);
        return window;
    }

    private boolean shouldRunHud() {
        MusicPreferences preferences = DesktopAppState.preferences();
        return smokeMode || preferences.musicToast().getValue() || preferences.hud().editMode().getValue()
                || renderers.stream().anyMatch(renderer -> renderer.layout(preferences).enabled().getValue());
    }

    private void updateScreenBounds() {
        Rectangle bounds = getDefaultScreenBounds();
        if (!bounds.equals(screenBounds)) {
            screenBounds = bounds;
            for (RendererWindow rendererWindow : rendererWindows) {
                rendererWindow.invalidateBounds();
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

    private Rectangle toWindowBounds(Rectangle2D localBounds, boolean editMode) {
        int extraWidth = editMode ? EDIT_PADDING * 2 : 0;
        int extraHeight = editMode ? EDIT_TOP_PADDING + EDIT_PADDING : 0;
        return new Rectangle(
                (int) Math.round(screenBounds.getX() + localBounds.getX() - (editMode ? EDIT_PADDING : 0)),
                (int) Math.round(screenBounds.getY() + localBounds.getY() - (editMode ? EDIT_TOP_PADDING : 0)),
                Math.max(1, (int) Math.ceil(localBounds.getWidth()) + extraWidth),
                Math.max(1, (int) Math.ceil(localBounds.getHeight()) + extraHeight)
        );
    }

    private final class RendererWindow {
        private final HudRenderer renderer;
        private final RendererPanel panel;
        private JWindow window;
        private Rectangle lastBounds = new Rectangle();
        private Boolean lastPassThrough;

        private RendererWindow(HudRenderer renderer) {
            this.renderer = renderer;
            this.panel = new RendererPanel(this);
        }

        private void sync(MusicPreferences preferences) {
            boolean editMode = preferences.hud().editMode().getValue();
            boolean visible = smokeMode || editMode || renderer.layout(preferences).enabled().getValue();
            if (!visible) {
                hide();
                return;
            }

            ensureWindow();
            Rectangle bounds = toWindowBounds(rendererBounds(renderer), editMode);
            if (!bounds.equals(lastBounds)) {
                window.setBounds(bounds);
                lastBounds = bounds;
            }
            if (!window.isVisible()) {
                window.setVisible(true);
                lastPassThrough = null;
            }

            boolean passThrough = !editMode;
            // AWT can overwrite native mouse flags after the initial show. A successful
            // earlier write is not proof that the window is still passing clicks through.
            if (!Boolean.valueOf(passThrough).equals(lastPassThrough)
                    || !HudPlatformWindow.isPassThroughApplied(window, passThrough)) {
                if (HudPlatformWindow.apply(window, passThrough)) {
                    lastPassThrough = passThrough;
                }
            }
        }

        private void ensureWindow() {
            if (window != null) {
                return;
            }

            window = createHudWindow();
            panel.setOpaque(false);
            window.setContentPane(panel);
        }

        private void repaint() {
            if (window != null && window.isVisible()) {
                window.repaint();
            }
        }

        private void hide() {
            if (window != null) {
                window.setVisible(false);
            }
            lastPassThrough = null;
        }

        private void dispose() {
            if (window != null) {
                window.setVisible(false);
                window.dispose();
                window = null;
            }
            lastBounds = new Rectangle();
            lastPassThrough = null;
        }

        private void invalidateBounds() {
            lastBounds = new Rectangle();
        }

        private boolean isDisposed() {
            return window == null;
        }
    }

    private final class RendererPanel extends JPanel {
        private final RendererWindow owner;

        private RendererPanel(RendererWindow owner) {
            this.owner = owner;
            MouseHandler mouseHandler = new MouseHandler(owner);
            addMouseListener(mouseHandler);
            addMouseMotionListener(mouseHandler);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                HudRenderUtil.configure(g);
                MusicPreferences preferences = DesktopAppState.preferences();
                boolean editMode = preferences.hud().editMode().getValue();
                HudStateSnapshot snapshot = currentSnapshot;
                HudRenderer renderer = owner.renderer;
                if (!renderer.shouldRender(snapshot, preferences, editMode)) {
                    return;
                }

                Rectangle2D bounds = rendererPanelBounds(editMode, getWidth(), getHeight());
                HudRenderContext context = new HudRenderContext(preferences, snapshot, screenBounds, bounds, editMode);
                renderer.render(g, context);
                if (editMode) {
                    drawEditFrame(g, renderer, bounds, renderer.layout(preferences).enabled().getValue());
                }
            } finally {
                g.dispose();
            }
        }

        private Rectangle2D rendererPanelBounds(boolean editMode, int width, int height) {
            if (!editMode) {
                return new Rectangle2D.Double(0, 0, width, height);
            }
            return new Rectangle2D.Double(EDIT_PADDING, EDIT_TOP_PADDING,
                    Math.max(1, width - EDIT_PADDING * 2.0),
                    Math.max(1, height - EDIT_TOP_PADDING - EDIT_PADDING));
        }

        private void drawEditFrame(Graphics2D graphics, HudRenderer renderer, Rectangle2D bounds, boolean enabled) {
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
        private final RendererWindow owner;

        private MouseHandler(RendererWindow owner) {
            this.owner = owner;
        }

        @Override
        public void mousePressed(MouseEvent event) {
            if (!DesktopAppState.preferences().hud().editMode().getValue()) {
                return;
            }

            Rectangle2D bounds = owner.panel.rendererPanelBounds(true, owner.panel.getWidth(), owner.panel.getHeight());
            if (resizeHandle(bounds).contains(event.getX(), event.getY())) {
                dragState = DragState.resize(owner.renderer, event);
                owner.panel.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
                return;
            }
            if (bounds.contains(event.getX(), event.getY()) || titleBounds(bounds).contains(event.getX(), event.getY())) {
                dragState = DragState.move(owner.renderer, event);
                owner.panel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (dragState == null || dragState.renderer != owner.renderer) {
                return;
            }

            HudElementPreferences layout = dragState.renderer.layout(DesktopAppState.preferences());
            double dx = event.getXOnScreen() - dragState.startMouseX;
            double dy = event.getYOnScreen() - dragState.startMouseY;

            if (dragState.resize) {
                double width = Math.max(layout.minWidth(), dragState.startWidth + dx);
                double height = Math.max(layout.minHeight(), dragState.startHeight + dy);
                layout.setBounds(dragState.startX, dragState.startY, width, height);
            } else {
                layout.setBounds(dragState.startX + dx, dragState.startY + dy, dragState.startWidth, dragState.startHeight);
            }
            layout.clampTo(screenBounds);
            owner.invalidateBounds();
            owner.panel.repaint();
        }

        @Override
        public void mouseMoved(MouseEvent event) {
            if (!DesktopAppState.preferences().hud().editMode().getValue()) {
                return;
            }

            Rectangle2D bounds = owner.panel.rendererPanelBounds(true, owner.panel.getWidth(), owner.panel.getHeight());
            if (resizeHandle(bounds).contains(event.getX(), event.getY())) {
                owner.panel.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
                return;
            }
            if (bounds.contains(event.getX(), event.getY()) || titleBounds(bounds).contains(event.getX(), event.getY())) {
                owner.panel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                return;
            }
            owner.panel.setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            dragState = null;
            owner.panel.setCursor(Cursor.getDefaultCursor());
            DesktopAppState.savePreferences();
        }

        private Rectangle2D resizeHandle(Rectangle2D bounds) {
            return new Rectangle2D.Double(bounds.getMaxX() - 18, bounds.getMaxY() - 18, 18, 18);
        }

        private Rectangle2D titleBounds(Rectangle2D bounds) {
            return new Rectangle2D.Double(bounds.getX(), Math.max(0, bounds.getY() - 24), 120, 24);
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

        private DragState(HudRenderer renderer, boolean resize, MouseEvent event) {
            this.renderer = renderer;
            this.resize = resize;
            this.startMouseX = event.getXOnScreen();
            this.startMouseY = event.getYOnScreen();
            HudElementPreferences layout = renderer.layout(DesktopAppState.preferences());
            this.startX = layout.x().getValue();
            this.startY = layout.y().getValue();
            this.startWidth = layout.width().getValue();
            this.startHeight = layout.height().getValue();
        }

        static DragState move(HudRenderer renderer, MouseEvent event) {
            return new DragState(renderer, false, event);
        }

        static DragState resize(HudRenderer renderer, MouseEvent event) {
            return new DragState(renderer, true, event);
        }
    }
}
