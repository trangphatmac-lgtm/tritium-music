package tritium.minecraft.runtime.hook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tritium.minecraft.protocol.*;
import tritium.minecraft.runtime.combat.JumpResetModule;
import tritium.minecraft.runtime.util.FriendRegistry;
import tritium.minecraft.runtime.util.InboundReplay;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Packet/state fixtures exercise the runtime without linking a second copy of Minecraft. */
class ExtendedCombatRuntimeTest {
    static final class Fixture implements AutoCloseable {
        final FakeGame game = new FakeGame();
        final AtomicLong time = new AtomicLong(10000);
        final ExtendedCombatRuntime runtime =
                new ExtendedCombatRuntime(game, new Random(7), time::get, false);
        final HudConfig config = new HudConfig();

        Fixture() {
            config.normalize();
            config.hidden = true;
            game.runtime = runtime;
            runtime.configure(config);
        }

        void enable(String... ids) {
            for (String id : ids) config.find(id).enabled = true;
            runtime.configure(config);
        }

        void number(String id, String key, double v) {
            config.find(id).openB4.numbers.put(key, v);
        }

        void toggle(String id, String key, boolean v) {
            config.find(id).openB4.toggles.put(key, v);
        }

        public void close() {
            runtime.close();
        }
    }

    @Test
    void horizontalZeroRetainsXZAndAppliesOnlyScaledVertical() {
        try (Fixture f = new Fixture()) {
            f.number("antiKnockback", "Horizontal", 0);
            f.number("antiKnockback", "Vertical", 50);
            f.enable("antiKnockback");
            f.game.player.motionX = .7;
            f.game.player.motionZ = -.2;
            assertTrue(f.runtime.receive(f.game, new S12PacketEntityVelocity(1, 3200, 3200, 1600)));
            assertEquals(.2, f.game.player.motionY, 1e-10);
            assertEquals(.7, f.game.player.motionX);
            assertEquals(-.2, f.game.player.motionZ);
        }
    }

    @Test
    void velocityScalingTruncatesLikeTheOriginalAndDoesNotTouchOtherEntities() {
        try (Fixture f = new Fixture()) {
            f.number("antiKnockback", "Horizontal", 37);
            f.number("antiKnockback", "Vertical", 25);
            f.enable("antiKnockback");
            S12PacketEntityVelocity own = new S12PacketEntityVelocity(1, 1001, 3101, -1001),
                    other = new S12PacketEntityVelocity(2, 1001, 3101, -1001);
            assertFalse(f.runtime.receive(f.game, own));
            assertEquals(370, own.motionX);
            assertEquals(-370, own.motionZ);
            assertEquals(775, own.motionY);
            assertFalse(f.runtime.receive(f.game, other));
            assertEquals(1001, other.motionX);
        }
    }

    @Test
    void delayedVelocityReplaysOnceAndFlushesFollowingPacketsInOrder() {
        try (Fixture f = new Fixture()) {
            f.number("antiKnockback", "Horizontal", 50);
            f.number("antiKnockback", "Delay", 100);
            f.enable("antiKnockback");
            f.runtime.tickEnd();
            S12PacketEntityVelocity velocity = new S12PacketEntityVelocity(1, 3200, 3200, 1600);
            Object transaction = new S32PacketConfirmTransaction();
            assertTrue(f.runtime.receive(f.game, velocity));
            assertTrue(f.runtime.receive(f.game, transaction));
            assertTrue(f.runtime.knockbackDelaying());
            f.runtime.tickEnd();
            assertTrue(f.game.received.isEmpty());
            f.runtime.tickEnd();
            assertTrue(f.game.received.isEmpty());
            f.runtime.tickEnd();
            assertEquals(Arrays.asList(velocity, transaction), f.game.received);
            assertEquals(1600, velocity.motionX, "replay must not scale twice");
            assertFalse(f.runtime.knockbackDelaying());
        }
    }

    @Test
    void disablingOrChangingWorldDoesNotReplayOldInboundIntoTheNewPlayer() {
        try (Fixture f = new Fixture()) {
            f.number("antiKnockback", "Delay", 100);
            f.enable("antiKnockback");
            f.runtime.tickEnd();
            assertTrue(f.runtime.receive(f.game, new S12PacketEntityVelocity(1, 1, 3200, 1)));
            f.game.world = new Object();
            f.game.player = new Player(5);
            f.runtime.configure(f.config);
            f.runtime.tickEnd();
            f.runtime.tickEnd();
            f.runtime.tickEnd();
            assertTrue(f.game.received.isEmpty());
        }
    }

