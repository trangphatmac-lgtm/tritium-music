package tritium.minecraft.runtime.hook;

import tritium.minecraft.runtime.util.CombatMath;
import tritium.minecraft.runtime.util.PacketBypass;

import java.util.*;

/** Minecraft 1.8.9 MCP/SRG adapter for the OpenB4 combat/network modules. */
public class ExtendedCombatAccess extends CombatAccess {
    public ExtendedCombatAccess(Object minecraft) {
        super(minecraft);
    }

    @Override
    public Object player() {
        return super.player();
    }

    @Override
    public Object world() {
        return super.world();
    }

    @Override
    public Object screen() {
        return super.screen();
    }

    @Override
    public Object held() {
        return super.held();
    }

    @Override
    public Object hit() {
        return super.hit();
    }

    @Override
    public Object settings() {
        return super.settings();
    }

    @Override
    public boolean singleplayer() {
        return super.singleplayer();
    }

    @Override
    public int entityId(Object entity) {
        return super.entityId(entity);
    }

    @Override
    public boolean dead(Object entity) {
        return super.dead(entity);
    }

    @Override
    public double coordinate(Object entity, int axis) {
        return super.coordinate(entity, axis);
    }

    @Override
    public boolean velocityForPlayer(Object packet) {
        return super.velocityForPlayer(packet);
    }

    @Override
    public double[] eyes() {
        return super.eyes();
    }

    public Object minecraft() {
        return mc;
    }

    public Object field(Object owner, String names) {
        return get(owner, names);
    }

    public void field(Object owner, String names, Object value) {
        set(owner, names, value);
    }

    public Object invoke(Object owner, String names, Object... args) {
        return call(owner, names, args);
    }

    public Object make(String name, Object... args) {
        return construct(name, args);
    }

    public Class<?> clazz(String name) {
        return type(name);
    }

    public boolean kind(Object value, String name) {
        return is(value, name);
    }

    public boolean packet(Object packet, String name) {
        for (Class<?> c = packet.getClass(); c != null; c = c.getSuperclass())
            if (c.getSimpleName().equals(name)) return true;
        return false;
    }

    public double num(Object owner, String name) {
        return ((Number) get(owner, name)).doubleValue();
    }

    public boolean flag(Object owner, String name) {
        return Boolean.TRUE.equals(get(owner, name));
    }

    public double result(Object owner, String name, Object... args) {
        return ((Number) call(owner, name, args)).doubleValue();
    }

    public boolean truth(Object owner, String name, Object... args) {
        return Boolean.TRUE.equals(call(owner, name, args));
    }

    public boolean ground() {
        return flag(player(), "onGround/field_70122_E");
    }

    public boolean sprinting() {
        return truth(player(), "isSprinting/func_70051_ag");
    }

    public boolean burning() {
        return truth(player(), "isBurning/func_70027_ad");
    }

    public boolean liquid() {
        return truth(player(), "isInWater/func_70090_H")
                || truth(player(), "isInLava/func_180799_ab");
    }

    public Object input() {
        return get(player(), "movementInput/field_71158_b");
    }

    public boolean moving() {
        return num(input(), "moveForward/field_78900_b") != 0
                || num(input(), "moveStrafe/field_78902_a") != 0;
    }

    public boolean backwards() {
        return truth(get(settings(), "keyBindBack/field_74368_y"), "isKeyDown/func_151470_d")
                && !truth(
                        get(settings(), "keyBindForward/field_74351_w"), "isKeyDown/func_151470_d");
    }

    public boolean capability(String name) {
        return flag(get(player(), "capabilities/field_71075_bZ"), name);
    }

    public boolean creative() {
        return capability("isCreativeMode/field_75098_d");
    }

    public double health() {
        return result(player(), "getHealth/func_110143_aJ");
    }

    public double[] position(Object entity) {
        return new double[] {coordinate(entity, 0), coordinate(entity, 1), coordinate(entity, 2)};
    }

    public double distanceSq(Object entity) {
        return distanceSq(position(entity));
    }

    public double distanceSq(double[] point) {
        double sum = 0;
        for (int i = 0; i < 3; i++) sum += square(coordinate(player(), i) - point[i]);
        return sum;
    }

    public double[] motion() {
        return new double[] {
            num(player(), "motionX/field_70159_w"),
            num(player(), "motionY/field_70181_x"),
            num(player(), "motionZ/field_70179_y")
        };
    }

    public boolean potion(String name) {
        return truth(player(), "isPotionActive/func_70644_a", get(type("potion.Potion"), name));
    }

