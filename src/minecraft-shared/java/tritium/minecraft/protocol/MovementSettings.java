package tritium.minecraft.protocol;

/** Sprint and Stasis options, including the text normally edited by Raven's sprint command. */
public final class MovementSettings {
    public boolean disableBackwards = true, displayText, rainbow;
    public String text = "[Sprint (Toggled)]";
    public double textX = 5, textY = 5;
    public double pulse = 60;

    public static boolean isMovement(String id) { return "sprint".equals(id) || "stasis".equals(id); }
    public void normalize() {
        if (text == null) text = "[Sprint (Toggled)]";
        text = text.replace('\n', ' ').replace('\r', ' ').replace('\u00a7', '&');
        if (text.length() > 256) text = text.substring(0, 256);
        textX = Math.rint(HudConfig.clamp(textX, 0, 16384));
        textY = Math.rint(HudConfig.clamp(textY, 0, 16384));
        pulse = Double.isNaN(pulse) || Double.isInfinite(pulse) ? 60 : Math.rint(HudConfig.clamp(pulse, 2, 85));
    }
    public String formattedText() { return text.replace('&', '\u00a7'); }
}