    @Test
    void blinkCancellationOccursAfterAntiKnockbackAndBeforeJumpReset() {
        try (Fixture f = new Fixture()) {
            f.toggle("blink", "Show initial position", false);
            f.toggle("blink", "Cancel knockback", true);
            f.enable("blink", "jumpReset");
            assertTrue(f.runtime.receive(f.game, new S12PacketEntityVelocity(1, 2000, 3200, 0)));
            assertFalse(f.runtime.jumpPending());
        }
    }

    @Test
    void hardResetQueuesEveryInboundThenReleasesAtTickStartOnLanding() {
        try (Fixture f = new Fixture()) {
            f.toggle("jumpReset", "Hard reset", true);
            f.game.player.onGround = false;
            f.game.targets.add(new Player(2));
            f.enable("jumpReset");
            Object kb = new S12PacketEntityVelocity(1, 2200, 3200, 0),
                    transaction = new S32PacketConfirmTransaction();
            assertTrue(f.runtime.receive(f.game, kb));
            assertTrue(f.runtime.receive(f.game, transaction));
            assertFalse(f.runtime.jumpPending());
            f.game.player.onGround = true;
            f.runtime.tickStart();
            assertEquals(Arrays.asList(kb, transaction), f.game.received);
            assertTrue(f.runtime.jumpPending());
        }
    }

    @Test
    void hardResetTimeoutAndTeleportReleaseTheOriginalOrder() {
        try (Fixture f = new Fixture()) {
            f.toggle("jumpReset", "Hard reset", true);
            f.number("jumpReset", "Hard reset max delay", 100);
            f.game.player.onGround = false;
            f.game.targets.add(new Player(2));
            f.enable("jumpReset");
            Object kb = new S12PacketEntityVelocity(1, 2000, 3200, 0);
            assertTrue(f.runtime.receive(f.game, kb));
            f.time.addAndGet(100);
            f.runtime.tickStart();
            assertEquals(Collections.singletonList(kb), f.game.received);
            f.game.received.clear();
            Object second = new S12PacketEntityVelocity(1, 2000, 3200, 0);
            assertTrue(f.runtime.receive(f.game, second));
            assertFalse(f.runtime.receive(f.game, new S08PacketPlayerPosLook()));
            assertEquals(Collections.singletonList(second), f.game.received);
        }
    }

    @Test
    void aimedJumpCancelsAtLeastThreePercentAndRestoresVisibleYaw() {
        try (Fixture f = new Fixture()) {
            f.number("jumpReset", "Chance", 100);
            f.enable("jumpReset");
            f.runtime.receive(f.game, new S12PacketEntityVelocity(1, 2400, 3200, 0));
            f.runtime.preUpdate();
            assertTrue(f.runtime.hasRotation());
            assertEquals(90, f.runtime.rotationYaw(), 1e-5);
            f.runtime.updateEntityActionState();
            assertTrue(f.game.player.jumping);
            f.runtime.preMotion();
            assertEquals(90, f.game.player.rotationYaw, 1e-5);
            f.runtime.postMotion();
            assertEquals(0, f.game.player.rotationYaw);
            assertTrue(JumpResetModule.helps(.3, 0, 90));
            assertFalse(JumpResetModule.helps(.3, 0, -90));
            assertFalse(JumpResetModule.helps(0, 0, 90));
        }
    }

    @Test
    void attackDuringAimJumpTickPreventsSilentYawChange() {
        try (Fixture f = new Fixture()) {
            f.enable("jumpReset");
            f.runtime.receive(f.game, new S12PacketEntityVelocity(1, 2400, 3200, 0));
            f.runtime.attack(new Player(2));
            f.runtime.preUpdate();
            assertFalse(f.runtime.hasRotation());
        }
    }

    @Test
    void hitSelectUsesConfirmedComboAndManualAutoClickerGates() {
        try (Fixture f = new Fixture()) {
            Player target = new Player(2);
            target.hurtTime = 10;
            f.game.targets.add(target);
            f.game.hit = target;
            f.enable("hitSelect");
            assertFalse(f.runtime.mouse(true, 0));
            f.runtime.sendBeforeLag(f.game, new C02PacketUseEntity(target));
            f.runtime.receive(f.game, new S19PacketEntityStatus(target));
            assertTrue(f.runtime.mouse(true, 0));
            f.toggle("hitSelect", "AutoClicker", false);
            f.runtime.autoClicking(true);
            assertFalse(f.runtime.mouse(true, 0));
            f.runtime.autoClicking(false);
            f.toggle("hitSelect", "Manual", false);
            assertFalse(f.runtime.mouse(true, 0));
        }
    }

