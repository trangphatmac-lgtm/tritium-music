package tritium.minecraft.runtime.hook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Validates hover, tab/scoreboard, sound and BotCheck geometry on actual MCP or SRG jars. */
@EnabledIfSystemProperty(named = "minecraft.mappingClasspathFile", matches = ".+")
class AntiBotMappingsTest {
    @Test
    void antiBotReflectionContractsExistOnRealMinecraft() throws Exception {
        List<URL> urls = new ArrayList<URL>();
        for (String p :
                Files.readAllLines(Paths.get(System.getProperty("minecraft.mappingClasspathFile"))))
            if (!p.trim().isEmpty()) urls.add(Paths.get(p).toUri().toURL());
        try (URLClassLoader loader =
                new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
            String[] methods = {
                "client/network/NetHandlerPlayClient|getPlayerInfo/func_175102_a|(Ljava/util/UUID;)Lnet/minecraft/client/network/NetworkPlayerInfo;",
                "client/network/NetworkPlayerInfo|getPlayerTeam/func_178850_i|()Lnet/minecraft/scoreboard/ScorePlayerTeam;",
                "client/network/NetworkPlayerInfo|getResponseTime/func_178853_c|()I",
                "scoreboard/ScorePlayerTeam|getTeamName/func_96669_c|()Ljava/lang/String;",
                "scoreboard/Team|getRegisteredName/func_96661_b|()Ljava/lang/String;",
                "entity/Entity|getUniqueID/func_110124_au|()Ljava/util/UUID;",
                "entity/Entity|getDisplayName/func_145748_c_|()Lnet/minecraft/util/IChatComponent;",
                "entity/EntityLivingBase|isPlayerSleeping/func_70608_bn|()Z",
                "entity/EntityLivingBase|getTeam/func_96124_cp|()Lnet/minecraft/scoreboard/Team;",
                "util/ChatComponentText|<init>|(Ljava/lang/String;)V",
                "util/ChatStyle|setChatHoverEvent/func_150209_a|(Lnet/minecraft/event/HoverEvent;)Lnet/minecraft/util/ChatStyle;",
                "util/ChatComponentStyle|setChatStyle/func_150255_a|(Lnet/minecraft/util/ChatStyle;)Lnet/minecraft/util/IChatComponent;",
                "event/HoverEvent|<init>|(Lnet/minecraft/event/HoverEvent$Action;Lnet/minecraft/util/IChatComponent;)V",
                "client/entity/EntityPlayerSP|addChatMessage/func_145747_a|(Lnet/minecraft/util/IChatComponent;)V",
                "client/audio/PositionedSoundRecord|<init>|(Lnet/minecraft/util/ResourceLocation;FFFFF)V",
                "client/audio/SoundHandler|playSound/func_147682_a|(Lnet/minecraft/client/audio/ISound;)V",
                "client/Minecraft|getSoundHandler/func_147118_V|()Lnet/minecraft/client/audio/SoundHandler;",
                "client/gui/GuiIngame|getTabList/func_175181_h|()Lnet/minecraft/client/gui/GuiPlayerTabOverlay;",
                "world/WorldProvider|getDimensionId/func_177502_q|()I",
                "scoreboard/Scoreboard|getSortedScores/func_96534_i|(Lnet/minecraft/scoreboard/ScoreObjective;)Ljava/util/Collection;",
                "scoreboard/ScorePlayerTeam|formatPlayerName/func_96667_a|(Lnet/minecraft/scoreboard/Team;Ljava/lang/String;)Ljava/lang/String;",
                "util/MathHelper|sin/func_76126_a|(F)F",
                "util/MathHelper|cos/func_76134_b|(F)F",
                "util/AxisAlignedBB|isVecInside/func_72318_a|(Lnet/minecraft/util/Vec3;)Z",
                "util/AxisAlignedBB|calculateIntercept/func_72327_a|(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)Lnet/minecraft/util/MovingObjectPosition;",
                "util/MovingObjectPosition|<init>|(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/Vec3;)V"
            };
            for (String spec : methods) check(loader, spec, false);
            for (String spec :
                    new String[] {
                        "entity/EntityLivingBase|maxHurtTime/field_70738_aO|I",
                        "client/gui/GuiPlayerTabOverlay|header/field_175256_i|Lnet/minecraft/util/IChatComponent;",
                        "event/HoverEvent$Action|SHOW_TEXT|Lnet/minecraft/event/HoverEvent$Action;",
                        "world/World|provider/field_73011_w|Lnet/minecraft/world/WorldProvider;"
                    }) check(loader, spec, true);
        }
    }

    private void check(ClassLoader loader, String spec, boolean field) throws Exception {
        String[] p = spec.split("\\|");
        assertTrue(
                exists(
                        loader,
                        "net/minecraft/" + p[0],
                        new HashSet<String>(Arrays.asList(p[1].split("/"))),
                        p[2],
                        field,
                        new HashSet<String>()),
                spec);
    }

    private boolean exists(
            ClassLoader loader,
            String name,
            Set<String> aliases,
            String descriptor,
            boolean field,
            Set<String> seen)
            throws Exception {
        if (name == null || !seen.add(name)) return false;
        try (InputStream in = loader.getResourceAsStream(name + ".class")) {
            if (in == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            if (field) {
                for (FieldNode f : node.fields)
                    if (aliases.contains(f.name) && descriptor.equals(f.desc)) return true;
            } else
                for (MethodNode m : node.methods)
                    if (aliases.contains(m.name) && descriptor.equals(m.desc)) return true;
            if (exists(loader, node.superName, aliases, descriptor, field, seen)) return true;
            for (String parent : node.interfaces)
                if (exists(loader, parent, aliases, descriptor, field, seen)) return true;
            return false;
        }
    }
}
