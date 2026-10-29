package tritium.minecraft.runtime.util;

import java.util.*;

/** Raven's Hypixel teammate rules, refreshed once per target scan. */
public final class TeamChecks extends GameReflection {
    private final Object minecraft;
    private Object player, world, ownTeam;
    private boolean hypixel, pit, bedwarsGame;
    public TeamChecks(Object minecraft) { super(minecraft.getClass().getClassLoader()); this.minecraft=minecraft; }
    public void refresh(Object player,Object world) {
        this.player=player; this.world=world; ownTeam=null; pit=bedwarsGame=false;
        Object server=call(minecraft,"getCurrentServerData/func_147104_D");
        String host=server==null ? "" : String.valueOf(get(server,"serverIP/field_78845_b")).toLowerCase(Locale.ROOT).split(":")[0];
        hypixel=host.equals("hypixel.net") || host.endsWith(".hypixel.net");
        if (bool(call(minecraft,"isSingleplayer/func_71356_B")) || server==null) { hypixel=false; return; }
        if (!hypixel) {
            Object overlay=call(get(minecraft,"ingameGUI/field_71456_v"),"getTabList/func_175181_h");
            Object header=get(overlay,"header/field_175256_i");
            hypixel=header!=null && String.valueOf(call(header,"getUnformattedText/func_150260_c")).toLowerCase(Locale.ROOT).contains("hypixel.net");
        }
        if (!hypixel) return;
        ownTeam=call(player,"getTeam/func_96124_cp");
        Object scoreboard=call(world,"getScoreboard/func_96441_U");
        if (scoreboard==null) return;
        Object objective=call(scoreboard,"getObjectiveInDisplaySlot/func_96539_a",1);
        if (objective==null) return;
        String title=String.valueOf(call(objective,"getDisplayName/func_96678_d")).replaceAll("§.","");
        pit=title.equals("THE HYPIXEL PIT");
        if (!title.equals("BED WARS")) return;
        List<?> scores=new ArrayList<Object>((Collection<?>)call(scoreboard,"getSortedScores/func_96534_i",objective));
        int line=0;
        for (int i=scores.size()-1;i>=0 && line<15;i--) {
            Object score=scores.get(i); String name=String.valueOf(call(score,"getPlayerName/func_96653_e"));
            if (name.startsWith("#")) continue;
            line++; Object team=call(scoreboard,"getPlayersTeam/func_96509_i",name);
            String text=String.valueOf(call(type("scoreboard.ScorePlayerTeam"),"formatPlayerName/func_96667_a",team,name));
            if (line==1 && text.contains("  §8L")) return;
            if (line>=5) {
                if (line==8 && text.startsWith("Mode")) return;
                if (text.endsWith(" YOU")) { bedwarsGame=true; return; }
            }
        }
    }
    public boolean sameTeam(Object entity) {
        if (!hypixel || pit) return false;
        if (is(entity,"entity.monster.EntitySilverfish")) return prefix(name(entity),ownName(),5);
        if (is(entity,"entity.monster.EntityIronGolem")) {
            Object tag=call(world,"getEntityByID/func_73045_a",integer(call(entity,"getEntityId/func_145782_y"))+1);
            return tag!=null && prefix(name(tag),ownName(),2);
        }
        if (!is(entity,"entity.player.EntityPlayer")) return !is(entity,"entity.passive.EntityHorse") && bedwarsGame;
        Object team=call(entity,"getTeam/func_96124_cp");
        return team!=null && ownTeam!=null && call(team,"getColorPrefix/func_96668_e").equals(call(ownTeam,"getColorPrefix/func_96668_e"));
    }
    private String ownName() { return String.valueOf(call(call(player,"getDisplayName/func_145748_c_"),"getUnformattedText/func_150260_c")); }
    private String name(Object e) { return String.valueOf(call(e,"getName/func_70005_c_")); }
    private static boolean prefix(String a,String b,int count) { return a.length()>count && b.length()>count && a.substring(0,count).equals(b.substring(0,count)); }
}