    @Test
    void forceFirstHitBypassesOnceAndResetsAfterTenSeconds() {
        try (Fixture f = new Fixture()) {
            f.number("hitSelect", "Only in combo", 0);
            f.toggle("hitSelect", "Force first hit", true);
            Player target = new Player(2);
            target.hurtTime = 10;
            f.game.hit = target;
            f.enable("hitSelect");
            assertFalse(f.runtime.mouse(true, 0));
            assertTrue(f.runtime.mouse(true, 0));
            f.time.addAndGet(10001);
            assertFalse(f.runtime.mouse(true, 0));
        }
    }

    @Test
    void wTapAppliesBothDelaysAndSilentResetIsRestoredAfterMotion() {
        try (Fixture f = new Fixture()) {
            f.toggle("wTap", "Silent", true);
            f.enable("wTap");
            f.runtime.attack(new Player(2));
            f.time.addAndGet(129);
            f.runtime.preUpdate();
            assertFalse(f.runtime.silentSprintReset());
            f.time.incrementAndGet();
            f.runtime.preUpdate();
            assertTrue(f.runtime.silentSprintReset());
            f.runtime.preMotion();
            assertFalse(f.game.player.sprinting);
            assertFalse(f.runtime.silentSprintReset());
            f.runtime.postMotion();
            assertTrue(f.game.player.sprinting);
            f.runtime.attack(new Player(2));
            f.time.addAndGet(130);
            f.runtime.preUpdate();
            assertFalse(f.runtime.silentSprintReset(), "cooldown prevents a second queued reset");
        }
    }

    @Test
    void fakeLagDefaultPulseDoesNotFlushEarlyAndBlinkOwnsTheFinalQueue() {
        try (Fixture f = new Fixture()) {
            f.number("fakeLag", "Outbound delay", 200);
            f.toggle("blink", "Show initial position", false);
            f.enable("fakeLag", "blink");
            Object packet = new C03PacketPlayer();
            assertTrue(f.runtime.sendLag(f.game, packet));
            f.runtime.networkPulse();
            f.time.addAndGet(130);
            f.runtime.networkPulse();
            assertTrue(f.game.sent.isEmpty());
            f.time.addAndGet(70);
            f.runtime.networkPulse();
            assertTrue(f.game.sent.isEmpty(), "FakeLag release feeds Blink");
            f.runtime.disable("blink");
            assertEquals(Collections.singletonList(packet), f.game.sent);
        }
    }

    @Test
    void disablingFakeLagOnAttackFlushesTheAttackAndPersistsItsDisabledState() {
        try (Fixture f = new Fixture()) {
            f.toggle("fakeLag", "Disable on attack", true);
            f.enable("fakeLag");
            Object move = new C03PacketPlayer(), attack = new C02PacketUseEntity(new Player(2));
            assertTrue(f.runtime.sendLag(f.game, move));
            assertTrue(f.runtime.sendLag(f.game, attack));
            assertEquals(Arrays.asList(move, attack), f.game.sent);
            assertFalse(f.config.find("fakeLag").enabled);
            assertEquals(Collections.singletonList("fakeLag"), f.runtime.consumeDisabledIds());
        }
    }

    @Test
    void changingFakeLagDelayDisablesInsteadOfReschedulingOldPackets() {
        try (Fixture f = new Fixture()) {
            f.enable("fakeLag");
            Object packet = new C03PacketPlayer();
            f.runtime.sendLag(f.game, packet);
            f.number("fakeLag", "Outbound delay", 200);
            f.runtime.networkPulse();
            assertFalse(f.runtime.enabled("fakeLag"));
            assertEquals(Collections.singletonList(packet), f.game.sent);
        }
    }

