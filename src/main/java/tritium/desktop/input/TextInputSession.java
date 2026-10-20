package tritium.desktop.input;

import tritium.rendering.entities.impl.TextField;

import java.awt.Rectangle;
import java.awt.font.TextHitInfo;
import java.text.AttributedCharacterIterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Transfers AWT input to the rendering thread. Each event belongs to one focus
 * session, so a late IME commit cannot end up in a different text field.
 */
final class TextInputSession {
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private volatile Target target;
    private long generation;

    record Target(TextField field, long generation) { }

    record Context(String text, int start, int end, Rectangle caret) {
        static final Context EMPTY = new Context("", 0, 0, new Rectangle());
    }

    private volatile Context context = Context.EMPTY;

    Target target() {
        return target;
    }

    Context context() {
        return context;
    }

    void focus(TextField field) {
        if (target != null && target.field() == field) return;
        if (target != null) target.field().setComposition("", 0);
        target = field == null ? null : new Target(field, ++generation);
        context = Context.EMPTY;
    }

    void updateContext(TextField field, Rectangle caret) {
        if (target == null || target.field() != field) return;
        // Password text must never be exposed to an input method.
        String text = field.isPassword ? "*".repeat(field.getText().length()) : field.getText();
        context = new Context(text, Math.min(field.getCursorPosition(), field.getSelectionEnd()),
                Math.max(field.getCursorPosition(), field.getSelectionEnd()), new Rectangle(caret));
    }

    void enqueue(Target owner, Consumer<TextField> action) {
        if (owner == null) return;
        pending.add(() -> {
            if (owner == target && owner.field().isFocused() && owner.field().isEnabled()) {
                action.accept(owner.field());
            }
        });
    }

    void inputMethodTextChanged(Target owner, AttributedCharacterIterator text, int committedCount,
                                TextHitInfo caret) {
        StringBuilder value = new StringBuilder();
        if (text != null) {
            for (char c = text.first(); c != AttributedCharacterIterator.DONE; c = text.next()) {
                value.append(c);
            }
        }
        String committed = value.substring(0, committedCount);
        String composed = value.substring(committedCount);
        int insertion = caret == null ? composed.length() : caret.getInsertionIndex();
        enqueue(owner, field -> {
            if (!committed.isEmpty()) field.writeText(committed);
            if (owner == target) field.setComposition(composed, insertion);
        });
    }

    void drain() {
        Runnable event;
        while ((event = pending.poll()) != null) event.run();
    }
}
