package net.minecraft.entity;

/** Minimal method bodies with the same transformation anchors as 1.8.9. Not game code. */
public class Entity {
    public boolean onGround;
    public float rotationYaw, rotationPitch, prevRotationPitch;
    public double marker;
    public boolean isSneaking() { return false; }
    public void moveEntity(double x, double y, double z) {
        // Six live double locals put vanilla's edge boolean in local slot 19.
        double a=x, b=y, c=z, d=x+y, e=y+z, f=z+x;
        boolean edge = onGround && isSneaking();
        marker = a+b+c+d+e+f+(edge ? 1 : 0);
    }
    public void moveFlying(float strafe, float forward, float friction) {
        marker = rotationYaw + strafe + forward + friction;
    }
}
