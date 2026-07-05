package tritium.desktop;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class PreferenceValue<T> {
    private final String name;
    private T value;
    private Consumer<T> callback = ignored -> {
    };
    private Predicate<PreferenceValue<T>> hiddenPredicate = ignored -> false;

    public PreferenceValue(String name, T defaultValue) {
        this.name = name;
        this.value = defaultValue;
    }

    public String getName() {
        return name;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        if (Objects.equals(this.value, value)) {
            return;
        }

        this.value = value;
        callback.accept(value);
    }

    public void setValueCallback(Consumer<T> callback) {
        this.callback = callback == null ? ignored -> {
        } : callback;
    }

    public void setHiddenPredicate(Predicate<PreferenceValue<T>> hiddenPredicate) {
        this.hiddenPredicate = hiddenPredicate == null ? ignored -> false : hiddenPredicate;
    }

    public boolean isHidden() {
        return hiddenPredicate.test(this);
    }
}
