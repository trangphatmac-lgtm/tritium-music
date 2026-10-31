package tritium.minecraft.runtime.util;

import tritium.minecraft.runtime.hook.ExtendedCombatAccess;

import java.util.*;

/** Read-only Hypixel manager portion needed by Disable in lobby / Disable in replays. */
public final class OpenB4ServerState {
    private final ExtendedCombatAccess game;
    private boolean lobby, replay;

    public OpenB4ServerState(ExtendedCombatAccess game) {
        this.game = game;
    }

    public boolean lobby() {
        return lobby;
    }

    public boolean replay() {
        return replay;
    }

    public void clear() {
        lobby = replay = false;
    }

    public void refresh() {
        clear();
        if (!game.inWorld() || game.singleplayer()) return;
        Object server = game.invoke(game.minecraft(), "getCurrentServerData/func_147104_D");
        if (server == null) return;
        String host =
                String.valueOf(game.field(server, "serverIP/field_78845_b"))
                        .toLowerCase(Locale.ROOT);
        boolean hypixel = host.endsWith("hypixel.net");
        if (!hypixel) {
            Object
                    overlay =
                            game.invoke(
                                    game.field(game.minecraft(), "ingameGUI/field_71456_v"),
                                    "getTabList/func_175181_h"),
                    header = game.field(overlay, "header/field_175256_i");
            hypixel =
                    header != null
                            && String.valueOf(
                                            game.invoke(header, "getUnformattedText/func_150260_c"))
                                    .toLowerCase(Locale.ROOT)
                                    .contains("hypixel.net");
        }
        if (!hypixel) return;
        Object board = game.invoke(game.world(), "getScoreboard/func_96441_U");
        if (board == null) return;
        Object objective = game.invoke(board, "getObjectiveInDisplaySlot/func_96539_a", 1);
        if (objective == null) return;
        String title =
                String.valueOf(game.invoke(objective, "getDisplayName/func_96678_d"))
                        .replaceAll("§.", "");
        if (title.equals("REPLAY")) {
            replay = true;
            return;
        }
        List<?> scores =
                new ArrayList<Object>(
                        (Collection<?>)
                                game.invoke(board, "getSortedScores/func_96534_i", objective));
        for (int i = scores.size() - 1; i >= 0; i--) {
            Object score = scores.get(i);
            if (score == null) continue;
            String name = (String) game.invoke(score, "getPlayerName/func_96653_e");
            if (name == null || name.startsWith("#")) continue;
            Object team = game.invoke(board, "getPlayersTeam/func_96509_i", name);
            String line =
                    String.valueOf(
                            game.invoke(
                                    game.clazz("scoreboard.ScorePlayerTeam"),
                                    "formatPlayerName/func_96667_a",
                                    team,
                                    name));
            lobby = line.contains("  §8L");
            break;
        }
    }
}