    public int potionLevel(String name) {
        Object effect =
                call(
                        player(),
                        "getActivePotionEffect/func_70660_b",
                        get(type("potion.Potion"), name));
        return effect == null
                ? 0
                : ((Number) call(effect, "getAmplifier/func_76458_c")).intValue() + 1;
    }

    public boolean keyDown(String name) {
        return truth(
                type("client.settings.GameSettings"),
                "isKeyDown/func_100015_a",
                get(settings(), name));
    }

    public void restartSwing() {
        int speed = potionLevel("digSpeed/field_76422_e"),
                slow = potionLevel("digSlowdown/field_76419_f");
        int duration = speed > 0 ? 6 - speed : slow > 0 ? 6 + slow * 2 : 6;
        int progress = (int) num(player(), "swingProgressInt/field_110158_av");
        if (!flag(player(), "isSwingInProgress/field_82175_bq")
                || progress >= duration / 2
                || progress < 0) {
            set(player(), "swingProgressInt/field_110158_av", -1);
            set(player(), "isSwingInProgress/field_82175_bq", true);
        }
    }

    public boolean alive(Object entity) {
        return !dead(entity) && truth(entity, "isEntityAlive/func_70089_S");
    }

    public List<?> players() {
        return (List<?>) get(world(), "playerEntities/field_73010_i");
    }

    public Object entity(int id) {
        return call(world(), "getEntityByID/func_73045_a", id);
    }

    public boolean away(Object entity) {
        return distanceSq(entity) > 1
                && Math.abs(
                                CombatMath.wrap(
                                        CombatMath.yaw(
                                                        coordinate(entity, 0),
                                                        coordinate(entity, 2),
                                                        coordinate(player(), 0),
                                                        coordinate(player(), 2))
                                                - (float) num(entity, "rotationYaw/field_70177_z")))
                        > 90;
    }

    public double angle(Object entity) {
        return Math.abs(
                CombatMath.wrap(
                        CombatMath.yaw(
                                        coordinate(player(), 0),
                                        coordinate(player(), 2),
                                        coordinate(entity, 0),
                                        coordinate(entity, 2))
                                - yaw()));
    }

    public double[] motionTowards(Object entity) {
        double[] m = motion();
        double dx = coordinate(entity, 0) - coordinate(player(), 0),
                dz = coordinate(entity, 2) - coordinate(player(), 2);
        double ms = m[0] * m[0] + m[2] * m[2], ds = dx * dx + dz * dz;
        return new double[] {
            Math.sqrt(ms),
            ms >= 1e-8 && ds >= 1e-8 ? (m[0] * dx + m[2] * dz) / Math.sqrt(ms * ds) : 0
        };
    }

    public double heightAboveGround() {
        double[] p = position(player()), eye = eyes();
        Object hit = rayBlocks(eye, new double[] {eye[0], eye[1] - 100, eye[2]});
        if (!blockHit(hit)) return -1;
        Object pos = call(hit, "getBlockPos/func_178782_a"),
                block =
                        call(
                                call(world(), "getBlockState/func_180495_p", pos),
                                "getBlock/func_177230_c");
        if (truth(call(block, "getMaterial/func_149688_o"), "isSolid/func_76220_a")
                || kind(block, "block.BlockCarpet")
                || kind(block, "block.BlockLilyPad"))
            p[1] -= result(block, "getBlockBoundsMaxY/func_149669_A");
        return Math.max(p[1] - result(pos, "getY/func_177956_o"), 0);
    }

    public boolean landsWithin(int ticks) {
        if (ground()) return false;
        double h = heightAboveGround();
        if (h < 0) return false;
        double fallen = 0, y = motion()[1];
        for (int i = 0; i < ticks; i++) {
            if (y < 0 && (fallen -= y) >= h) return true;
            y = (y - .08) * .98;
        }
        return false;
    }

    public void strafe(double speed) {
        strafe(speed, yaw());
    }

    public void strafe(double speed, float movementYaw) {
        float yaw = movementYaw;
        double forward = num(input(), "moveForward/field_78900_b"),
                side = num(input(), "moveStrafe/field_78902_a");
        if (forward < 0) yaw += 180;
        double factor = forward < 0 ? -.5 : forward > 0 ? .5 : 1;
        if (side > 0) yaw -= 90 * factor;
        if (side < 0) yaw += 90 * factor;
        double r = (float) (yaw * ((float) Math.PI / 180));
        set(player(), "motionX/field_70159_w", -Math.sin(r) * speed);
        set(player(), "motionZ/field_70179_y", Math.cos(r) * speed);
    }

