package tritium.minecraft.runtime.hook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.runtime.util.AntiBotHypixel;
import tritium.minecraft.runtime.util.BotCheck;

import java.util.*;

class AntiBotRuntimeTest {
    static final class Player {
        int id = 2, hurtTime, maxHurtTime = 10;
        boolean dead, invisible, sleeping;
        float health = 20, rotationYaw = 45, rotationPitch = 10;
        double y = 64;
        String name = "Alice", display = "§aAlice", team = "green", networkTeam = "green";
        Object held;
        boolean tab = true;
    }

    static final class Fake extends AntiBotAccess {
        Object world = new Object();
        Player self = new Player();
        long now = 10000;
        int ticks;
        boolean hypixel = true;
        List<String> lines = Arrays.asList("DUELS", "one", "two", "Time: 01:00");
        final Map<Integer, Player> entities = new HashMap<Integer, Player>();
        final List<String> messages = new ArrayList<String>(), hovers = new ArrayList<String>();
        int sounds, boxes;
        Object boxed;

        Fake() {
            super(null);
            self.id = 1;
        }

        @Override
        public Object world() {
            return world;
        }

        @Override
        public Object player() {
            return self;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public int ticks() {
            return ticks;
        }

        @Override
        public boolean playerEntity(Object e) {
            return e instanceof Player;
        }

        @Override
        public boolean dead(Object e) {
            return ((Player) e).dead;
        }

        @Override
        public int entityId(Object e) {
            return ((Player) e).id;
        }

        @Override
        public Object entity(int id) {
            return entities.get(id);
        }

        @Override
        public String name(Object e) {
            return ((Player) e).name;
        }

        @Override
        public String display(Object e) {
            return ((Player) e).display;
        }

        @Override
        public String text(Object component) {
            return component.toString();
        }

        @Override
        public Object uuid(Object e) {
            return new UUID(0, ((Player) e).id);
        }

        @Override
        public float health(Object e) {
            return ((Player) e).health;
        }

        @Override
        public int maxHurtTime(Object e) {
            return ((Player) e).maxHurtTime;
        }

        @Override
        public boolean invisible(Object e) {
            return ((Player) e).invisible;
        }

        @Override
        public boolean sleeping(Object e) {
            return ((Player) e).sleeping;
        }

        @Override
        public Object held(Object e) {
            return ((Player) e).held;
        }

        @Override
        public Object info(Object e) {
            return ((Player) e).tab ? e : null;
        }

        @Override
        public Object networkTeam(Object e) {
            return ((Player) e).networkTeam;
        }

        @Override
        public String teamName(Object e) {
            return (String) e;
        }

        @Override
        public Object team(Object e) {
            return ((Player) e).team;
        }

        @Override
        public String registeredTeamName(Object e) {
            return (String) e;
        }

        @Override
        public int ping(Object e) {
            return 73;
        }

        @Override
        public String itemName(Object e) {
            return e.toString();
        }

        @Override
        public double coordinate(Object e, int axis) {
            return axis == 1 ? ((Player) e).y : axis == 0 ? 1.25 : 3.5;
        }

        @Override
        public boolean hypixelServer() {
            return hypixel;
        }

        @Override
        public int dimension() {
            return 0;
        }

        @Override
        public List<String> sidebar() {
            return lines;
        }

        @Override
        public void chat(String text, String hover) {
            messages.add(text);
            hovers.add(hover);
        }

        @Override
        public void sound(String name, float volume, float pitch) {
            assertEquals("note.pling", name);
            assertEquals(1f, volume);
            assertEquals(1f, pitch);
            sounds++;
        }

        @Override
        public void watchdogBox(Object e, float partial) {
            boxes++;
            boxed = e;
        }
    }

    static final class Fixture implements AutoCloseable {
        final Fake game = new Fake();
        final Player other = new Player();
        final HudConfig config = new HudConfig();
        final AntiBotRuntime runtime = new AntiBotRuntime(game, new Random(7));

        Fixture() {
            config.normalize();
            config.hidden = true;
            config.find("antiBot").enabled = true;
            game.entities.put(other.id, other);
            runtime.configure(config);
            runtime.preUpdate();
        }

        void number(String key, double value) {
            config.find("antiBot").openB4.numbers.put(key, value);
            runtime.configure(config);
        }

        void toggle(String key, boolean value) {
            config.find("antiBot").openB4.toggles.put(key, value);
            runtime.configure(config);
        }

        void enabled(boolean value) {
            config.find("antiBot").enabled = value;
            runtime.configure(config);
        }

        public void close() {
            runtime.close();
        }
    }

