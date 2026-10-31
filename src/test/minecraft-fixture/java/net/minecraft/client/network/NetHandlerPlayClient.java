package net.minecraft.client.network;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.play.server.*;
/** Attach fixture only, not game code. */
public class NetHandlerPlayClient {
    public void handleChat(S02PacketChat packet) {
        PacketThreadUtil.checkThreadAndEnqueue(packet,this,null);
        net.minecraft.util.IChatComponent component = net.minecraftforge.event.ForgeEventFactory.onClientChat(packet.getType(),packet.getChatComponent());
        if (component == null) return;
        if (packet.getType() != 2) new net.minecraft.client.gui.GuiNewChat().printChatMessage(component);
    }
    public void handleKeepAlive(S00PacketKeepAlive packet) { }
    public void handleEntityMovement(S14PacketEntity packet) { PacketThreadUtil.checkThreadAndEnqueue(packet,this,null); }
    public void handleEntityVelocity(S12PacketEntityVelocity packet) { PacketThreadUtil.checkThreadAndEnqueue(packet,this,null); }
    public void handlePlayerPosLook(S08PacketPlayerPosLook packet) { PacketThreadUtil.checkThreadAndEnqueue(packet,this,null); }
    public void handleExplosion(S27PacketExplosion packet) { PacketThreadUtil.checkThreadAndEnqueue(packet,this,null); }
}
