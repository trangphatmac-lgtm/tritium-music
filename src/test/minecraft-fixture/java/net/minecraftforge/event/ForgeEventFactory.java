package net.minecraftforge.event;
import net.minecraft.util.*;
public class ForgeEventFactory {
    public static IChatComponent onClientChat(byte type, IChatComponent component) {
        return component.getUnformattedText().equals("cancel") ? null : new ChatComponentText("filtered:" + component.getUnformattedText());
    }
}
