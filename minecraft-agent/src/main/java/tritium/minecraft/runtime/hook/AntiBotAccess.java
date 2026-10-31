package tritium.minecraft.runtime.hook;

import tritium.minecraft.runtime.util.render.Render3D;
import tritium.minecraft.runtime.util.render.RenderState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Reflection-only game services used by the session's OpenB4 BotCheck manager. */
public class AntiBotAccess extends ExtendedCombatAccess {
    private RenderAccess render;
    private Render3D drawing;

    public AntiBotAccess(Object minecraft) {
        super(minecraft);
    }

    public long now() {
        return System.currentTimeMillis();
    }

    public boolean playerEntity(Object e) {
        return kind(e, "entity.player.EntityPlayer");
    }

    public int ticks() {
        return (int) num(player(), "ticksExisted/field_70173_aa");
    }

    public String name(Object e) {
        return String.valueOf(invoke(e, "getName/func_70005_c_"));
    }

    public String display(Object e) {
        return text(invoke(e, "getDisplayName/func_145748_c_"));
    }

    public String text(Object component) {
        return String.valueOf(invoke(component, "getUnformattedText/func_150260_c"));
    }

    public Object uuid(Object e) {
        return invoke(e, "getUniqueID/func_110124_au");
    }

    public float health(Object e) {
        return (float) result(e, "getHealth/func_110143_aJ");
    }

    public int maxHurtTime(Object e) {
        return (int) num(e, "maxHurtTime/field_70738_aO");
    }

    public boolean invisible(Object e) {
        return truth(e, "isInvisible/func_82150_aj");
    }

    public boolean sleeping(Object e) {
        return truth(e, "isPlayerSleeping/func_70608_bn");
    }

    public Object held(Object e) {
        return invoke(e, "getHeldItem/func_70694_bm");
    }

    public Object info(Object e) {
        Object handler = handler();
        return e == null || handler == null
                ? null
                : invoke(handler, "getPlayerInfo/func_175102_a", uuid(e));
    }

    public Object networkTeam(Object info) {
        return invoke(info, "getPlayerTeam/func_178850_i");
    }

    public String teamName(Object team) {
        return String.valueOf(invoke(team, "getTeamName/func_96669_c"));
    }

    public Object team(Object e) {
        return invoke(e, "getTeam/func_96124_cp");
    }

    public String registeredTeamName(Object team) {
        return String.valueOf(invoke(team, "getRegisteredName/func_96661_b"));
    }

    public int ping(Object info) {
        return (int) result(info, "getResponseTime/func_178853_c");
    }

    public String itemName(Object stack) {
        return String.valueOf(invoke(stack, "getDisplayName/func_82833_r"));
    }

    public boolean canRiderInteract(Object entity) {
        try {
            return truth(entity, "canRiderInteract");
        } catch (IllegalStateException missingForgeExtension) {
            if (missingForgeExtension.getMessage().startsWith("Missing Minecraft method "))
                return false;
            throw missingForgeExtension;
        }
    }

    public boolean hypixelServer() {
        if (singleplayer()) return false;
        Object server = invoke(minecraft(), "getCurrentServerData/func_147104_D");
        if (server == null) return false;
        if (String.valueOf(field(server, "serverIP/field_78845_b"))
                .toLowerCase(Locale.ROOT)
                .endsWith("hypixel.net")) return true;
        try {
            Object overlay =
                    invoke(
                            field(minecraft(), "ingameGUI/field_71456_v"),
                            "getTabList/func_175181_h");
            Object header = field(overlay, "header/field_175256_i");
            return header != null && text(header).toLowerCase(Locale.ROOT).contains("hypixel.net");
        } catch (RuntimeException unavailableHeader) {
            return false;
        }
    }

    public int dimension() {
        return (int)
                result(field(world(), "provider/field_73011_w"), "getDimensionId/func_177502_q");
    }

    public List<String> sidebar() {
        if (world() == null) return null;
        Object board = invoke(world(), "getScoreboard/func_96441_U");
        if (board == null) return null;
        Object objective = invoke(board, "getObjectiveInDisplaySlot/func_96539_a", 1);
        if (objective == null) return null;
        List<?> scores =
                new ArrayList<Object>(
                        (Collection<?>) invoke(board, "getSortedScores/func_96534_i", objective));
        List<String> lines = new ArrayList<String>();
        lines.add(String.valueOf(invoke(objective, "getDisplayName/func_96678_d")));
        int count = 0;
        for (int i = scores.size() - 1; i >= 0 && count < 15; i--) {
            Object score = scores.get(i);
            if (score == null) continue;
            String name = (String) invoke(score, "getPlayerName/func_96653_e");
            if (name == null || name.startsWith("#")) continue;
            Object team = invoke(board, "getPlayersTeam/func_96509_i", name);
            lines.add(
                    String.valueOf(
                            invoke(
                                    clazz("scoreboard.ScorePlayerTeam"),
                                    "formatPlayerName/func_96667_a",
                                    team,
                                    name)));
            count++;
        }
        return lines;
    }

    public void chat(String text, String hover) {
        Object component = make("util.ChatComponentText", text);
        if (hover != null) {
            Object action = field(clazz("event.HoverEvent$Action"), "SHOW_TEXT");
            Object event = make("event.HoverEvent", action, make("util.ChatComponentText", hover));
            Object style = make("util.ChatStyle");
            invoke(style, "setChatHoverEvent/func_150209_a", event);
            invoke(component, "setChatStyle/func_150255_a", style);
        }
        invoke(player(), "addChatMessage/func_145747_a", component);
    }

    public void sound(String name, float volume, float pitch) {
        Object sound =
                make(
                        "client.audio.PositionedSoundRecord",
                        make("util.ResourceLocation", name),
                        volume,
                        pitch,
                        (float) coordinate(player(), 0),
                        (float) coordinate(player(), 1),
                        (float) coordinate(player(), 2));
        invoke(
                invoke(minecraft(), "getSoundHandler/func_147118_V"),
                "playSound/func_147682_a",
                sound);
    }

    public void watchdogBox(Object entity, float partial) {
        if (render == null) {
            render = new RenderAccess(minecraft());
            drawing = new Render3D(render);
        }
        try (RenderState state = new RenderState(render.nativeCache())) {
            drawing.update(partial);
            drawing.entity(entity, 2, java.awt.Color.red.getRGB(), partial);
        }
    }
}
