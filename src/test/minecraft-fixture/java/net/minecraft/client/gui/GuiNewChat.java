package net.minecraft.client.gui;
import net.minecraft.util.IChatComponent;
public class GuiNewChat {
    public static int displayed;
    public void printChatMessage(IChatComponent component) { displayed++; }
}
