package net.minecraft.client.renderer.entity;
import net.minecraft.entity.EntityLivingBase;
public class RendererLivingEntity {
    public int nameCalls;
    public float headPitch;
    public void doRender(EntityLivingBase entity, double x, double y, double z, float yaw, float partial) {
        headPitch = entity.rotationPitch * partial + entity.prevRotationPitch * (1 - partial);
    }
    public void renderName(EntityLivingBase entity, double x, double y, double z) { nameCalls++; }
}
