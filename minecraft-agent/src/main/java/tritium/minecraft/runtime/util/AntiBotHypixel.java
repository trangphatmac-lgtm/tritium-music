package tritium.minecraft.runtime.util;

import tritium.minecraft.runtime.hook.AntiBotAccess;

import java.util.List;

/** The original Hypixel.detect state machine; IRC/locraw do not participate in BotCheck. */
public final class AntiBotHypixel {
    public enum State {
        NOT_HYPIXEL,
        UNKNOWN,
        LIMBO,
        LOBBY,
        REPLAY,
        BRIDGE_PRACTICE,
        PIT,
        MURDER_MYSTERY_GAME,
        BEDWARS_PREGAME,
        BEDWARS_GAME_BED,
        BEDWARS_GAME_NO_BED,
        SKYWARS_QUEUE,
        SKYWARS_GAME_SOLO,
        SKYWARS_GAME_TEAM,
        DUELS_QUEUE,
        DUELS_GAME
    }

    private State state = State.UNKNOWN;

    public State state() {
        return state;
    }

    public boolean onHypixel() {
        return state != State.NOT_HYPIXEL;
    }

    public boolean duelsGame() {
        return state == State.DUELS_GAME;
    }

    public void clear() {
        state = State.UNKNOWN;
    }

    public void refresh(AntiBotAccess game) {
        if (!game.hypixelServer()) {
            state = State.NOT_HYPIXEL;
            return;
        }
        List<String> lines = game.sidebar();
        state = detect(lines, lines == null ? game.dimension() : 0);
    }

    public static State detect(List<String> lines, int dimension) {
        if (lines == null) return dimension == 1 ? State.LIMBO : State.UNKNOWN;
        boolean bedwars = false, skywars = false, skyPlayers = false, duels = false, murder = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0) {
                String title = BotCheck.strip(line);
                if (title.equals("REPLAY")) return State.REPLAY;
                if (title.equals("BED WARS PRACTICE")) return State.BRIDGE_PRACTICE;
                if (title.equals("THE HYPIXEL PIT")) return State.PIT;
                if (title.equals("BED WARS")) bedwars = true;
                else if (title.equals("SKYWARS")) skywars = true;
                else if (title.equals("DUELS")) duels = true;
                else if (title.equals("MURDER MYSTERY")) murder = true;
                continue;
            }
            if (i == 1 && line.contains("  §8L")) return State.LOBBY;
            if (bedwars) {
                if (i >= 5) {
                    if (i == 8 && line.startsWith("Mode")) return State.BEDWARS_PREGAME;
                    if (line.endsWith(" YOU"))
                        return line.indexOf('✓') != -1
                                ? State.BEDWARS_GAME_BED
                                : State.BEDWARS_GAME_NO_BED;
                }
            } else if (skywars) {
                if (i == 3) {
                    if (line.startsWith("Player")) return State.SKYWARS_QUEUE;
                } else if (i == 6) {
                    if (line.startsWith("Player")) skyPlayers = true;
                } else if (i == 7 && skyPlayers)
                    return !line.startsWith("Team")
                            ? State.SKYWARS_GAME_SOLO
                            : State.SKYWARS_GAME_TEAM;
            } else if (murder) {
                if (i == 3 && line.startsWith("Role")) return State.MURDER_MYSTERY_GAME;
            } else if (duels) {
                if (i == 3) {
                    if (line.startsWith("Time")) return State.DUELS_GAME;
                } else if (i == 6 && (line.startsWith("Waiting.") || line.startsWith("Starting ")))
                    return State.DUELS_QUEUE;
            }
        }
        return State.UNKNOWN;
    }
}
