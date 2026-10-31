package net.minecraft.client.renderer.entity;
import net.minecraft.client.entity.AbstractClientPlayer;
public class RenderPlayer extends RendererLivingEntity {
    public boolean throwOnRender;
    public int renders, models;
    public void doRender(AbstractClientPlayer player, double x, double y, double z, float yaw, float partial) {
        if (throwOnRender) throw new IllegalStateException("fixture render failure");
        renders++;
    }
    public void setModelVisibilities(AbstractClientPlayer player) { models++; }
}
