package tritium.desktop.hud;

import javax.swing.JWindow;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Locale;

/** A transparent HUD window that replaces its pixels only after a whole frame is ready. */
final class HudWindow extends JWindow {
    private final HudFrameBuffer frameBuffer = new HudFrameBuffer();
    private final boolean syncAfterPaint = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("mac");

    @Override
    public void paint(Graphics graphics) {
        // Window.paint clears the native surface before Swing paints its buffer
        // (JDK-8303950). Buffering just paintComponent cannot hide that blank frame.
        // printAll renders the entire root pane without Swing's shared double buffer.
        frameBuffer.paint((Graphics2D) graphics, getWidth(), getHeight(),
                frame -> getRootPane().printAll(frame));
        if (syncAfterPaint) {
            // Flush this window before switching the shared macOS render queue to
            // another HUD window (JDK-8378506 affects both Metal and OpenGL).
            getToolkit().sync();
        }
    }

    @Override
    public void update(Graphics graphics) {
        paint(graphics);
    }

    @Override
    public void dispose() {
        super.dispose();
        frameBuffer.release();
    }
}
