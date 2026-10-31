package fixtures;

import java.lang.reflect.Field;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.*;
import net.minecraft.network.play.server.S02PacketChat;

/** Exercises the same callbacks after removal, so clearing callback fields cannot hide stale bytecode. */
public final class BridgeAssistProbe {
    public static String run() throws Exception {
        Class<?> bridge = Class.forName("tritium.minecraft.bridge.HookBridge", true, null);
        Field event = bridge.getField("callback"), module = bridge.getField("moduleCallback");
        Object oldEvent = event.get(null), oldModule = module.get(null);
        int[] counts = new int[20]; boolean[] replaceRay = {false};
        StringBuilder order = new StringBuilder(), messages = new StringBuilder();
        event.set(null, (IntPredicate) kind -> { if (kind == 7) { order.append('G'); return replaceRay[0]; } return false; });
        module.set(null, (BiFunction<Integer,Object[],Object>) (kind,args) -> {
            counts[kind]++;
            switch (kind) {
                case 13: ((MovementInput) args[0]).sneak = true; break;
                case 14: case 15: return true;
                case 16: order.append('R'); break;
                case 17: messages.append(((IChatComponent) args[0]).getUnformattedText()).append(':').append(args[1]).append(';'); break;
                case 18: order.append('B'); break;
                case 19: order.append('A'); break;
            }
            return null;
        });
        MovementInputFromOptions input = new MovementInputFromOptions();
        Minecraft mc = Minecraft.getMinecraft(); mc.rightClicks = 0; mc.inventory.currentItem = 0;
        EntityRenderer.mouseOvers = 0; GuiNewChat.displayed = 0;
        boolean exceptionPreserved = false;
        try {
            input.updatePlayerMoveState(); mc.rightClickMouse(); mc.runTick();
            replaceRay[0] = true; mc.runTick();
            // Scrolling is intercepted only in runTick, not arbitrary inventory changes.
            mc.inventory.changeCurrentItem(3);
            EntityRenderer renderer = new EntityRenderer(); renderer.throwOnMouseOver = true;
            try { renderer.getMouseOver(1); } catch (IllegalStateException expected) { exceptionPreserved = "fixture mouse-over failure".equals(expected.getMessage()); }
            NetHandlerPlayClient handler = new NetHandlerPlayClient();
            handler.handleChat(new S02PacketChat(new ChatComponentText("normal"), (byte)0));
            handler.handleChat(new S02PacketChat(new ChatComponentText("system"), (byte)1));
            handler.handleChat(new S02PacketChat(new ChatComponentText("cancel"), (byte)0));
            handler.handleChat(new S02PacketChat(new ChatComponentText("actionbar"), (byte)2));
            new GuiNewChat().printChatMessage(new ChatComponentText("local"));
        } finally { event.set(null, oldEvent); module.set(null, oldModule); }
        int total = 0; for (int i = 13; i < counts.length; i++) total += counts[i];
        return "BRIDGEASSIST " + total + " " + input.moveForward + " " + mc.rightClicks + " " + mc.inventory.currentItem
                + " " + EntityRenderer.mouseOvers + " " + exceptionPreserved + " " + GuiNewChat.displayed + " [" + order + "] [" + messages + "]";
    }
    private BridgeAssistProbe() { }
}
