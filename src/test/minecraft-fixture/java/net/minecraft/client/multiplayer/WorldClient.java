package net.minecraft.client.multiplayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
public class WorldClient {
    public int added;
    public void onEntityAdded(Entity entity) { added++; }
    public MovingObjectPosition rayTraceBlocks(Vec3 from, Vec3 to) { return null; }
}
