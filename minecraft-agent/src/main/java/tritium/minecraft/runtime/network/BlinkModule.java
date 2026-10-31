package tritium.minecraft.runtime.network;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.*;

public final class BlinkModule extends OpenB4Module {
    private final OutboundPacketQueue queue = new OutboundPacketQueue();
    private boolean blinking;
    private int ticks;
    private String text;
    private long startedAt, stoppedAt;
    private Object ghost;

    public BlinkModule(ExtendedCombatRuntime runtime) {
        super(runtime, "blink");
    }

    public Object ghost() {
        return ghost;
    }

    public boolean blinking() {
        return blinking;
    }

    public boolean cancelsKnockback() {
        return blinking && on("Cancel knockback");
    }

    @Override
    public void enable() {
        if (game.singleplayer()) {
            runtime.disable(id);
            return;
        }
        blinking = true;
        ticks = 0;
        startedAt = runtime.now();
        if (on("Show initial position")) ghost = game.ghost(game.player(), true);
    }

    public boolean hold(Object manager, Object packet) {
        if ((!blinking && !runtime.autoBlockQueueActive()) || game.singleplayer()) return false;
        queue.add(manager, packet, 0);
        if (!blinking) return true;
        boolean disable = false;
        if (game.attackPacket(packet)) disable = on("Disable on attack");
        else if (game.packet(packet, "C0EPacketClickWindow")) disable = on("Disable on inventory");
        else if (game.packet(packet, "C07PacketPlayerDigging")) {
            String action = String.valueOf(game.invoke(packet, "getStatus/func_180762_c"));
            disable =
                    on("Disable on mine")
                            && ("START_DESTROY_BLOCK".equals(action)
                                    || "STOP_DESTROY_BLOCK".equals(action));
        } else if (game.placement(packet))
            disable =
                    on("Disable on place")
                            && game.item(game.packetStack(packet), "item.ItemBlock")
                            && (int) game.result(packet, "getPlacedBlockDirection/func_149568_f")
                                    != 255;
        if (disable) runtime.disable(id);
        return true;
    }

    public void tick() {
        if (!blinking && !runtime.autoBlockQueueActive()) return;
        if (game.kind(game.screen(), "client.gui.GuiGameOver")) {
            runtime.discardAutoBlockQueue();
            runtime.disable(id);
            return;
        }
        if (!blinking) return;
        if (number("Auto-disable") != 0 && ticks >= number("Auto-disable") * 20) {
            runtime.disable(id);
            return;
        }
        // This conditional counter is deliberate: it is the supplied OpenB4 implementation.
        text =
                String.valueOf(
                        number("Auto-disable") != 0
                                ? (int) (number("Auto-disable") * 20) - ticks + 1
                                : ++ticks);
    }

    @Override
    public void disable(boolean worldChanged) {
        if (blinking) {
            blinking = false;
            stoppedAt = runtime.now();
        }
        ghost = null;
        if (worldChanged) {
            queue.clear();
            text = null;
        } else flushIfIdle();
    }

    public void discard() {
        queue.clear();
    }

    /** Flush on the caller thread only after both shared PacketQueue owners have closed. */
    public void flushIfIdle() {
        if (!blinking && !runtime.autoBlockQueueActive())
            for (OutboundPacketQueue.Entry p : queue.drain(0, true))
                game.sendDirect(p.manager, p.packet);
    }

    public void renderWorld(float partial) {
        if (ghost != null) runtime.renderer().box(ghost, partial, runtime.themeColor(), false);
    }

    public void renderHud() {
        if (!on("Show blink ticks") || text == null) return;
        long elapsed = runtime.now() - (blinking ? startedAt : stoppedAt);
        int alpha =
                blinking
                        ? (elapsed < 125 ? (int) (elapsed / 125f * 240) : 240)
                        : (elapsed < 125 ? (int) (240 - elapsed / 125f * 240) : 0);
        if (!blinking && alpha == 0) text = null;
        runtime.renderer().timer(text, runtime.themeColor(), alpha);
    }
}
