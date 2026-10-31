package tritium.minecraft.protocol;

/** Shared category metadata; category selection never changes a module's enabled state. */
public enum ModuleCategory {
    LYRIC("lyric"), COMBAT("combat"), MOVEMENT("movement"), PLAYER("player"),
    RENDER("render"), WORLD("world"), OTHER("other");

    public final String id;

    ModuleCategory(String id) { this.id = id; }

    public boolean isHidden() { return this != LYRIC; }
    public boolean isAvailable(boolean hidden) { return !isHidden() || hidden; }

    public static ModuleCategory forModule(String id) {
        if (CombatSettings.isCombat(id)) return COMBAT;
        if (MovementSettings.isMovement(id)) return MOVEMENT;
        // World lyrics are a music feature, not a Hidden world module. Keep the
        // common editor and disconnect actions accessible without Hidden as well.
        if ("musicInfo".equals(id) || "musicLyrics".equals(id) || "musicSpectrum".equals(id)
                || "musicToast".equals(id) || "worldLyrics".equals(id)
                || "editHud".equals(id) || "uninject".equals(id)) return LYRIC;
        return OTHER;
    }
}
