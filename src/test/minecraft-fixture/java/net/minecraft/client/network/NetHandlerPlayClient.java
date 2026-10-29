package net.minecraft.client.network;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.play.server.*;
/** Attach fixture only, not game code. */
public class NetHandlerPlayClient {
    public void handleEntityVelocity(S12PacketEntityVelocity packet) { PacketThreadUtil.checkThreadAndEnqueue(packet,this,null); }
    public void handlePlayerPosLook(S08PacketPlayerPosLook packet) { PacketThreadUtil.checkThreadAndEnqueue(packet,this,null); }
    public void handleExplosion(S27PacketExplosion packet) { PacketThreadUtil.checkThreadAndEnqueue(packet,this,null); }
}
