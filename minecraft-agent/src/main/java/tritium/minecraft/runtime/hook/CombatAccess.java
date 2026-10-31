package tritium.minecraft.runtime.hook;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import tritium.minecraft.runtime.util.GameReflection;
import tritium.minecraft.runtime.util.TeamChecks;
import tritium.minecraft.runtime.util.CombatMath;
import java.util.*;

/** Cached MCP/SRG access. No Minecraft or Forge classes are linked into the agent JAR. */
public class CombatAccess extends GameReflection {
    final Object mc;
    private TeamChecks teams;
    private AntiBotRuntime antiBot;
    public void antiBot(AntiBotRuntime antiBot) { this.antiBot=antiBot; }
    public boolean bot(Object entity) { return antiBot!=null && antiBot.bot(entity); }
    public boolean ignored(Object entity) { return antiBot!=null && antiBot.ignored(entity); }
    CombatAccess(Object minecraft) {
        super(minecraft == null ? CombatAccess.class.getClassLoader() : minecraft.getClass().getClassLoader());
        mc = minecraft;
    }

    Object player() { return get(mc, "thePlayer/field_71439_g"); }
    Object world() { return get(mc, "theWorld/field_71441_e"); }
    Object screen() { return get(mc, "currentScreen/field_71462_r"); }
    public boolean inWorld() { return player() != null && world() != null; }
    public boolean focused() { return inWorld() && screen() == null && bool(get(mc, "inGameHasFocus/field_71415_G")) && Display.isActive(); }
    public boolean mouse(int button) { return Mouse.isCreated() && Mouse.isButtonDown(button); }
    public boolean shift() { return Keyboard.isCreated() && (Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54)); }
    public boolean inventory() { return is(screen(), "client.gui.inventory.GuiInventory") || is(screen(), "client.gui.inventory.GuiChest"); }
    boolean singleplayer() { return bool(call(mc, "isSingleplayer/func_71356_B")); }
    public boolean sword() { return swordStack(held()); }
    public boolean weapon() { return sword(); } // Raven Settings: all "Extra weapons" default to false.
    public boolean blockItem() { Object stack = held(); return stack != null && is(call(stack, "getItem/func_77973_b"), "item.ItemBlock"); }
    Object held() { return call(player(), "getHeldItem/func_70694_bm"); }
    boolean swordStack(Object stack) { return stack != null && is(call(stack, "getItem/func_77973_b"), "item.ItemSword"); }
    public boolean eating() { return bool(call(player(), "isEating/func_70113_ah")); }
    public boolean blocking() {
        // AutoClicker checks actual item use; the presentation-only fake-block hook must not pause it.
        if (!bool(call(player(), "isUsingItem/func_71039_bw"))) return false;
        Object stack = call(player(), "getItemInUse/func_71011_bu");
        return stack != null && "BLOCK".equals(String.valueOf(call(stack, "getItemUseAction/func_77975_n")));
    }
    public int hurtTime() { return integer(get(player(), "hurtTime/field_70737_aN")); }
    public boolean allowEdit() { return bool(get(get(player(), "capabilities/field_71075_bZ"), "allowEdit/field_75099_e")); }
    double reach() { return bool(call(get(mc, "playerController/field_71442_b"), "extendedReach/func_78749_i")) ? 6 : 3; }
    public float yaw() { return number(get(player(), "rotationYaw/field_70177_z")).floatValue(); }
    public float pitch() { return number(get(player(), "rotationPitch/field_70125_A")).floatValue(); }
    public void rotation(float yaw, float pitch) { set(player(), "rotationYaw/field_70177_z", yaw); set(player(), "rotationPitch/field_70125_A", pitch); }
    public float sensitivity() { return number(get(settings(), "mouseSensitivity/field_74341_c")).floatValue(); }
    Object settings() { return get(mc, "gameSettings/field_71474_y"); }
    Object binding(int button) { return get(settings(), button == 0 ? "keyBindAttack/field_74312_F" : "keyBindUseItem/field_74313_G"); }
    public int key(int button) { return integer(call(binding(button), "getKeyCode/func_151463_i")); }
    public void key(int button, boolean down, boolean tick) {
        int key = key(button);
        call(type("client.settings.KeyBinding"), "setKeyBindState/func_74510_a", key, down);
        if (tick) call(type("client.settings.KeyBinding"), "onTick/func_74507_a", key);
    }
    public void restoreKey(int button) {
        int code = key(button);
        boolean down = code < 0 ? mouse(code + 100) : code > 0 && Keyboard.isCreated() && Keyboard.isKeyDown(code);
        key(button, down, false);
    }
    public boolean mouseEvent(int button, boolean down) {
        // Forge is optional (Lunar/MCP). Keep physical input untouched so holding a mouse
        // button remains distinguishable from the synthetic release between clicks.
        Class<?> eventType;
        try { eventType = Class.forName("net.minecraftforge.client.event.MouseEvent", false, loader); }
        catch (ClassNotFoundException noForge) { return true; }
        try {
            Object event = eventType.newInstance(); set(event, "button", button); set(event, "buttonstate", down);
            Object bus = get(Class.forName("net.minecraftforge.common.MinecraftForge", false, loader), "EVENT_BUS");
            return !bool(call(bus, "post", event));
        } catch (ReflectiveOperationException e) { throw failure(e); }
    }
    public void clickScreen() {
        Object gui = screen(); if (gui == null) return;
        int width = integer(get(gui, "width/field_146294_l")), height = integer(get(gui, "height/field_146295_m"));
        int x = Mouse.getX() * width / Math.max(1, integer(get(mc, "displayWidth/field_71443_c")));
        int y = height - Mouse.getY() * height / Math.max(1, integer(get(mc, "displayHeight/field_71440_d"))) - 1;
        call(gui, "mouseClicked/func_73864_a", x, y, 0);
    }
    Object hit() { return get(mc, "objectMouseOver/field_71476_x"); }
    public Object hitEntity() { Object hit = hit(); return hit == null ? null : get(hit, "entityHit/field_72308_g"); }
    public boolean blockHit() { return blockHit(hit()); }
    public boolean blockHit(Object hit) { return hit != null && "BLOCK".equals(String.valueOf(get(hit, "typeOfHit/field_72313_a"))); }
    Object hitBlock() {
        Object hit = hit(); if (!blockHit(hit)) return null;
        Object pos = call(hit, "getBlockPos/func_178782_a");
        return pos == null ? null : call(call(world(), "getBlockState/func_180495_p", pos), "getBlock/func_177230_c");
    }
    public boolean breakableBlock() { Object b = hitBlock(); return b != null && !is(b, "block.BlockAir") && !is(b, "block.BlockLiquid"); }
    public boolean interactable() {
        Object b = hitBlock();
        if (b == null) return false;
        for (String name : new String[]{"BlockContainer", "BlockWorkbench", "BlockAnvil", "BlockBed", "BlockLever", "BlockTrapDoor", "BlockFence", "BlockFenceGate"})
            if (is(b, "block." + name)) return true;
        return is(b, "block.BlockDoor") && b != get(type("init.Blocks"), "iron_door/field_150454_av");
    }
    public boolean lookingAtBot() { Object e = hitEntity(); return e != null && (antiBot!=null ? antiBot.bot(e,false) : !is(e, "entity.player.EntityPlayer") || dead(e) || entityId(e) == -170); }
    int entityId(Object e) { return integer(call(e, "getEntityId/func_145782_y")); }
    boolean dead(Object e) { return bool(get(e, "isDead/field_70128_L")); }
    public boolean targetMatches(Object e, boolean playersOnly) { return e != null && (is(e, "entity.player.EntityPlayer") || !playersOnly && mob(e)); }
    boolean mob(Object e) { return is(e, "entity.monster.EntityMob") || is(e, "entity.monster.EntityGolem") || is(e, "entity.monster.EntitySlime"); }
    public List<Target> targets() {
        List<Target> result = new ArrayList<Target>();
        if (teams == null) teams = new TeamChecks(mc);
        teams.refresh(player(), world());
        for (Object e : (List<?>) get(world(), "loadedEntityList/field_72996_f")) {
            if (e == player() || !is(e, "entity.EntityLivingBase") || dead(e) || integer(get(e, "deathTime/field_70725_aQ")) != 0 || entityId(e) == -170) continue;
            Target t = new Target(); t.entity = e; t.player = is(e, "entity.player.EntityPlayer"); t.mob = mob(e);
            t.x = coordinate(e, 0); t.y = coordinate(e, 1); t.z = coordinate(e, 2);
            t.distanceSq = square(t.x - coordinate(player(), 0)) + square(t.y - coordinate(player(), 1)) + square(t.z - coordinate(player(), 2));
            t.angle = Math.abs(CombatMath.wrap(CombatMath.yaw(coordinate(player(), 0), coordinate(player(), 2), t.x, t.z) - yaw()));
            t.invisible = bool(call(e, "isInvisible/func_82150_aj"));
            if (t.invisible) for (int i = 0; i <= 4; i++) if (call(e, "getEquipmentInSlot/func_71124_b", i) != null) { t.invisible = false; break; }
            t.teammate = teams.sameTeam(e);
            float entityYaw = number(get(e, "rotationYaw/field_70177_z")).floatValue();
            t.away = t.distanceSq > 1 && Math.abs(CombatMath.wrap(CombatMath.yaw(t.x, t.z, coordinate(player(), 0), coordinate(player(), 2)) - entityYaw)) > 90;
            result.add(t);
        }
        return result;
    }
    double coordinate(Object e, int axis) { return number(get(e, new String[]{"posX/field_70165_t", "posY/field_70163_u", "posZ/field_70161_v"}[axis])).doubleValue(); }
    double[] eyes() { Object p = player(); return new double[]{coordinate(p, 0), coordinate(p, 1) + number(call(p, "getEyeHeight/func_70047_e")).doubleValue(), coordinate(p, 2)}; }
    double[] box(Target t) {
        Object box = call(t.entity, "getEntityBoundingBox/func_174813_aQ");
        double border = number(call(t.entity, "getCollisionBorderSize/func_70111_Y")).doubleValue();
        String[] names = {"minX/field_72340_a", "minY/field_72338_b", "minZ/field_72339_c", "maxX/field_72336_d", "maxY/field_72337_e", "maxZ/field_72334_f"};
        double[] b = new double[6]; for (int i = 0; i < 6; i++) b[i] = number(get(box, names[i])).doubleValue() + (i < 3 ? -border : border);
        return b;
    }
    public float[] aimPoint(Target t) { return CombatMath.toBox(eyes(), box(t), yaw(), pitch()); }
    public boolean rayHits(Target t, double range) { float[] r = aimPoint(t); return ray(t, range, r[0], r[1]) != null; }
    double[] ray(Target t, double range, float yaw, float pitch) {
        if (!bool(call(t.entity, "canBeCollidedWith/func_70067_L"))) return null;
        return CombatMath.intercept(eyes(), box(t), yaw, pitch, range);
    }
    public void mouseOver(Target t, float yaw, float pitch) {
        double[] point = ray(t, reach(), yaw, pitch);
        Object hit = point == null ? null : construct("util.MovingObjectPosition", t.entity, vec(point));
        set(mc, "objectMouseOver/field_71476_x", hit);
        set(mc, "pointedEntity/field_147125_j", point == null ? null : t.entity);
    }
    Object vec(double[] p) { return construct("util.Vec3", p[0], p[1], p[2]); }
    Object rayBlocks(double[] from, double[] to) { return call(world(), "rayTraceBlocks/func_147447_a", vec(from), vec(to), false, false, false); }
    public boolean breakingRay() { double[] from = eyes(), d = CombatMath.direction(yaw(), pitch()); double reach = reach();
        return blockHit(rayBlocks(from, new double[]{from[0] + d[0]*reach, from[1] + d[1]*reach, from[2] + d[2]*reach})); }
    public boolean visible(Target t) {
        double[] eyes = eyes(); double h = number(call(t.entity, "getEyeHeight/func_70047_e")).doubleValue();
        for (double[] p : new double[][]{{t.x+.3,t.y+h-.2,t.z},{t.x-.3,t.y+h-.2,t.z},{t.x,t.y+h-.2,t.z+.3},{t.x,t.y+h-.2,t.z-.3}})
            if (rayBlocks(eyes, p) == null) return true;
        for (double y = h + .2; y > 0; y -= .2) if (rayBlocks(eyes, new double[]{t.x,t.y+y,t.z}) == null) return true;
        return false;
    }
    public void send(Object manager, Object packet) { call(manager, "sendPacket/func_179290_a", packet); }
    boolean velocityForPlayer(Object packet) { return inWorld() && entityId(player()) == integer(call(packet, "getEntityID/func_149412_c")); }
    boolean knockback(Object packet) { int y=integer(call(packet,"getMotionY/func_149410_e")); return y>0 && y<4500; }
    public boolean attackPacket(Object packet) { return is(packet, "network.play.client.C02PacketUseEntity") && "ATTACK".equals(String.valueOf(call(packet, "getAction/func_149565_c"))); }
    public boolean placement(Object packet) { return is(packet, "network.play.client.C08PacketPlayerBlockPlacement"); }
    public boolean swordPlacement(Object packet) { return placement(packet) && swordStack(call(packet, "getStack/func_149574_g")); }
    public boolean blockPlacement(Object packet) { return swordPlacement(packet) && integer(call(packet, "getPlacedBlockDirection/func_149568_f")) == 255; }

    public static final class Target {
        public Object entity;
        public boolean player, mob, invisible, teammate, away;
        public double x, y, z, distanceSq, angle;
    }
}