    @Test
    void disabledStillRejectsDeadNonPlayersAndFakeEntities() {
        try (Fixture f = new Fixture()) {
            f.enabled(false);
            assertFalse(f.runtime.bot(f.other));
            assertTrue(f.runtime.bot(null));
            assertTrue(f.runtime.bot(new Object()));
            f.other.dead = true;
            assertTrue(f.runtime.bot(f.other));
            f.other.dead = false;
            f.other.id = -170;
            assertTrue(f.runtime.bot(f.other));
        }
    }

    @Test
    void spawnDelayUsesJoinIdentityAndEvenTickStrictExpiry() {
        try (Fixture f = new Fixture()) {
            f.toggle("^ Disable in duels", false);
            f.number("Delay (spawn)", 1);
            assertFalse(f.runtime.bot(f.other));
            f.runtime.entityJoin(f.other);
            assertTrue(f.runtime.bot(f.other));
            f.game.now += 1000;
            f.game.ticks = 2;
            f.runtime.tickEnd();
            assertTrue(f.runtime.bot(f.other));
            f.game.now++;
            f.game.ticks = 3;
            f.runtime.tickEnd();
            assertTrue(f.runtime.bot(f.other));
            f.game.ticks = 4;
            f.runtime.tickEnd();
            assertFalse(f.runtime.bot(f.other));
            f.runtime.entityJoin(f.game.self);
            assertFalse(f.runtime.bots().recentlySpawned.containsKey(f.game.self));
        }
    }

    @Test
    void spawnBypassDoesNotBypassTabListOrOtherFilters() {
        try (Fixture f = new Fixture()) {
            f.toggle("^ Disable in duels", false);
            f.number("Delay (spawn)", 1);
            f.runtime.entityJoin(f.other);
            assertTrue(f.runtime.bot(f.other, true));
            assertFalse(f.runtime.bot(f.other, false));
            f.toggle("Tab list", true);
            f.other.tab = false;
            assertTrue(f.runtime.bot(f.other, false));
        }
    }

    @Test
    void disableInDuelsAppliesOnlyToActiveGame() {
        try (Fixture f = new Fixture()) {
            f.number("Delay (spawn)", 1);
            f.runtime.entityJoin(f.other);
            assertEquals(AntiBotHypixel.State.DUELS_GAME, f.runtime.serverState());
            assertFalse(f.runtime.bot(f.other));
            f.game.lines =
                    Arrays.asList("DUELS", "one", "two", "three", "four", "five", "Waiting...");
            f.runtime.preUpdate();
            assertTrue(f.runtime.bot(f.other));
            f.game.lines = Arrays.asList("DUELS", "one", "two", "Time: 00:10");
            f.runtime.preUpdate();
            f.toggle("^ Disable in duels", false);
            assertTrue(f.runtime.bot(f.other));
        }
    }

    @Test
    void respawnUsesStrippedChatAndDoesNotAlterBotOrAboveSpawn() {
        try (Fixture f = new Fixture()) {
            f.number("Delay (respawn)", 2);
            f.runtime.chat("§aYou have respawned!", 2);
            assertFalse(f.runtime.ignored(f.other));
            f.runtime.chat("§aYou have respawned!", 0);
            assertTrue(f.runtime.ignored(f.other));
            assertFalse(f.runtime.bot(f.other));
            assertFalse(f.runtime.aboveSpawn(f.other));
            f.game.now += 2000;
            assertFalse(f.runtime.ignored(f.other));
            f.runtime.chat(" You have respawned!", 0);
            assertFalse(f.runtime.ignored(f.other));
        }
    }

    @Test
    void pitUsesChangedDefaultAndInclusiveHeightWithoutChangingBotResult() {
        try (Fixture f = new Fixture()) {
            f.other.y = 200;
            assertFalse(f.runtime.aboveSpawn(f.other));
            f.number("Pit spawn", 80);
            f.other.y = 79.999;
            assertFalse(f.runtime.ignored(f.other));
            f.other.y = 80;
            assertTrue(f.runtime.aboveSpawn(f.other));
            assertTrue(f.runtime.ignored(f.other));
            assertFalse(f.runtime.bot(f.other));
        }
    }

    @Test
    void hypixelHeuristicsAreSkippedElsewhereButTabListIsUniversal() {
        try (Fixture f = new Fixture()) {
            f.game.hypixel = false;
            f.runtime.preUpdate();
            f.other.name = "";
            assertFalse(f.runtime.bot(f.other));
            f.other.tab = false;
            f.toggle("Tab list", true);
            assertTrue(f.runtime.bot(f.other));
        }
    }

