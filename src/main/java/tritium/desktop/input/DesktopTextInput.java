package tritium.desktop.input;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import tritium.desktop.DesktopAppState;
import tritium.rendering.AllowedCharacters;
import tritium.rendering.entities.impl.TextField;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.utils.KeyboardUtils;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;

/**
 * LWJGL 2's native windows are not input-method clients. A transparent AWT focus
 * window supplies that client without replacing the OpenGL drawable. Only AWT's
 * committed text reaches the editor; composition stays separate from its value.
 */
public final class DesktopTextInput {
    private static final TextInputSession SESSION = new TextInputSession();
    private static volatile boolean installed;
    private static InputClient client;
    private static JDialog window;
    private static TextField renderedField;

    private DesktopTextInput() { }

    public static void install() {
        if (GraphicsEnvironment.isHeadless()) return;
        installed = true;
    }

    public static boolean ownsKeyboard() {
        return installed && SESSION.target() != null;
    }

    public static void focus(TextField field) {
        if (!installed) return;
        TextInputSession.Target previous = SESSION.target();
        SESSION.focus(field);
        if (previous != null && previous.field() != field) previous.field().setFocused(false);
        TextInputSession.Target owner = SESSION.target();
        SwingUtilities.invokeLater(() -> {
            if (SESSION.target() != owner || !installed) return;
            if (owner == null) {
                deactivate();
                return;
            }
            ensureWindow();
            if (client.owner != owner) {
                // endComposition can synchronously commit. Detach its old owner first.
                client.owner = null;
                client.getInputContext().endComposition();
                client.composing = false;
                client.owner = owner;
            }
            Rectangle caret = SESSION.context().caret();
            window.setLocation(caret.x, caret.y);
            window.setVisible(true);
            window.toFront();
            client.requestFocusInWindow();
        });
    }

    public static void unfocus(TextField field) {
        if (SESSION.target() != null && SESSION.target().field() == field) focus(null);
    }

    public static void clearFocus() {
        if (SESSION.target() != null) SESSION.target().field().setFocused(false);
    }

    public static void pump() {
        SESSION.drain();
    }

    public static void beginFrame() {
        renderedField = null;
    }

    public static void update(TextField field, double caretX, double caretY, double caretHeight) {
        if (!installed || !field.isFocused()) return;
        renderedField = field;
        if (SESSION.target() == null || SESSION.target().field() != field) focus(field);
        double scale = RenderSystem.getUiScaleFactor() / RenderSystem.getPixelScaleFactor();
        Rectangle caret = new Rectangle(
                Display.getX() + (int) Math.round(caretX * scale),
                Display.getY() + (int) Math.round(caretY * scale),
                0, Math.max(1, (int) Math.ceil(caretHeight * scale)));
        SESSION.updateContext(field, caret);
    }

    public static void endFrame() {
        if (SESSION.target() != null && SESSION.target().field() != renderedField) clearFocus();
    }

    public static void close() {
        clearFocus();
        installed = false;
        SwingUtilities.invokeLater(() -> {
            deactivate();
            if (window != null) window.dispose();
            window = null;
            client = null;
        });
    }

    private static void deactivate() {
        if (client != null) {
            client.owner = null;
            client.getInputContext().endComposition();
            client.composing = false;
        }
        if (window != null) window.setVisible(false);
    }

