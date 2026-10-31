package tritium.minecraft.runtime.network;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.*;

public final class FakeLagModule extends OpenB4Module {
    private final OutboundPacketQueue queue = new OutboundPacketQueue();
    private long delay, lastPulse;
    private Object ghost;
    private double[] serverPos;
    private boolean caughtUp, show;

    public FakeLagModule(ExtendedCombatRuntime runtime) {
        super(runtime, "fakeLag");
    }

    public Object ghost() {
        return ghost;
    }

    @Override
    public void enable() {
        delay = (int) number("Outbound delay");
        show = on("Show server position");
        if (show) spawnGhost();
    }

    private void spawnGhost() {
        caughtUp = true;
        ghost = game.ghost(game.player(), true);
        serverPos = game.position(ghost);
    }

    @Override
    public void settingsChanged() {
        if (show != on("Show server position")) {
            show = on("Show server position");
            if (show) spawnGhost();
            else ghost = null;
        }
    }

    public void pulse() {
        if (delay != (int) number("Outbound delay")) {
            runtime.disable(id);
            return;
        }
        long now = runtime.now();
        if (changed("Pulse")) {
            if (lastPulse == 0) lastPulse = now;
            else if (now - lastPulse > (int) number("Pulse")) {
                lastPulse = now;
                release(true);
                return;
            }
        }
        release(false);
    }

    public boolean send(Object manager, Object packet) {
        if (game.singleplayer()) return false;
        queue.add(manager, packet, runtime.now() + delay);
        if (game.attackPacket(packet) && on("Disable on attack")) runtime.disable(id);
        return true;
    }

    public void release(boolean all) {
        for (OutboundPacketQueue.Entry p : queue.drain(runtime.now(), all)) {
            if (ghost != null
                    && game.packet(p.packet, "C03PacketPlayer")
                    && game.truth(p.packet, "isMoving/func_149466_j"))
                serverPos = game.packetPosition(p.packet);
            runtime.afterFakeLag(p.manager, p.packet);
        }
    }

    public void update() {
        if (ghost != null && serverPos != null) {
            game.positionGhost(ghost, serverPos);
            caughtUp = game.caughtUp(ghost);
        }
    }

    public void renderWorld(float partial) {
        if (ghost != null
                && !runtime.blinkGhost()
                && !runtime.lagRangeGhost()
                && (!caughtUp || game.moving()))
            runtime.renderer().box(ghost, partial, runtime.themeColor(), false);
    }

    @Override
    public void disable(boolean worldChanged) {
        ghost = null;
        caughtUp = false;
        lastPulse = 0;
        if (worldChanged) queue.clear();
        else release(true);
    }
}
