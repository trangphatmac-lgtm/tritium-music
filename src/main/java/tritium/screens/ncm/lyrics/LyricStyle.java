package tritium.screens.ncm.lyrics;

import java.util.Arrays;

/** Saved identifiers are independent of the translated labels. */
public enum LyricStyle {
    Normal, Hanabi, Lumierei, Tempera, Diorama, Sonnet, Pendolo, Fume, Monet, Claddagh, Partita, Classic;

    public static String[] ids() { return Arrays.stream(values()).map(Enum::name).toArray(String[]::new); }
    public static LyricStyle from(String value) {
        try { return valueOf(value); } catch (IllegalArgumentException | NullPointerException ignored) { return Normal; }
    }
    public boolean usesPlayerControls() { return this != Normal; }

    LyricScene createScene() {
        return switch (this) {
            case Hanabi -> new HanabiScene();
            case Lumierei -> new LumiereScene();
            case Tempera -> new TemperaScene();
            case Diorama -> new DioramaScene();
            case Sonnet -> new SonnetScene();
            case Pendolo -> new PendoloScene();
            case Fume -> new FumeScene();
            case Monet -> new MonetScene();
            case Claddagh -> new CladdaghScene();
            case Partita -> new PartitaScene();
            case Classic -> new ClassicScene();
            case Normal -> throw new IllegalStateException("Normal uses MusicLyricsPanel");
        };
    }
}
