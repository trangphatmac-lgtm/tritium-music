package net.minecraft.util;
public class ChatComponentText implements IChatComponent {
    private final String text;
    public ChatComponentText(String text) { this.text = text; }
    public String getUnformattedText() { return text; }
}