    @Test
    void lagRangeMinimumDelaysAreDisabledAndEnemiesAreCaseInsensitive() {
        try (Fixture f = new Fixture()) {
            f.number("lagRange", "Packet delay (close)", 10);
            f.number("lagRange", "Packet delay (far)", 10);
            f.enable("lagRange");
            f.runtime.preUpdate();
            assertNull(f.runtime.target());
            FriendRegistry registry = new FriendRegistry();
            registry.configure(new String[] {"Teammate"}, new String[] {"Target"});
            assertTrue(registry.isFriend("TEAMMATE"));
            assertTrue(registry.isEnemy("target"));
            assertFalse(registry.isEnemy("someone"));
        }
    }

    @Test
    void backtrackStartsOnlyForRetreatingTargetsAndDelaysInTickOrder() {
        try (Fixture f = new Fixture()) {
            Player target = new Player(2);
            target.posZ = 3.2;
            f.game.targets.add(target);
            f.enable("backtrack");
            f.runtime.tickEnd();
            Object move = new S15PacketEntityRelMove(target, 16),
                    transaction = new S32PacketConfirmTransaction();
            assertTrue(f.runtime.receive(f.game, move));
            assertTrue(f.runtime.receive(f.game, transaction));
            for (int i = 0; i < 3; i++) f.runtime.tickEnd();
            assertTrue(f.game.received.isEmpty());
            f.runtime.tickEnd();
            assertEquals(Arrays.asList(move, transaction), f.game.received);
        }
    }

    @Test
    void backtrackMovementOnlyBypassesOtherPacketsAndFlushesOnDamage() {
        try (Fixture f = new Fixture()) {
            f.toggle("backtrack", "Only movement", true);
            Player target = new Player(2);
            target.posZ = 3.2;
            f.game.targets.add(target);
            f.enable("backtrack");
            f.runtime.tickEnd();
            Object move = new S15PacketEntityRelMove(target, 16);
            assertTrue(f.runtime.receive(f.game, move));
            assertFalse(f.runtime.receive(f.game, new S32PacketConfirmTransaction()));
            assertFalse(f.runtime.receive(f.game, new S12PacketEntityVelocity(1, 2000, 3200, 0)));
            assertEquals(Collections.singletonList(move), f.game.received);
        }
    }

    @Test
    void lagRangeFlushesAtTheMovementAfterAnAttack() {
        try (Fixture f = new Fixture()) {
            Player target = new Player(2);
            target.posZ = 4;
            f.game.targets.add(target);
            f.game.player.motionZ = .2;
            f.enable("lagRange");
            f.runtime.preUpdate();
            assertSame(target, f.runtime.target());
            Object first = new C03PacketPlayer(),
                    attack = new C02PacketUseEntity(target),
                    next = new C03PacketPlayer();
            assertTrue(f.runtime.sendLag(f.game, first));
            assertTrue(f.runtime.sendLag(f.game, attack));
            assertTrue(f.game.sent.isEmpty());
            assertTrue(f.runtime.sendLag(f.game, next));
            assertEquals(Arrays.asList(first, attack, next), f.game.sent);
            assertNull(f.runtime.target());
        }
    }

    @Test
    void scaffoldStopsExistingLagRangeAndFlushesItsPackets() {
        try (Fixture f = new Fixture()) {
            Player target = new Player(2);
            target.posZ = 4;
            f.game.targets.add(target);
            f.game.player.motionZ = .2;
            f.enable("lagRange");
            f.runtime.preUpdate();
            Object packet = new C03PacketPlayer();
            assertTrue(f.runtime.sendLag(f.game, packet));
            f.runtime.scaffolding(true);
            f.runtime.preUpdate();
            assertNull(f.runtime.target());
            assertEquals(Collections.singletonList(packet), f.game.sent);
        }
    }

    @Test
    void reachUsesClicksWithoutAutoclickerAndTickPickingOnlyWhileAutoclickingLeft() {
        try (Fixture f = new Fixture()) {
            f.enable("reach");
            assertFalse(f.runtime.mouseOver());
            assertFalse(f.runtime.mouse(true, 1));
            assertTrue(f.game.pickedRange >= 3.1 && f.game.pickedRange <= 3.3);
            f.game.pickedRange = 0;
            f.runtime.autoClicking(true);
            assertFalse(f.runtime.mouseOver());
            f.game.leftMouse = true;
            assertTrue(f.runtime.mouseOver());
            assertTrue(f.game.pickedRange >= 3.1);
            f.game.reachBlocked = true;
            assertFalse(f.runtime.mouseOver());
            f.toggle("reach", "Hit through blocks", true);
            assertTrue(f.runtime.mouseOver());
        }
    }

