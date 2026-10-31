package tritium.minecraft.protocol;

import java.util.*;

/** Imported Raven-family settings: keys are the original names, including punctuation.
 * The persisted OpenB4 field name is retained for existing profiles. */
public final class OpenB4Settings {
    public Map<String, Double> numbers = new LinkedHashMap<String, Double>();
    public Map<String, Boolean> toggles = new LinkedHashMap<String, Boolean>();
    public Map<String, String> texts = new LinkedHashMap<String, String>();
    public double offsetX = 70, offsetY = 40;
    /** Tritium extension for Radar; kept separate from the unchanged source catalog. */
    public boolean offscreenRendering;

    public double number(String key) {
        Double value = numbers.get(key);
        if (value == null) throw new IllegalArgumentException("Unknown OpenB4 number: " + key);
        return value;
    }
    public boolean on(String key) { return Boolean.TRUE.equals(toggles.get(key)); }
    public String text(String key) { String value = texts.get(key); return value == null ? "" : value; }
    public void normalize(String id) {
        if (numbers == null) numbers = new LinkedHashMap<String, Double>();
        if (toggles == null) toggles = new LinkedHashMap<String, Boolean>();
        if (texts == null) texts = new LinkedHashMap<String, String>();
        for (Option option : options(id)) {
            if (option.toggle) {
                if (toggles.get(option.key) == null) toggles.put(option.key, option.defaultValue != 0);
            } else if (option.numeric) {
                Double saved = numbers.get(option.key);
                double value = saved == null || !finite(saved) ? option.defaultValue : saved;
                // SliderSetting.setValue rounds from zero; its constructor keeps
                // the supplied default verbatim (some defaults are off the min grid).
                if (value != option.defaultValue) {
                    double reciprocal = 1 / option.step;
                    value = Math.round(HudConfig.clamp(value, option.min, option.max) * reciprocal) / reciprocal;
                }
                numbers.put(option.key, HudConfig.clamp(value, option.min, option.max));
            }
        }
        if ("reach".equals(id)) order("Min", "Max");
        if ("backtrack".equals(id)) order("Distance (min)", "Distance (max)");
        offsetX = finite(offsetX) ? HudConfig.clamp(offsetX, -16384, 16384) : 70;
        offsetY = finite(offsetY) ? HudConfig.clamp(offsetY, -16384, 16384) : 40;
    }
    private void order(String min, String max) {
        if (number(min) > number(max)) { double value = number(min); numbers.put(min, number(max)); numbers.put(max, value); }
    }
    private static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }
    private static final Map<String, Definition> DEFINITIONS = definitionsById();
    private static Map<String, Definition> definitionsById() {
        Map<String, Definition> definitions = new LinkedHashMap<String, Definition>(OpenB4Catalog.create());
        definitions.putAll(RavenBsCatalog.create());
        return Collections.unmodifiableMap(definitions);
    }
    public static boolean contains(String id) { return DEFINITIONS.containsKey(id); }
    public static Collection<Definition> definitions() { return DEFINITIONS.values(); }
    public static Definition definition(String id) { return DEFINITIONS.get(id); }
    public static List<Option> options(String id) {
        Definition definition = definition(id);
        return definition == null ? Collections.<Option>emptyList() : definition.options;
    }

    public static final class Definition {
        public final String id, name;
        public final ModuleCategory category;
        public final List<Option> options;
        Definition(String id, String name, ModuleCategory category, List<Option> options) {
            this.id = id; this.name = name; this.category = category;
            this.options = Collections.unmodifiableList(options);
        }
    }
    public static final class Option {
        public final String key, kind, prefix, suffix;
        public final boolean toggle, numeric, action, description;
        public final double defaultValue, min, max, step;
        public final List<String> choices;
        public final Map<Double, String> labels;
        public final int compatMax;
        Option(String key, String kind, boolean value) {
            this.key = key; this.kind = kind; toggle = "toggle".equals(kind);
            action = "button".equals(kind); description = "desc".equals(kind); numeric = false;
            defaultValue = value ? 1 : 0; min = 0; max = 1; step = 1; prefix = suffix = "";
            choices = Collections.emptyList(); labels = Collections.emptyMap(); compatMax = -1;
        }
        Option(String key, double value, double min, double max, double step, String prefix, String suffix,
               String[] choices, double[] labelValues, String[] labelNames, boolean numeric, int compatMax) {
            this.key = key; kind = numeric ? "slider" : "mode"; toggle = action = description = false; this.numeric = true;
            defaultValue = value; this.min = min; this.max = max; this.step = step;
            this.prefix = prefix; this.suffix = suffix; this.compatMax = compatMax;
            this.choices = Collections.unmodifiableList(Arrays.asList(choices));
            Map<Double, String> entries = new LinkedHashMap<Double, String>();
            for (int i = 0; i < labelValues.length; i++) entries.put(labelValues[i], labelNames[i]);
            labels = Collections.unmodifiableMap(entries);
        }
        public String format(double value) {
            for (Map.Entry<Double, String> entry : labels.entrySet())
                if (Math.abs(value - entry.getKey()) < .000001) return entry.getValue().replaceAll("\u00a7.", "");
            if (!choices.isEmpty()) return choices.get(Math.max(0, Math.min(choices.size() - 1, (int) value)));
            String number = String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
            return prefix + number + suffix;
        }
    }
}