    public boolean knockback(Object packet) {
        double y = result(packet, "getMotionY/func_149410_e");
        return y > 0 && y < 4500;
    }

    public int velocity(Object packet, int axis) {
        return (int)
                result(
                        packet,
                        new String[] {
                                    "getMotionX/func_149411_d",
                                    "getMotionY/func_149410_e",
                                    "getMotionZ/func_149409_f"
                                }
                                [axis]);
    }

    public double[] explosionPosition(Object packet) {
        return new double[] {
            result(packet, "getX/func_149148_f"),
            result(packet, "getY/func_149143_g"),
            result(packet, "getZ/func_149145_h")
        };
    }

    public Object packetEntity(Object packet) {
        return call(packet, "getEntityFromWorld/func_149564_a", world());
    }

    public Object packetStack(Object packet) {
        return call(packet, "getStack/func_149574_g");
    }

    public boolean item(Object stack, String name) {
        return stack != null && kind(call(stack, "getItem/func_77973_b"), name);
    }

    public boolean itemConstant(Object stack, String name) {
        return stack != null
                && call(stack, "getItem/func_77973_b") == get(type("init.Items"), name);
    }

    public Object networkManager() {
        return call(call(mc, "getNetHandler/func_147114_u"), "getNetworkManager/func_147298_b");
    }

    public Object handler() {
        return call(networkManager(), "getNetHandler/func_150729_e");
    }

    public void sendDirect(final Object manager, final Object packet) {
        PacketBypass.run(() -> send(manager, packet));
    }

    public void process(Object packet, Object handler) {
        call(packet, "processPacket/func_148833_a", handler);
    }

    public Object ghost(Object source, boolean ownId) {
        Object ghost =
                construct(
                        "client.entity.EntityOtherPlayerMP",
                        world(),
                        call(player(), "getGameProfile/func_146103_bH"));
        if (ownId) call(ghost, "setEntityId/func_145769_d", -170);
        call(ghost, "copyLocationAndAnglesFrom/func_82149_j", source);
        return ghost;
    }

    public double[] packetPosition(Object packet) {
        return new double[] {
            result(packet, "getPositionX/func_149464_c"),
            result(packet, "getPositionY/func_149467_d"),
            result(packet, "getPositionZ/func_149472_e")
        };
    }

    public void positionGhost(Object ghost, double[] pos) {
        for (int i = 0; i < 3; i++)
            set(
                    ghost,
                    new String[] {
                                "lastTickPosX/field_70142_S",
                                "lastTickPosY/field_70137_T",
                                "lastTickPosZ/field_70136_U"
                            }
                            [i],
                    coordinate(ghost, i));
        call(ghost, "setPosition/func_70107_b", pos[0], pos[1], pos[2]);
    }

    public boolean caughtUp(Object ghost) {
        for (int i = 0; i < 3; i++)
            if (Math.abs(coordinate(ghost, i) - coordinate(player(), i)) >= .05) return false;
        return true;
    }

    public boolean thirdPerson() {
        return num(settings(), "thirdPersonView/field_74320_O") != 0;
    }

    public double[] box(Object entity) {
        Object b = call(entity, "getEntityBoundingBox/func_174813_aQ");
        String[] names = {
            "minX/field_72340_a",
            "minY/field_72338_b",
            "minZ/field_72339_c",
            "maxX/field_72336_d",
            "maxY/field_72337_e",
            "maxZ/field_72334_f"
        };
        double[] result = new double[6];
        for (int i = 0; i < 6; i++) result[i] = num(b, names[i]);
        return result;
    }

    public double rayDistanceSq(Object target, double[] box, double range) {
        if (!truth(target, "canBeCollidedWith/func_70067_L")) return -1;
        double border = result(target, "getCollisionBorderSize/func_70111_Y");
        double[] expanded = box.clone();
        for (int i = 0; i < 6; i++) expanded[i] += i < 3 ? -border : border;
        float[] rot = CombatMath.toBox(eyes(), expanded, yaw(), pitch());
        Object from = call(player(), "getPositionEyes/func_174824_e", 1f);
        double[] direction = look(rot[0], rot[1]);
        Object to =
                call(
                        from,
                        "addVector/func_72441_c",
                        direction[0] * range,
                        direction[1] * range,
                        direction[2] * range);
        Object b =
                construct(
                        "util.AxisAlignedBB",
                        expanded[0],
                        expanded[1],
                        expanded[2],
                        expanded[3],
                        expanded[4],
                        expanded[5]);
        Object hit = call(b, "calculateIntercept/func_72327_a", from, to);
        return hit == null
                ? -1
                : result(from, "squareDistanceTo/func_72436_e", get(hit, "hitVec/field_72307_f"));
    }