    @Test
    void jumpRotationKeepsHigherPriorityPitchAndFallsBackToOtherMovementYaw() {
        try (Fixture f = new Fixture()) {
            f.enable("jumpReset");
            f.runtime.movementYawFallback(75f);
            assertEquals(75, f.runtime.movementYaw());
            f.runtime.receive(f.game, new S12PacketEntityVelocity(1, 2400, 3200, 0));
            f.runtime.rotationBase(10, 25);
            f.runtime.preUpdate();
            f.game.player.rotationPitch = -40;
            f.runtime.preMotion();
            assertEquals(25, f.game.player.rotationPitch);
            f.runtime.postMotion();
            assertEquals(-40, f.game.player.rotationPitch);
        }
    }

    @Test
    void blinkRetainsTheSuppliedSourcesConditionalCounterBehavior() {
        try (Fixture f = new Fixture()) {
            f.toggle("blink", "Show initial position", false);
            f.number("blink", "Auto-disable", 1);
            f.enable("blink");
            for (int i = 0; i < 50; i++) f.runtime.packetQueueTick();
            assertTrue(
                    f.runtime.enabled("blink"),
                    "OpenB4 only increments ticks in its default auto-disable branch");
            f.number("blink", "Auto-disable", 0);
            for (int i = 0; i < 21; i++) f.runtime.packetQueueTick();
            f.number("blink", "Auto-disable", 1);
            f.runtime.packetQueueTick();
            assertFalse(f.runtime.enabled("blink"));
            assertEquals(Collections.singletonList("blink"), f.runtime.consumeDisabledIds());
        }
    }

    @Test
    void sharedAutoBlockAndBlinkQueueOnlyFlushesAfterTheLastHolderCloses() {
        try (Fixture f = new Fixture()) {
            f.toggle("blink", "Show initial position", false);
            f.enable("blink");
            f.runtime.openAutoBlockQueue();
            Object first = new C03PacketPlayer();
            assertTrue(f.runtime.sendLag(f.game, first));
            f.runtime.closeAutoBlockQueue();
            assertFalse(f.runtime.autoBlockQueueActive());
            assertTrue(f.game.sent.isEmpty());
            f.runtime.disable("blink");
            assertEquals(Collections.singletonList(first), f.game.sent);
            f.enable("blink");
            f.runtime.openAutoBlockQueue();
            Object second = new C03PacketPlayer();
            assertTrue(f.runtime.sendLag(f.game, second));
            f.runtime.disable("blink");
            assertTrue(f.runtime.autoBlockQueueActive());
            assertEquals(Collections.singletonList(first), f.game.sent);
            f.runtime.closeAutoBlockQueue();
            assertEquals(Arrays.asList(first, second), f.game.sent);
        }
    }

    @Test
    void autoBlockHoldRunsAfterFakeLagWithoutAddingAnExtraDelay() {
        try (Fixture f = new Fixture()) {
            f.enable("fakeLag");
            f.runtime.openAutoBlockQueue();
            Object packet = new C03PacketPlayer();
            assertTrue(f.runtime.sendLag(f.game, packet));
            f.time.addAndGet(50);
            f.runtime.closeAutoBlockQueue();
            assertTrue(f.game.sent.isEmpty());
            f.time.addAndGet(50);
            f.runtime.networkPulse();
            assertEquals(
                    Collections.singletonList(packet),
                    f.game.sent,
                    "expires 100ms from send, not 100ms after AutoBlock releases");
            f.runtime.openAutoBlockQueue();
            Object next = new C03PacketPlayer();
            assertTrue(f.runtime.sendLag(f.game, next));
            f.time.addAndGet(100);
            f.runtime.networkPulse();
            assertEquals(Collections.singletonList(packet), f.game.sent);
            f.runtime.closeAutoBlockQueue();
            assertEquals(Arrays.asList(packet, next), f.game.sent);
        }
    }

    @Test
    void worldReplacementDiscardsBothOwnersSharedOutboundQueue() {
        try (Fixture f = new Fixture()) {
            f.toggle("blink", "Show initial position", false);
            f.enable("blink");
            f.runtime.openAutoBlockQueue();
            assertTrue(f.runtime.sendLag(f.game, new C03PacketPlayer()));
            f.game.world = new Object();
            f.game.player = new Player(7);
            f.runtime.configure(f.config);
            assertFalse(f.runtime.autoBlockQueueActive());
            f.runtime.disable("blink");
            assertTrue(f.game.sent.isEmpty());
        }
    }

