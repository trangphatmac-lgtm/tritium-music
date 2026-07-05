package tritium.desktop;

import java.util.Arrays;
import java.util.List;

public class ModePreference extends PreferenceValue<String> {
    private final List<String> modes;

    public ModePreference(String name, String defaultValue, String[] modes) {
        super(name, defaultValue);
        this.modes = Arrays.asList(modes);
    }

    @Override
    public void setValue(String value) {
        if (!modes.contains(value)) {
            throw new IllegalArgumentException("Unsupported mode for " + getName() + ": " + value);
        }
        super.setValue(value);
    }

    public List<String> getModes() {
        return modes;
    }
}