    private static void ensureWindow() {
        if (window != null) return;
        window = new JDialog((Frame) null, false);
        window.setUndecorated(true);
        window.setType(Window.Type.UTILITY);
        window.setFocusableWindowState(true);
        window.setAutoRequestFocus(true);
        GraphicsDevice device = window.getGraphicsConfiguration().getDevice();
        if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            window.setBackground(new Color(0, 0, 0, 0));
        }
        client = new InputClient(SESSION);
        window.setContentPane(client);
        window.setSize(1, 1);
        InputClient focusClient = client;
        JDialog focusWindow = window;
        window.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                TextInputSession.Target owner = focusClient.owner;
                SESSION.enqueue(owner, field -> {
                    if (!focusWindow.isFocused()) field.setFocused(false);
                });
            }
        });
    }

    static final class InputClient extends JComponent implements InputMethodRequests, InputMethodListener {
        private final TextInputSession session;
        TextInputSession.Target owner;
        private boolean composing;

        InputClient(TextInputSession session) {
            this.session = session;
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);
            setOpaque(false);
            enableInputMethods(true);
            enableEvents(AWTEvent.KEY_EVENT_MASK);
            addInputMethodListener(this);
        }

        @Override
        public InputMethodRequests getInputMethodRequests() {
            return this;
        }

        @Override
        protected void processKeyEvent(KeyEvent event) {
            // InputContext has already consumed keys used for composition/selection.
            if (event.isConsumed() || owner == null) return;
            if (event.getID() == KeyEvent.KEY_TYPED) {
                char character = event.getKeyChar();
                if (!composing && (!event.isControlDown() || event.isAltGraphDown()) && !event.isMetaDown()
                        && AllowedCharacters.isAllowedCharacter(character)
                        && character != KeyEvent.CHAR_UNDEFINED) {
                    session.enqueue(owner, field -> field.writeText(String.valueOf(character)));
                }
            } else if (event.getID() == KeyEvent.KEY_PRESSED && !composing) {
                int key = keyCode(event);
                int modifiers = event.getModifiersEx();
                if (key != Keyboard.KEY_NONE) {
                    session.enqueue(owner, field -> KeyboardUtils.withModifiers(modifiers, () -> {
                        if (DesktopAppState.screenManager().getCurrentScreen() != null) {
                            DesktopAppState.screenManager().getCurrentScreen().keyTyped('\0', key);
                        }
                    }));
                }
            }
            event.consume();
        }

        @Override
        public void inputMethodTextChanged(InputMethodEvent event) {
            AttributedCharacterIterator text = event.getText();
            composing = text != null
                    && text.getEndIndex() - text.getBeginIndex() > event.getCommittedCharacterCount();
            session.inputMethodTextChanged(owner, text, event.getCommittedCharacterCount(), event.getCaret());
            event.consume();
        }

        @Override
        public void caretPositionChanged(InputMethodEvent event) {
            if (event.getCaret() != null) {
                int caret = event.getCaret().getInsertionIndex();
                session.enqueue(owner, field -> field.setComposition(field.getComposition(), caret));
            }
            event.consume();
        }

        @Override
        public Rectangle getTextLocation(TextHitInfo offset) {
            return new Rectangle(session.context().caret());
        }

        @Override
        public TextHitInfo getLocationOffset(int x, int y) {
            return null;
        }

        @Override
        public int getInsertPositionOffset() {
            return session.context().start();
        }

        @Override
        public AttributedCharacterIterator getCommittedText(int begin, int end,
                                                            AttributedCharacterIterator.Attribute[] attributes) {
            return new AttributedString(session.context().text().substring(begin, end)).getIterator();
        }

        @Override
        public int getCommittedTextLength() {
            return session.context().text().length();
        }

        @Override
        public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes) {
            return null;
        }

        @Override
        public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes) {
            TextInputSession.Context context = session.context();
            return new AttributedString(context.text().substring(context.start(), context.end())).getIterator();
        }
    }

    static int keyCode(KeyEvent event) {
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_BACK_SPACE -> Keyboard.KEY_BACK;
            case KeyEvent.VK_DELETE -> Keyboard.KEY_DELETE;
            case KeyEvent.VK_LEFT -> Keyboard.KEY_LEFT;
            case KeyEvent.VK_RIGHT -> Keyboard.KEY_RIGHT;
            case KeyEvent.VK_HOME -> Keyboard.KEY_HOME;
            case KeyEvent.VK_END -> Keyboard.KEY_END;
            case KeyEvent.VK_ESCAPE -> Keyboard.KEY_ESCAPE;
            case KeyEvent.VK_ENTER -> event.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD
                    ? Keyboard.KEY_NUMPADENTER : Keyboard.KEY_RETURN;
            case KeyEvent.VK_A -> event.isControlDown() || event.isMetaDown() ? Keyboard.KEY_A : Keyboard.KEY_NONE;
            case KeyEvent.VK_C -> event.isControlDown() || event.isMetaDown() ? Keyboard.KEY_C : Keyboard.KEY_NONE;
            case KeyEvent.VK_V -> event.isControlDown() || event.isMetaDown() ? Keyboard.KEY_V : Keyboard.KEY_NONE;
            case KeyEvent.VK_X -> event.isControlDown() || event.isMetaDown() ? Keyboard.KEY_X : Keyboard.KEY_NONE;
            case KeyEvent.VK_F -> event.isControlDown() || event.isMetaDown() ? Keyboard.KEY_F : Keyboard.KEY_NONE;
            case KeyEvent.VK_G -> event.isControlDown() || event.isMetaDown() ? Keyboard.KEY_G : Keyboard.KEY_NONE;
            default -> Keyboard.KEY_NONE;
        };
    }
}
