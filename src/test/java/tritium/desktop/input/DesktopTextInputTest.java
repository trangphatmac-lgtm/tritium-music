package tritium.desktop.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;
import tritium.desktop.DesktopAppState;
import tritium.desktop.DesktopScreen;
import tritium.rendering.entities.impl.TextField;

import java.awt.event.InputMethodEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextHitInfo;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopTextInputTest {
    private final TextInputSession session = new TextInputSession();
    private final TextField field = new TextField(0, 0, 0, 100, 20);
    private final DesktopTextInput.InputClient client = new DesktopTextInput.InputClient(session);
    private final List<String> submissions = new ArrayList<>();
    private DesktopScreen previousScreen;

    @BeforeEach
    void setup() {
        previousScreen = DesktopAppState.screenManager().getCurrentScreen();
        DesktopAppState.screenManager().displayScreen(new DesktopScreen() {
            @Override
            public void drawScreen(int mouseX, int mouseY) { }

            @Override
            public void keyTyped(char character, int keyCode) {
                if (keyCode == Keyboard.KEY_RETURN) submissions.add(field.getText());
                else field.textboxKeyTyped(character, keyCode);
            }
        });
        field.setFocused(true);
        session.focus(field);
        client.owner = session.target();
    }

    @AfterEach
    void restore() {
        DesktopAppState.screenManager().displayScreen(previousScreen);
    }

    private void key(int id, int code, char character) {
        client.processKeyEvent(new KeyEvent(client, id, 1, 0, code, character));
    }

    private void composition(String value, int committed) {
        client.inputMethodTextChanged(new InputMethodEvent(client,
                InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, new AttributedString(value).getIterator(),
                committed, TextHitInfo.leading(value.length() - committed), null));
    }

    @Test
    void latinKeysAreInsertedOnlyByTypedEvents() {
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a');
        key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'a');
        key(KeyEvent.KEY_RELEASED, KeyEvent.VK_A, 'a');
        session.drain();
        assertEquals("a", field.getText());
    }

    @Test
    void altGraphCanTypePrintableCharactersEvenWhenControlIsAlsoSet() {
        client.processKeyEvent(new KeyEvent(client, KeyEvent.KEY_TYPED, 1,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK,
                KeyEvent.VK_UNDEFINED, '€'));
        session.drain();
        assertEquals("€", field.getText());
    }

    @Test
    void candidateKeysCannotSubmitOrDeleteCommittedText() {
        field.setText("已有");
        composition("ni'hao", 0);
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n');
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_BACK_SPACE, '\b');
        key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ' ');
        session.drain();
        assertEquals("已有", field.getText());
        assertTrue(submissions.isEmpty());
        composition("你好", 2);
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n');
        session.drain();
        assertEquals(List.of("已有你好"), submissions);
    }

    @Test
    void inputContextConsumedEnterCannotTriggerSearchAfterCommit() {
        composition("你好", 2);
        KeyEvent consumed = new KeyEvent(client, KeyEvent.KEY_PRESSED, 1, 0, KeyEvent.VK_ENTER, '\n');
        consumed.consume();
        client.processKeyEvent(consumed);
        session.drain();
        assertEquals("你好", field.getText());
        assertTrue(submissions.isEmpty());
    }

    @Test
    void committedEmojiCanBeNavigatedAndDeletedWithoutBreakingUtf16() {
        composition("中🤔文", 4);
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED);
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED);
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED);
        session.drain();
        assertEquals("中文", field.getText());
        assertEquals(1, field.getCursorPosition());
        composition("🤔", 2);
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_BACK_SPACE, '\b');
        session.drain();
        assertEquals("中文", field.getText());
        assertEquals(1, field.getCursorPosition());
    }
}
