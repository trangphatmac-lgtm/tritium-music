package net.minecraft.client.entity;

/** Attach fixture only, not game code. */
public class EntityPlayerSP extends AbstractClientPlayer {
    public void onUpdate() {}
    public void onUpdateWalkingPlayer() {}
    public void onLivingUpdate() { new net.minecraft.util.MovementInput().updatePlayerMoveState(); }
    public void updateEntityActionState() {}
}
