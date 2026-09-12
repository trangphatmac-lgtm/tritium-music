package tritium.rendering;

import lombok.experimental.UtilityClass;
import tritium.desktop.DesktopAppState;
import tritium.utils.math.Mth;

/** Song notifications are displayed in a native desktop HUD, independent of OpenGL. */
@UtilityClass
public class MusicToast {
    public void pushMusicToast(String name) {
        DesktopAppState.hudManager().pushMusicToast(name);
    }

    private final DyeColor[] MUSIC_NOTE_COLORS = new DyeColor[]{DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.LIGHT_BLUE, DyeColor.BLUE, DyeColor.CYAN, DyeColor.GREEN, DyeColor.LIME, DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.PINK, DyeColor.RED, DyeColor.MAGENTA};

    public int getLerpedColor(float tick) {
        int colorDuration = 30;
        int tickCount = Mth.floor(tick);
        int value = tickCount / colorDuration;
        int colorCount = MUSIC_NOTE_COLORS.length;
        int c1 = value % colorCount;
        int c2 = (value + 1) % colorCount;
        float subStep = ((float)(tickCount % colorDuration) + Mth.frac(tick)) / (float)colorDuration;
        int color1 = getModifiedColor(MUSIC_NOTE_COLORS[c1], 1.25f);
        int color2 = getModifiedColor(MUSIC_NOTE_COLORS[c2], 1.25f);
        return RGBA.srgbLerp(subStep, color1, color2);
    }

    private int getModifiedColor(DyeColor color, float brightness) {
        if (color == DyeColor.WHITE) {
            return -1644826;
        }
        int src = color.getTextureDiffuseColor();
        return RGBA.color(Mth.floor((float) RGBA.red(src) * brightness), Mth.floor((float) RGBA.green(src) * brightness), Mth.floor((float) RGBA.blue(src) * brightness), 255);
    }

    public enum DyeColor
    {
        WHITE(0, "white", 0xF9FFFE, 0xF0F0F0, 0xFFFFFF),
        ORANGE(1, "orange", 16351261, 15435844, 16738335),
        MAGENTA(2, "magenta", 13061821, 12801229, 0xFF00FF),
        LIGHT_BLUE(3, "light_blue", 3847130, 6719955, 10141901),
        YELLOW(4, "yellow", 16701501, 14602026, 0xFFFF00),
        LIME(5, "lime", 8439583, 4312372, 0xBFFF00),
        PINK(6, "pink", 15961002, 14188952, 16738740),
        GRAY(7, "gray", 4673362, 0x434343, 0x808080),
        LIGHT_GRAY(8, "light_gray", 0x9D9D97, 0xABABAB, 0xD3D3D3),
        CYAN(9, "cyan", 1481884, 2651799, 65535),
        PURPLE(10, "purple", 8991416, 8073150, 10494192),
        BLUE(11, "blue", 3949738,  2437522, 255),
        BROWN(12, "brown", 8606770, 5320730, 9127187),
        GREEN(13, "green", 6192150, 3887386, 65280),
        RED(14, "red", 11546150, 11743532, 0xFF0000),
        BLACK(15, "black", 0x1D1D21, 0x1E1B1B, 0);

        private final int id;
        private final String name;
        private final int textureDiffuseColor;
        private final int fireworkColor;
        private final int textColor;

        DyeColor(int id, String name, int textureDiffuseColor, int fireworkColor, int textColor) {
            this.id = id;
            this.name = name;
            this.textColor = RGBA.opaque(textColor);
            this.textureDiffuseColor = RGBA.opaque(textureDiffuseColor);
            this.fireworkColor = fireworkColor;
        }

        public int getId() {
            return this.id;
        }

        public String getName() {
            return this.name;
        }

        public int getTextureDiffuseColor() {
            return this.textureDiffuseColor;
        }

        public int getFireworkColor() {
            return this.fireworkColor;
        }

        public int getTextColor() {
            return this.textColor;
        }


        public String toString() {
            return this.name;
        }

        public String getSerializedName() {
            return this.name;
        }

    }

}