    @Test
    void allOriginalNameHealthAndDisplayPatternsArePreserved() {
        try (Fixture f = new Fixture()) {
            f.other.maxHurtTime = 0;
            for (String display :
                    new String[] {"1234567890", "cp_dummy", "Opalsmith", "§a[NPC] Clerk"}) {
                f.other.display = display;
                assertTrue(f.runtime.bot(f.other), display);
            }
            f.other.display = "§a1234567890";
            f.other.sleeping = true;
            assertTrue(f.runtime.bot(f.other));
            f.other.sleeping = false;
            assertFalse(f.runtime.bot(f.other));
            f.other.health = 19;
            f.other.display = "plain";
            assertTrue(f.runtime.bot(f.other));
            f.other.display = "§cAlice";
            assertFalse(f.runtime.bot(f.other));
            f.other.invisible = true;
            assertTrue(f.runtime.bot(f.other));
            f.other.maxHurtTime = 10;
            f.other.invisible = false;
            f.other.name = "§cAlice";
            assertTrue(f.runtime.bot(f.other));
            f.other.name = "";
            assertTrue(f.runtime.bot(f.other));
        }
    }

    @Test
    void watchdogRequiresEmptyNetworkTeamAndQueuesOneSoundUntilDisappearance() {
        try (Fixture f = new Fixture()) {
            f.other.display = "§cAlice";
            assertFalse(f.runtime.bot(f.other));
            f.other.networkTeam = "";
            assertTrue(f.runtime.bot(f.other));
            assertTrue(f.game.messages.isEmpty());
            f.other.held = "sword";
            assertTrue(f.runtime.bot(f.other));
            assertEquals(1, f.game.messages.size());
            assertEquals(0, f.game.sounds);
            f.runtime.renderWorld(.5f);
            assertSame(f.other, f.game.boxed);
            f.runtime.tickEnd();
            assertEquals(1, f.game.sounds);
            f.game.now += 2000;
            f.runtime.bot(f.other);
            f.runtime.tickEnd();
            assertEquals(1, f.game.sounds);
            assertEquals(1, f.game.messages.size());
            f.game.entities.clear();
            f.runtime.renderWorld(0);
            f.game.now += 1000;
            f.runtime.bot(f.other);
            f.runtime.tickEnd();
            assertEquals(2, f.game.messages.size());
            assertEquals(2, f.game.sounds);
        }
    }

    @Test
    void watchdogEntityIdCanChangeWithoutRepeatingAnnouncement() {
        try (Fixture f = new Fixture()) {
            f.other.display = "§cAlice";
            f.other.networkTeam = "";
            f.other.held = "sword";
            f.runtime.bot(f.other);
            Player next = new Player();
            next.id = 3;
            next.display = "§cBob";
            next.networkTeam = "";
            next.held = "axe";
            f.game.entities.put(3, next);
            f.game.now += 999;
            f.runtime.bot(next);
            f.runtime.renderWorld(0);
            assertSame(f.other, f.game.boxed);
            f.game.now++;
            f.runtime.bot(next);
            f.runtime.renderWorld(0);
            assertSame(next, f.game.boxed);
            assertEquals(1, f.game.messages.size());
        }
    }

    @Test
    void disablingClearsProtectionAndWatchdogButPreservesOriginalAnnouncementThrottle() {
        try (Fixture f = new Fixture()) {
            f.number("Delay (spawn)", 1);
            f.number("Delay (respawn)", 2);
            f.runtime.entityJoin(f.other);
            f.runtime.chat("You have respawned!", 0);
            f.other.display = "§cAlice";
            f.other.networkTeam = "";
            f.other.held = "sword";
            f.runtime.bot(f.other, false);
            f.runtime.tickEnd();
            f.enabled(false);
            assertTrue(f.runtime.bots().recentlySpawned.isEmpty());
            assertFalse(f.runtime.ignored(f.other));
            f.runtime.renderWorld(0);
            assertEquals(0, f.game.boxes);
            f.game.now += 500;
            f.enabled(true);
            f.runtime.bot(f.other, false);
            assertEquals(1, f.game.messages.size());
            f.game.now += 500;
            f.runtime.bot(f.other, false);
            assertEquals(2, f.game.messages.size());
        }
    }

    @Test
    void joinMessageHasOriginalRichHoverAndBypassesSpawnDelayInDiagnostics() {
        try (Fixture f = new Fixture()) {
            f.number("Delay (spawn)", 1);
            f.toggle("^ Disable in duels", false);
            f.toggle("Print world join", true);
            f.other.held = "Diamond Sword";
            f.runtime.entityJoin(f.other);
            assertEquals(1, f.game.messages.size());
            assertTrue(f.game.messages.get(0).contains("§7Entity §b2 §7joined: §r§aAlice"));
            String hover = f.game.hovers.get(0);
            for (String part :
                    new String[] {
                        "§ename: §rAlice",
                        "§estripped: §rAlice",
                        "§ebot: §7false",
                        "§eping: §773",
                        "§enetwork team: §7green",
                        "Diamond Sword",
                        "00000000-0000-0000-0000-000000000002"
                    }) assertTrue(hover.contains(part), part);
            assertTrue(f.runtime.bot(f.other));
        }
    }

