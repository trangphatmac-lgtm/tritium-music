package tritium.desktop.input;

import org.junit.jupiter.api.Test;
import tritium.rendering.entities.impl.TextField;
import tritium.utils.KeyboardUtils;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.font.TextHitInfo;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextInputSessionTest {
    private final TextInputSession session = new TextInputSession();
    private final TextField field = new TextField(0, 0, 0, 200, 24);

    private void focus() {
        field.setFocused(true);
        session.focus(field);
    }

    private void input(String text, int committed) {
        session.inputMethodTextChanged(session.target(),
                text == null ? null : new AttributedString(text).getIterator(), committed, TextHitInfo.leading(0));
        session.drain();
    }

    @Test
    void compositionDoesNotMutateValueAndCommitIsDeliveredOnceAsAString() {
        focus();
        List<String> changes = new ArrayList<>();
        field.setCallback(changes::add);
        input("ni", 0);
        input("ni'hao", 0);
        assertEquals("", field.getText());
        assertEquals("ni'hao", field.getComposition());
        assertTrue(changes.isEmpty());

        input("你好", 2);
        assertEquals("你好", field.getText());
        assertEquals("", field.getComposition());
        assertEquals(List.of("你好"), changes);
    }

    @Test
    void partialCommitPreservesRemainingCompositionAndEventOrder() {
        focus();
        session.inputMethodTextChanged(session.target(), new AttributedString("你hao").getIterator(),
                1, TextHitInfo.leading(3));
        session.inputMethodTextChanged(session.target(), new AttributedString("好").getIterator(),
                1, null);
        List<String> submitted = new ArrayList<>();
        session.enqueue(session.target(), editor -> submitted.add(editor.getText()));
        session.drain();
        assertEquals("你好", field.getText());
        assertEquals(List.of("你好"), submitted);
    }

    @Test
    void cancellingCompositionDoesNotDeleteTheSelectedText() {
        field.setText("existing");
        field.selectAll();
        focus();
        input("拼音", 0);
        input(null, 0);
        assertEquals("existing", field.getText());
        assertEquals("existing", field.getSelectedText());
        assertEquals("", field.getComposition());
    }

    @Test
    void staleCommitsCannotCrossFocusSessionsEvenForTheSameField() {
        focus();
        TextInputSession.Target old = session.target();
        session.inputMethodTextChanged(old, new AttributedString("旧").getIterator(), 1, null);
        session.focus(null);
        session.focus(field);
        session.inputMethodTextChanged(old, new AttributedString("迟到").getIterator(), 2, null);
        input("新", 1);
        assertEquals("新", field.getText());
    }

    @Test
    void disabledOrUnfocusedEditorsRejectPendingCommits() {
        focus();
        session.inputMethodTextChanged(session.target(), new AttributedString("中文").getIterator(), 2, null);
        field.setEnabled(false);
        session.drain();
        assertEquals("", field.getText());
        assertFalse(field.isFocused());
    }

    @Test
    void fullFieldSelectionCanBeReplacedByImeCommit() {
        field.setMaxStringLength(2);
        field.setText("ab");
        field.selectAll();
        focus();
        input("中文", 2);
        assertEquals("中文", field.getText());
        assertEquals(2, field.getCursorPosition());
    }

    @Test
    void commitsRespectPredicateAndDoNotTruncateASurrogatePair() {
        focus();
        field.setPredicate(value -> !value.contains("禁"));
        input("禁止", 2);
        assertEquals("", field.getText());
        field.setMaxStringLength(3);
        input("中\uD840\uDC00", 3);
        assertEquals("中\uD840\uDC00", field.getText());
        field.setText("");
        field.setMaxStringLength(2);
        input("中\uD840\uDC00", 3);
        assertEquals("中", field.getText());
    }

    @Test
    void inputContextMasksPasswordsAndUsesAnImmutableCaretSnapshot() {
        field.setText("secret");
        field.isPassword = true;
        field.selectAll();
        focus();
        Rectangle caret = new Rectangle(100, 200, 0, 20);
        session.updateContext(field, caret);
        caret.x = 999;
        assertEquals("******", session.context().text());
        assertEquals(100, session.context().caret().x);
        assertEquals(0, session.context().start());
        assertEquals(6, session.context().end());
    }

    @Test
    void editingUsesEventModifiersEvenAfterKeysAreReleasedOnAwtThread() {
        field.setText("hello");
        focus();
        KeyboardUtils.withModifiers(InputEvent.META_DOWN_MASK, () ->
                field.textboxKeyTyped('\0', org.lwjgl.input.Keyboard.KEY_A));
        input("你好", 2);
        assertEquals("你好", field.getText());
        KeyboardUtils.withModifiers(InputEvent.SHIFT_DOWN_MASK, () ->
                field.textboxKeyTyped('\0', org.lwjgl.input.Keyboard.KEY_LEFT));
        assertEquals("好", field.getSelectedText());
    }
}
