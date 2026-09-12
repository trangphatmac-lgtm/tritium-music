package tritium.desktop.hud;

import tritium.rendering.MusicToast;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** A transient, non-activating desktop window. All access is on the Swing EDT. */
final class MusicToastHud {
    private static final long ANIMATION_NANOS = 750_000_000L;
    private static final long HOLD_NANOS = 5_000_000_000L;
    private static final int HEIGHT = 48;
    private static final Font TEXT_FONT = HudRenderUtil.fontBold(14);
    private final JPanel panel = new JPanel() {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                paintToast(g);
            } finally {
                g.dispose();
            }
        }
    };
    private HudWindow window;
    private BufferedImage background;
    private BufferedImage notes;
    private String text;
    private long startedAt;
    private long elapsed;

    void tick(String message, boolean enabled, long now) {
        if (!enabled) {
            hide();
            return;
        }
        if (message != null) {
            text = message;
            startedAt = now;
        }
        if (text == null) {
            return;
        }
        elapsed = Math.max(0, now - startedAt);
        if (elapsed >= ANIMATION_NANOS * 2 + HOLD_NANOS) {
            hide();
            return;
        }
        ensureWindow();
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int available = Math.max(1, screen.width - insets.left - insets.right - 24);
        int width = Math.min(available, Math.max(240, panel.getFontMetrics(TEXT_FONT).stringWidth(text) + 64));
        Rectangle bounds = new Rectangle(screen.x + insets.left + 12, screen.y + insets.top + 12, width, HEIGHT);
        if (!bounds.equals(window.getBounds())) {
            window.setBounds(bounds);
        }
        if (!window.isVisible()) {
            window.setVisible(true);
            HudPlatformWindow.apply(window, true);
        } else if (!HudPlatformWindow.isPassThroughApplied(window, true)) {
            HudPlatformWindow.apply(window, true);
        }
        window.repaint();
    }

    private void ensureWindow() {
        if (window != null) {
            return;
        }
        background = readImage("now_playing.png");
        notes = readImage("music_notes.png");
        window = new HudWindow();
        window.setType(Window.Type.UTILITY);
        window.setName("Tritium Music Toast HUD");
        window.setBackground(new Color(0, 0, 0, 0));
        window.setAlwaysOnTop(true);
        window.setFocusable(false);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        panel.setOpaque(false);
        window.setContentPane(panel);
    }

    private static BufferedImage readImage(String name) {
        try {
            return ImageIO.read(MusicToastHud.class.getResource("/tritium/textures/hud/" + name));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[HUD] Cannot load " + name + ": " + e.getMessage());
            return null;
        }
    }

    private void paintToast(Graphics2D g) {
        if (text == null) {
            return;
        }
        HudRenderUtil.configure(g);
        int width = panel.getWidth();
        double progress = Math.min(1, elapsed / (double) ANIMATION_NANOS);
        double visible = 1 - Math.pow(1 - progress, 4);
        if (elapsed > ANIMATION_NANOS + HOLD_NANOS) {
            progress = Math.min(1, (elapsed - ANIMATION_NANOS - HOLD_NANOS) / (double) ANIMATION_NANOS);
            visible = Math.pow(1 - progress, 4);
        }
        g.translate(-(1 - visible) * width, 0);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (background != null) {
            int edge = 8;
            int sourceEdge = 5;
            int sw = background.getWidth();
            int sh = background.getHeight();
            g.drawImage(background, 0, 0, edge, HEIGHT, 0, 0, sourceEdge, sh, null);
            g.drawImage(background, edge, 0, width - edge, HEIGHT, sourceEdge, 0, sw - sourceEdge, sh, null);
            g.drawImage(background, width - edge, 0, width, HEIGHT, sw - sourceEdge, 0, sw, sh, null);
        } else {
            HudRenderUtil.fillRound(g, 0, 0, width, HEIGHT, 4, 0xF0202020);
        }
        if (notes != null) {
            int size = notes.getWidth();
            int frame = (int) ((elapsed / 100_000_000L) % (notes.getHeight() / size));
            BufferedImage tinted = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D icon = tinted.createGraphics();
            try {
                icon.drawImage(notes, 0, 0, size, size, 0, frame * size, size, (frame + 1) * size, null);
                icon.setComposite(AlphaComposite.SrcIn);
                icon.setColor(new Color(MusicToast.getLerpedColor(elapsed / 25_000_000f), true));
                icon.fillRect(0, 0, size, size);
            } finally {
                icon.dispose();
            }
            g.drawImage(tinted, 12, 8, 32, 32, null);
        }
        g.setFont(TEXT_FONT);
        g.setColor(Color.WHITE);
        FontMetrics metrics = g.getFontMetrics();
        String label = fitText(text, metrics, Math.max(0, width - 64));
        g.drawString(label, 52, (HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent());
    }

    static String fitText(String text, FontMetrics metrics, int available) {
        if (metrics.stringWidth(text) <= available) {
            return text;
        }
        if (metrics.stringWidth("…") > available) {
            return "";
        }
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > available) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end) + "…";
    }

    private void hide() {
        text = null;
        if (window != null && window.isVisible()) {
            window.setVisible(false);
        }
    }

    void dispose() {
        hide();
        if (window != null) {
            window.dispose();
            window = null;
        }
    }

    boolean isDisposed() {
        return window == null;
    }
}
