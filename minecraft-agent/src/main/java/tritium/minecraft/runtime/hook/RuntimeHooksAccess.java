package tritium.minecraft.runtime.hook;

import tritium.minecraft.runtime.util.GameReflection;
import tritium.minecraft.runtime.util.CombatMath;
import java.util.ArrayDeque;

/** Session-level adapters for callbacks shared by more than one module category. */
final class RuntimeHooksAccess extends GameReflection {
    private final Object mc;
    private Object savedPlayer;
    private float savedYaw, savedPitch;
    private float lastYaw, lastPitch;
    private float[] selectedRotation;
    private float previousPitch;
    private Object previousPlayer;
    private final ArrayDeque<MouseOverRotation> mouseOverRotations = new ArrayDeque<MouseOverRotation>();
    RuntimeHooksAccess(Object mc) { super(mc.getClass().getClassLoader()); this.mc = mc; }
    Object player() { return get(mc, "thePlayer/field_71439_g"); }
    Object world() { return get(mc, "theWorld/field_71441_e"); }
    Object screen() { return get(mc, "currentScreen/field_71462_r"); }
    boolean local(Object entity) { return entity != null && entity == player(); }
    boolean gameThread() { return bool(call(mc, "isCallingFromMinecraftThread/func_152345_ab")); }
    void execute(Runnable action) { call(mc, "addScheduledTask/func_152344_a", action); }
    void process(Object handler, Object packet) { call(packet, "processPacket/func_148833_a", handler); }
    Object handler() { return call(mc, "getNetHandler/func_147114_u"); }
    float yaw() { return number(get(player(), "rotationYaw/field_70177_z")).floatValue(); }
    float pitch() { return number(get(player(), "rotationPitch/field_70125_A")).floatValue(); }
    float lastYaw() { initialize(); return lastYaw; }
    float lastPitch() { initialize(); return lastPitch; }
    private void initialize() {
        if (player() != previousPlayer) {
            previousPlayer = player(); if (previousPlayer != null) { lastYaw = yaw(); lastPitch = pitch(); }
        }
    }
    void rotation(float yaw, float pitch) {
        set(player(), "rotationYaw/field_70177_z", yaw); set(player(), "rotationPitch/field_70125_A", pitch);
    }
    void beginRotation(float[] rotation) {
        if (rotation == null || player() == null) return;
        savedPlayer = player(); savedYaw = yaw(); savedPitch = pitch(); rotation(rotation[0], rotation[1]);
    }
    void selectRotation(float[] selected) { selectedRotation = selected; }
    void rememberRotation() {
        if (player() == null) return;
        previousPitch = number(get(player(), "lastReportedPitch/field_175165_bM")).floatValue();
        lastYaw = yaw(); lastPitch = pitch();
        if (selectedRotation != null) {
            set(player(), "rotationYawHead/field_70759_as", lastYaw);
            float body = number(get(player(), "renderYawOffset/field_70761_aq")).floatValue();
            body += CombatMath.wrap(lastYaw - body) * .3f;
            float difference = Math.max(-75, Math.min(75, CombatMath.wrap(lastYaw - body)));
            body = lastYaw - difference;
            if (difference * difference > 2500) body += difference * .2f;
            set(player(), "renderYawOffset/field_70761_aq", body);
        }
    }
    float headPitch(Object entity, float vanilla, float partial) {
        return local(entity) && selectedRotation != null ? previousPitch + (lastPitch - previousPitch) * partial : vanilla;
    }
    void restoreRotation() {
        if (savedPlayer != null && savedPlayer == player()) rotation(savedYaw, savedPitch);
        savedPlayer = null;
    }
    boolean mouseOver(float[] rotation) {
        if (rotation == null || player() == null) return false;
        float yaw = yaw(), pitch = pitch(); rotation(rotation[0], rotation[1]);
        try { call(get(mc, "entityRenderer/field_71460_t"), "getMouseOver/func_78473_a", 1f); return true; }
        finally { rotation(yaw, pitch); }
    }
    /** RavenBs RotationUtils.fixRotation; its Settings.randomYawFactor defaults to zero. */
    float[] bridgeRotation(float[] requested) {
        if (requested == null) return null;
        float yaw = lastYaw(), pitch = lastPitch();
        float deltaYaw = unwrapYaw(requested[0], yaw) - yaw, deltaPitch = requested[1] - pitch;
        float sensitivity = number(get(get(mc, "gameSettings/field_71474_y"), "mouseSensitivity/field_74341_c")).floatValue();
        float factor = sensitivity * .6f + .2f;
        double step = factor * factor * factor * 1.2;
        float snappedYaw = yaw + (float) (Math.round(deltaYaw / step) * step);
        float snappedPitch = pitch + (float) (Math.round(deltaPitch / step) * step);
        return new float[]{snappedYaw, Math.max(-90, Math.min(90, snappedPitch))};
    }
    private static float unwrapYaw(float yaw, float previous) {
        return previous + (((yaw - previous + 180f) % 360f + 360f) % 360f - 180f);
    }
    void bridgeTickStart(boolean wasActive) {
        if (!wasActive || player() == null) return;
        float unwrapped = unwrapYaw(CombatMath.wrap(yaw()), lastYaw());
        set(player(), "rotationYaw/field_70177_z", unwrapped);
        set(player(), "prevRotationYaw/field_70126_B", unwrapped);
    }
    void bridgeRenderArms(boolean active) {
        if (!active || player() == null) return;
        set(player(), "renderArmYaw/field_71154_f", yaw());
        set(player(), "prevRenderArmYaw/field_71163_h", yaw());
    }
    /** All getMouseOver invocations, including render frames, use RavenBs' temporary view rotation. */
    void beginMouseOver(float[] rotation) {
        Object view = rotation == null ? null : call(mc, "getRenderViewEntity/func_175606_aa");
        MouseOverRotation saved = new MouseOverRotation(); saved.view = view;
        if (view != null) {
            saved.yaw = number(get(view, "rotationYaw/field_70177_z")).floatValue();
            saved.pitch = number(get(view, "rotationPitch/field_70125_A")).floatValue();
            saved.previousYaw = number(get(view, "prevRotationYaw/field_70126_B")).floatValue();
            saved.previousPitch = number(get(view, "prevRotationPitch/field_70127_C")).floatValue();
        }
        mouseOverRotations.push(saved);
        if (view != null) {
            set(view, "rotationYaw/field_70177_z", rotation[0]);
            set(view, "prevRotationYaw/field_70126_B", rotation[0]);
            set(view, "rotationPitch/field_70125_A", rotation[1]);
            set(view, "prevRotationPitch/field_70127_C", rotation[1]);
        }
    }
    void endMouseOver() {
        if (mouseOverRotations.isEmpty()) return;
        MouseOverRotation saved = mouseOverRotations.pop();
        if (saved.view == null) return;
        set(saved.view, "rotationYaw/field_70177_z", saved.yaw);
        set(saved.view, "prevRotationYaw/field_70126_B", saved.previousYaw);
        set(saved.view, "rotationPitch/field_70125_A", saved.pitch);
        set(saved.view, "prevRotationPitch/field_70127_C", saved.previousPitch);
    }
    void clearMouseOver() { while (!mouseOverRotations.isEmpty()) endMouseOver(); }
    private static final class MouseOverRotation {
        Object view;
        float yaw, pitch, previousYaw, previousPitch;
    }
    void hideHeldModel(Object entity, Object renderer) {
        if (local(entity)) set(call(renderer, "getMainModel/func_177087_b"), "heldItemRight/field_78120_m", 0);
    }
    void fixMovement(float serverYaw) {
        Object input = get(player(), "movementInput/field_71158_b");
        float forward = number(get(input, "moveForward/field_78900_b")).floatValue();
        float strafe = number(get(input, "moveStrafe/field_78902_a")).floatValue();
        if (forward == 0 && strafe == 0) return;
        float target = moveYaw(yaw(), forward, strafe), best = Float.POSITIVE_INFINITY, bestForward = 0, bestStrafe = 0;
        for (float f = -1; f <= 1; f++) for (float s = -1; s <= 1; s++) {
            if (f == 0 && s == 0) continue;
            float difference = Math.abs(CombatMath.wrap(target - moveYaw(serverYaw, f, s)));
            if (difference < best) { best = difference; bestForward = f; bestStrafe = s; }
        }
        float speed = Math.max(Math.abs(forward), Math.abs(strafe));
        set(input, "moveForward/field_78900_b", bestForward * speed); set(input, "moveStrafe/field_78902_a", bestStrafe * speed);
    }
    private static float moveYaw(float yaw, float forward, float strafe) {
        if (forward < 0) yaw += 180;
        float factor = forward < 0 ? -.5f : forward > 0 ? .5f : 1;
        if (strafe > 0) yaw -= 90 * factor; if (strafe < 0) yaw += 90 * factor;
        return CombatMath.wrap(yaw);
    }
}
