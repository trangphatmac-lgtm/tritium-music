package tritium.desktop;

public class NumberPreference extends PreferenceValue<Double> {
    private final double min;
    private final double max;
    private final double increment;

    public NumberPreference(String name, double defaultValue, double min, double max, double increment) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
        this.increment = increment;
    }

    @Override
    public void setValue(Double value) {
        double clamped = Math.max(min, Math.min(max, value));
        super.setValue(clamped);
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getIncrement() {
        return increment;
    }
}