    @Test
    void outgoingAutoBlockObservationDoesNotWaitOnTheInboundReplayMonitor() throws Exception {
        try (Fixture f = new Fixture()) {
            CombatRuntimeTest.Fake oldGame = new CombatRuntimeTest.Fake();
            CombatRuntime combat = new CombatRuntime(oldGame, new Random(8));
            combat.extended(f.runtime);
            f.config.find("autoBlock").enabled = true;
            combat.configure(f.config);
            f.runtime.openAutoBlockQueue();
            java.util.concurrent.ExecutorService netty = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                synchronized (f.runtime) {
                    java.util.concurrent.Future<Boolean> observed = netty.submit(() -> combat.send(oldGame, "move"));
                    assertFalse(observed.get(2, java.util.concurrent.TimeUnit.SECONDS));
                }
            } finally {
                netty.shutdownNow();
                combat.close();
            }
        }
    }

    @Test
    void replayScopeIsNestedAndExceptionSafe() {
        assertFalse(InboundReplay.active());
        InboundReplay.run(
                "backtrack",
                () -> {
                    assertTrue(InboundReplay.ownedBy("backtrack"));
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    InboundReplay.run(
                                            "antiKnockback",
                                            () -> {
                                                throw new IllegalStateException();
                                            }));
                    assertTrue(InboundReplay.ownedBy("backtrack"));
                });
        assertFalse(InboundReplay.active());
    }

    static class Player {
        final int id;
        public double posX, posY, posZ, motionX, motionY, motionZ;
        public float rotationYaw, rotationPitch, fallDistance;
        public int hurtTime, deathTime;
        public boolean onGround = true, sprinting = true, jumping, isDead;
        public Object ridingEntity;

        Player(int id) {
            this.id = id;
        }

        public String getName() {
            return "player" + id;
        }

        public void setSprinting(boolean value) {
            sprinting = value;
        }

        public boolean isSprinting() {
            return sprinting;
        }

        public boolean isRiding() {
            return false;
        }

        public boolean isBurning() {
            return false;
        }

        public boolean isInWater() {
            return false;
        }

        public boolean isInLava() {
            return false;
        }

        public boolean isSneaking() {
            return false;
        }

        public boolean isOnLadder() {
            return false;
        }

        public void setJumping(boolean value) {
            jumping = value;
        }

        public boolean isEntityAlive() {
            return !isDead;
        }

        public boolean isInvisible() {
            return false;
        }

        public float getHealth() {
            return 20;
        }
    }

    static final class Input {
        public float moveForward = 1, moveStrafe;
    }

    static final class Client {
        public Object getCurrentServerData() {
            return null;
        }
    }

    static final class FakeGame extends ExtendedCombatAccess {
        Player player = new Player(1);
        Object world = new Object(), hit;
        final Client client = new Client();
        final Input input = new Input();
        boolean creative, jumpKey, leftMouse, reachBlocked;
        double pickedRange;
        ExtendedCombatRuntime runtime;
        final List<Player> targets = new ArrayList<Player>();
        final List<Object> received = new ArrayList<Object>(), sent = new ArrayList<Object>();

        FakeGame() {
            super(null);
        }

        @Override
        public Object minecraft() {
            return client;
        }

        @Override
        public Object player() {
            return player;
        }

        @Override
        public Object world() {
            return world;
        }

        @Override
        public Object screen() {
            return null;
        }

        @Override
        public boolean inWorld() {
            return player != null && world != null;
        }

        @Override
        public boolean singleplayer() {
            return false;
        }

        @Override
        public Object input() {
            return input;
        }

        @Override
        public boolean kind(Object entity, String name) {
            if (entity == null) return false;
            if (entity instanceof Player)
                return name.equals("entity.EntityLivingBase")
                        || name.equals("entity.player.EntityPlayer");
            if (name.startsWith("network."))
                return packet(entity, name.substring(name.lastIndexOf('.') + 1));
            return false;
        }

        @Override
        public int entityId(Object e) {
            return ((Player) e).id;
        }

        @Override
        public Object entity(int id) {
            if (player.id == id) return player;
            for (Player p : targets) if (p.id == id) return p;
            return null;
        }

        @Override
        public List<?> players() {
            return targets;
        }

        @Override
        public List<CombatAccess.Target> targets() {
            List<CombatAccess.Target> result = new ArrayList<CombatAccess.Target>();
            for (Player p : targets) {
                CombatAccess.Target t = new CombatAccess.Target();
                t.entity = p;
                t.player = true;
                t.distanceSq = distanceSq(p);
                result.add(t);
            }
            return result;
        }

        @Override
        public boolean velocityForPlayer(Object p) {
            return ((S12PacketEntityVelocity) p).entityID == player.id;
        }

        @Override
        public Object hitEntity() {
            return hit;
        }

        @Override
        public boolean capability(String name) {
            return name.startsWith("allowEdit");
        }

        @Override
        public boolean creative() {
            return creative;
        }

        @Override
        public boolean allowEdit() {
            return true;
        }

        @Override
        public boolean potion(String name) {
            return false;
        }

        @Override
        public int potionLevel(String name) {
            return 0;
        }

        @Override
        public boolean keyDown(String name) {
            return jumpKey;
        }

        @Override
        public boolean backwards() {
            return false;
        }

        @Override
        public boolean mouse(int button) {
            return button == 0 && leftMouse;
        }

        @Override
        public boolean reachBlocked() {
            return reachBlocked;
        }

        @Override
        public boolean pick(double range) {
            pickedRange = range;
            return true;
        }

        @Override
        public boolean weapon() {
            return true;
        }

        @Override
        public boolean sword() {
            return true;
        }

        @Override
        public Object held() {
            return null;
        }

        @Override
        public boolean blockHit() {
            return false;
        }

        @Override
        public boolean interactable() {
            return false;
        }

        @Override
        public boolean blocking() {
            return false;
        }

        @Override
        public boolean attackPacket(Object packet) {
            return packet instanceof C02PacketUseEntity;
        }

        @Override
        public boolean placement(Object packet) {
            return false;
        }

        @Override
        public Object packetEntity(Object packet) {
            return ((C02PacketUseEntity) packet).target;
        }

        @Override
        public void sendDirect(Object manager, Object packet) {
            sent.add(packet);
        }

        @Override
        public void process(Object packet, Object handler) {
            if (!runtime.receive(handler, packet)) received.add(packet);
        }

        @Override
        public double[] serverPosition(Object entity) {
            return position(entity);
        }

        @Override
        public double[] offsetBox(Object entity, double[] pos) {
            return new double[] {
                pos[0] - .3, pos[1], pos[2] - .3, pos[0] + .3, pos[1] + 1.8, pos[2] + .3
            };
        }

        @Override
        public double rayDistanceSq(Object entity, double[] box, double range) {
            double distance = box[2] - .1;
            return distance <= range ? distance * distance : -1;
        }

        @Override
        public double heightAboveGround() {
            return 1;
        }

        @Override
        public boolean landsWithin(int ticks) {
            return true;
        }
    }

    static class S12PacketEntityVelocity {
        final int entityID;
        public int motionX, motionY, motionZ;

        S12PacketEntityVelocity(int id, int x, int y, int z) {
            entityID = id;
            motionX = x;
            motionY = y;
            motionZ = z;
        }

        public int getEntityID() {
            return entityID;
        }

        public int getMotionX() {
            return motionX;
        }

        public int getMotionY() {
            return motionY;
        }

        public int getMotionZ() {
            return motionZ;
        }
    }

    static final class S15PacketEntityRelMove {
        final Player target;
        final int z;

        S15PacketEntityRelMove(Player target, int z) {
            this.target = target;
            this.z = z;
        }

        public Player getEntity(Object world) {
            return target;
        }

        public int func_149062_c() {
            return 0;
        }

        public int func_149061_d() {
            return 0;
        }

        public int func_149064_e() {
            return z;
        }
    }

    static final class S32PacketConfirmTransaction {}

    static final class S08PacketPlayerPosLook {
        public float getPitch() {
            return 0;
        }
    }

    static final class C03PacketPlayer {
        public boolean isMoving() {
            return false;
        }
    }

    static final class C02PacketUseEntity {
        final Player target;

        C02PacketUseEntity(Player target) {
            this.target = target;
        }

        public String getAction() {
            return "ATTACK";
        }
    }

    static final class S19PacketEntityStatus {
        final Player target;

        S19PacketEntityStatus(Player target) {
            this.target = target;
        }

        public int getOpCode() {
            return 2;
        }

        public Player getEntity(Object world) {
            return target;
        }
    }
}
