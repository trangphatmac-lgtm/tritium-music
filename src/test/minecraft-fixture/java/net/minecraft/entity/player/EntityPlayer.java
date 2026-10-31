package net.minecraft.entity.player;

/** Attach fixture only, not game code. */
public class EntityPlayer extends net.minecraft.entity.EntityLivingBase {
    public boolean isBlocking() { return false; }
    public int getItemInUseCount() { return 0; }
    public void attackTargetEntityWithCurrentItem(net.minecraft.entity.Entity target) { marker++; }
}
