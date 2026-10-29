package tritium.minecraft.protocol;

import java.util.*;

/** Raven/OpenB4 option definitions shared by persistence, the desktop UI and the Java 8 agent. */
public final class CombatSettings {
    public Map<String, Double> numbers = new LinkedHashMap<String, Double>();
    public Map<String, Boolean> toggles = new LinkedHashMap<String, Boolean>();

    public double number(String key) { return numbers.get(key); }
    public boolean on(String key) { return Boolean.TRUE.equals(toggles.get(key)); }
    public void normalize(String module) {
        if (numbers == null) numbers = new LinkedHashMap<String, Double>();
        if (toggles == null) toggles = new LinkedHashMap<String, Boolean>();
        for (Option option : options(module)) {
            if (option.toggle) {
                if (toggles.get(option.key) == null) toggles.put(option.key, option.defaultValue != 0);
            } else {
                Double value = numbers.get(option.key);
                double v = value == null || Double.isNaN(value) || Double.isInfinite(value) ? option.defaultValue : value;
                v = option.min + Math.round((HudConfig.clamp(v, option.min, option.max) - option.min) / option.step) * option.step;
                numbers.put(option.key, HudConfig.clamp(v, option.min, option.max));
            }
        }
        if ("autoClicker".equals(module) && number("minCps") > number("maxCps")) {
            double min = number("minCps"); numbers.put("minCps", number("maxCps")); numbers.put("maxCps", min);
        }
    }
    public static boolean isCombat(String id) {
        return "aimAssist".equals(id) || "autoBlock".equals(id) || "autoClicker".equals(id);
    }
    public static List<Option> options(String id) {
        List<Option> result = DEFINITIONS.get(id);
        return result == null ? Collections.<Option>emptyList() : result;
    }
    public static final class Option {
        public final String key, label, suffix, special;
        public final boolean toggle;
        public final double defaultValue, min, max, step, specialValue;
        private Option(String key, String label, boolean value) {
            this(key, label, value ? 1 : 0, 0, 1, 1, "", Double.NaN, "", true);
        }
        private Option(String key, String label, double value, double min, double max, double step,
                       String suffix, double specialValue, String special) {
            this(key, label, value, min, max, step, suffix, specialValue, special, false);
        }
        private Option(String key, String label, double value, double min, double max, double step,
                       String suffix, double specialValue, String special, boolean toggle) {
            this.key = key; this.label = label; this.defaultValue = value; this.min = min; this.max = max;
            this.step = step; this.suffix = suffix; this.specialValue = specialValue; this.special = special; this.toggle = toggle;
        }
    }
    private static final Map<String, List<Option>> DEFINITIONS = new LinkedHashMap<String, List<Option>>();
    static {
        DEFINITIONS.put("aimAssist", Collections.unmodifiableList(Arrays.asList(
            new Option("speed", "Speed", 50.0, 2.0, 100.0, 2.0, "%", 100.0, "Lock"),
            new Option("fov", "FOV", 120.0, 20.0, 360.0, 5.0, "", Double.NaN, ""),
            new Option("range", "Range", 5.0, 3.0, 10.0, 0.1, "", Double.NaN, ""),
            new Option("rotateYaw", "Rotate yaw", true),
            new Option("rotatePitch", "Rotate pitch", true),
            new Option("aimInvis", "Aim invis", false),
            new Option("aimMobs", "Aim mobs", false),
            new Option("aimThroughBlocks", "Aim through blocks", true),
            new Option("clickAim", "Click aim", false),
            new Option("ignoreTeammates", "Ignore teammates", true),
            new Option("silentAim", "Silent aim", false),
            new Option("stopOnTarget", "Stop on target", false),
            new Option("stopWhenBreaking", "Stop when breaking", false),
            new Option("weaponOnly", "Weapon only", true))));
        DEFINITIONS.put("autoBlock", Collections.unmodifiableList(Arrays.asList(
            new Option("maxHurtTime", "Max hurt time", 100.0, 0.0, 500.0, 50.0, "ms", Double.NaN, ""),
            new Option("maxHoldDuration", "Max hold duration", 150.0, 50.0, 1000.0, 50.0, "ms", Double.NaN, ""),
            new Option("randomHoldDuration", "Random hold duration", 0.0, 0.0, 1000.0, 50.0, "ms", 0.0, "Disabled"),
            new Option("cooldown", "Cooldown", 250.0, 0.0, 1000.0, 50.0, "ms", Double.NaN, ""),
            new Option("range", "Range", 3.0, 1.0, 6.1, 0.1, "", 6.1, "Infinite"),
            new Option("disableBlockOutOfRange", "Disable block out of range", false),
            new Option("disableWhileChasing", "Disable while chasing", false),
            new Option("forceBlockAnimation", "Force block animation", false),
            new Option("ignoreMobs", "Ignore mobs", false),
            new Option("ignoreTeammates", "Ignore teammates", true),
            new Option("onlyWhileLeftClicking", "Only while left clicking", false),
            new Option("onlyWhileRightClicking", "Only while right clicking", false),
            new Option("onlyWhileTargeting", "Only while targeting", false),
            new Option("lagChance", "Lag chance", 0.0, 0.0, 100.0, 2.0, "%", 0.0, "Disabled"),
            new Option("lagDuration", "Lag duration", 200.0, 10.0, 800.0, 10.0, "ms", Double.NaN, ""),
            new Option("flushOnAttack", "Flush on attack", true),
            new Option("flushOnInteractItem", "Flush on interact item", true))));
        DEFINITIONS.put("autoClicker", Collections.unmodifiableList(Arrays.asList(
            new Option("minCps", "Min CPS", 11.0, 1.0, 20.0, 0.5, "", Double.NaN, ""),
            new Option("maxCps", "Max CPS", 13.0, 1.0, 20.0, 0.5, "", Double.NaN, ""),
            new Option("inventoryCps", "Inventory CPS", 0.0, 0.0, 20.0, 0.5, "", 0.0, "Disabled"),
            new Option("blockHit", "Block hit", 0.0, 0.0, 100.0, 2.0, "%", Double.NaN, ""),
            new Option("jitter", "Jitter", 0.0, 0.0, 3.0, 0.1, "", 0.0, "Disabled"),
            new Option("leftClick", "Left click", true),
            new Option("clickOnDamage", "Click on damage", true),
            new Option("breakBlocks", "Break blocks", true),
            new Option("weaponOnly", "Weapon only", false),
            new Option("rightClick", "Right click", false),
            new Option("blocksOnly", "Blocks only", true))));
    }
}
