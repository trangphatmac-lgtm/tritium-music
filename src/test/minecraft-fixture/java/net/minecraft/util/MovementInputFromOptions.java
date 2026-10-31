package net.minecraft.util;

/** Mirrors the raw input assignment and the vanilla sneak scaling order. */
public class MovementInputFromOptions extends MovementInput {
    public void updatePlayerMoveState() {
        moveForward = 1; moveStrafe = 1; jump = false; sneak = false;
        if (sneak) { moveForward = (float) (moveForward * .3); moveStrafe = (float) (moveStrafe * .3); }
    }
}