    @Test
    void worldChangeDropsOldEntityReferencesAndQueuedSound() {
        try (Fixture f = new Fixture()) {
            f.number("Delay (spawn)", 1);
            f.runtime.entityJoin(f.other);
            f.other.display = "§cAlice";
            f.other.networkTeam = "";
            f.other.held = "sword";
            f.runtime.bot(f.other, false);
            f.game.world = new Object();
            f.runtime.configure(f.config);
            assertTrue(f.runtime.bots().recentlySpawned.isEmpty());
            f.runtime.tickEnd();
            f.runtime.renderWorld(0);
            assertEquals(0, f.game.sounds);
            assertEquals(0, f.game.boxes);
        }
    }

    @Test
    void combatAndRenderAdaptersShareStateAndExplicitSpawnBypass() {
        try (Fixture f = new Fixture()) {
            f.number("Delay (spawn)", 1);
            f.toggle("^ Disable in duels", false);
            f.runtime.entityJoin(f.other);
            CombatAccess combat =
                    new CombatAccess(null) {
                        @Override
                        public Object hitEntity() {
                            return f.other;
                        }
                    };
            combat.antiBot(f.runtime);
            RenderAccess render = new RenderAccess(null);
            render.antiBot(f.runtime);
            assertTrue(combat.bot(f.other));
            assertFalse(combat.lookingAtBot());
            assertTrue(render.bot(f.other));
            assertFalse(render.bot(f.other, false));
            f.number("Pit spawn", 80);
            f.other.y = 80;
            assertTrue(combat.ignored(f.other));
            assertTrue(render.aboveSpawn(f.other));
        }
    }

    @Test
    void offscreenNonShaderEspDoesNotTriggerWatchdogWhileShaderPassStillChecksBots() throws Exception {
        try (Fixture f = new Fixture()) {
            f.other.display = "§cAlice";
            f.other.networkTeam = "";
            f.other.held = "sword";
            RenderAccess render = new RenderAccess(null) {
                @Override public Object player() { return f.game.self; }
                @Override public int integerField(Object entity, String field) { return 0; }
                @Override public boolean invisible(Object entity) { return false; }
                @Override public Object entityBox(Object entity) { return entity; }
                @Override public Object invoke(Object owner, String method, Object... args) {
                    if (method.equals("isBoundingBoxInFrustum")) return false;
                    throw new AssertionError(method);
                }
            };
            render.antiBot(f.runtime);
            tritium.minecraft.runtime.render.PlayerESPModule esp =
                    new tritium.minecraft.runtime.render.PlayerESPModule(render,
                            new tritium.minecraft.runtime.util.render.Render3D(render));
            esp.configure(f.config.find("playerESP").openB4, true);
            java.lang.reflect.Method accepted = esp.getClass().getDeclaredMethod("accepted", Object.class, boolean.class);
            accepted.setAccessible(true);
            assertEquals(false, accepted.invoke(esp, f.other, false));
            assertTrue(f.game.messages.isEmpty(), "Offscreen ordinary ESP never calls the original BotCheck");
            assertEquals(false, accepted.invoke(esp, f.other, true));
            assertEquals(1, f.game.messages.size(), "The shader pass checks bots even outside the frustum");
        }
    }

    @Test
    void strippingRetainsOriginalAsciiAndFormattingRules() {
        assertEquals("A  B", BotCheck.strip("§aA\u0001 中文 B§"));
        assertEquals("", BotCheck.strip(null));
    }

    @Test
    void scoreboardStateMachinePreservesLobbyAndDuelsPrecedence() {
        assertEquals(AntiBotHypixel.State.LIMBO, AntiBotHypixel.detect(null, 1));
        assertEquals(
                AntiBotHypixel.State.PIT,
                AntiBotHypixel.detect(Arrays.asList("§eTHE HYPIXEL PIT"), 0));
        assertEquals(
                AntiBotHypixel.State.LOBBY,
                AntiBotHypixel.detect(Arrays.asList("DUELS", "map  §8L1", "", "Time: 1"), 0));
        assertEquals(
                AntiBotHypixel.State.DUELS_QUEUE,
                AntiBotHypixel.detect(
                        Arrays.asList("DUELS", "", "", "", "", "", "Starting 10"), 0));
        assertEquals(
                AntiBotHypixel.State.UNKNOWN,
                AntiBotHypixel.detect(Arrays.asList("DUELS", "Time: 1"), 0));
        assertEquals(
                AntiBotHypixel.State.BEDWARS_GAME_BED,
                AntiBotHypixel.detect(Arrays.asList("BED WARS", "", "", "", "", "§a✓ YOU"), 0));
    }
}
