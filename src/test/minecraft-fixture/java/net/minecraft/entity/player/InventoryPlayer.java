package net.minecraft.entity.player;
public class InventoryPlayer {
    public int currentItem;
    public void changeCurrentItem(int delta) { currentItem += delta; }
}
