package net.minecraft.client.renderer;
import net.minecraft.item.ItemStack;
public class ItemRenderer {
    public ItemStack itemToRender=new ItemStack();
    public float progress;
    public void renderItemInFirstPerson(float partial) {
        float equip = partial + 1;
        ItemStack item = itemToRender;
        progress = item == null ? -equip : equip;
    }
}
