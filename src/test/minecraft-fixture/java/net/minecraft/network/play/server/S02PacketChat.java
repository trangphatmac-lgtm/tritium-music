package net.minecraft.network.play.server;
import net.minecraft.util.IChatComponent;
public class S02PacketChat {
    private final IChatComponent component; private final byte type;
    public S02PacketChat(IChatComponent component, byte type) { this.component = component; this.type = type; }
    public IChatComponent getChatComponent() { return component; }
    public byte getType() { return type; }
}