    public boolean consumable(Object stack) {
        if (item(stack, "item.ItemFood")) return true;
        if (!item(stack, "item.ItemPotion")) return false;
        int metadata = ((Number) call(stack, "getMetadata/func_77960_j")).intValue();
        return !truth(type("item.ItemPotion"), "isSplash/func_77831_g", metadata);
    }

    private double[] look(float yaw, float pitch) {
        float y = yaw * -((float) Math.PI / 180) - (float) Math.PI,
                p = pitch * -((float) Math.PI / 180);
        float cosine = (float) result(type("util.MathHelper"), "cos/func_76134_b", y),
                sine = (float) result(type("util.MathHelper"), "sin/func_76126_a", y);
        float horizontal = -(float) result(type("util.MathHelper"), "cos/func_76134_b", p),
                vertical = (float) result(type("util.MathHelper"), "sin/func_76126_a", p);
        return new double[] {sine * horizontal, vertical, cosine * horizontal};
    }

    public double[] offsetBox(Object target, double[] position) {
        double[] box = box(target);
        for (int i = 0; i < 6; i++) box[i] += position[i % 3] - coordinate(target, i % 3);
        return box;
    }

    public double[] serverPosition(Object entity) {
        return new double[] {
            num(entity, "serverPosX/field_70118_ct") / 32,
            num(entity, "serverPosY/field_70117_cu") / 32,
            num(entity, "serverPosZ/field_70116_cv") / 32
        };
    }

    public boolean reachBlocked() {
        Object hit = hit();
        if (hit == null) return false;
        Object pos = call(hit, "getBlockPos/func_178782_a");
        return pos != null
                && call(call(world(), "getBlockState/func_180495_p", pos), "getBlock/func_177230_c")
                        != get(type("init.Blocks"), "air/field_150350_a");
    }

    public boolean pick(double range) {
        Object viewer = call(mc, "getRenderViewEntity/func_175606_aa");
        if (viewer == null) return false;
        Object eyes = call(viewer, "getPositionEyes/func_174824_e", 1f);
        double[] dir = look(yaw(), pitch());
        Object end =
                call(
                        eyes,
                        "addVector/func_72441_c",
                        dir[0] * range,
                        dir[1] * range,
                        dir[2] * range);
        Object raw = call(viewer, "getEntityBoundingBox/func_174813_aQ");
        Object broad =
                call(
                        call(
                                raw,
                                "addCoord/func_72321_a",
                                dir[0] * range,
                                dir[1] * range,
                                dir[2] * range),
                        "expand/func_72314_b",
                        1d,
                        1d,
                        1d);
        List<?> candidates =
                (List<?>)
                        call(
                                world(),
                                "getEntitiesWithinAABBExcludingEntity/func_72839_b",
                                viewer,
                                broad);
        Object found = null, point = null;
        double best = range;
        for (Object e : candidates) {
            if (!truth(e, "canBeCollidedWith/func_70067_L")) continue;
            double border = result(e, "getCollisionBorderSize/func_70111_Y");
            Object b =
                    call(
                            call(e, "getEntityBoundingBox/func_174813_aQ"),
                            "expand/func_72314_b",
                            border,
                            border,
                            border);
            Object hit = call(b, "calculateIntercept/func_72327_a", eyes, end);
            if (truth(b, "isVecInside/func_72318_a", eyes)) {
                if (best >= 0) {
                    found = e;
                    point = hit == null ? eyes : get(hit, "hitVec/field_72307_f");
                    best = 0;
                }
                continue;
            }
            if (hit == null) continue;
            Object hitVec = get(hit, "hitVec/field_72307_f");
            double distance = result(eyes, "distanceTo/func_72438_d", hitVec);
            if (distance >= best && best != 0) continue;
            if (e == get(viewer, "ridingEntity/field_70154_o")) {
                if (best == 0) {
                    found = e;
                    point = hitVec;
                }
            } else {
                found = e;
                point = hitVec;
                best = distance;
            }
        }
        if (best < range
                && !kind(found, "entity.EntityLivingBase")
                && !kind(found, "entity.item.EntityItemFrame")) found = null;
        if (found == null || point == null) return false;
        set(
                mc,
                "objectMouseOver/field_71476_x",
                construct("util.MovingObjectPosition", found, point));
        set(mc, "pointedEntity/field_147125_j", found);
        return true;
    }
}
