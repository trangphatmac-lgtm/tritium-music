package tritium.minecraft.runtime.hook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RenderAccessTest {
    private static final List<String> glCalls = new ArrayList<String>();
    private static float[] color;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void glStateCallsDoNotLookUpWorldBlocks(boolean srg) {
        Object world = srg ? new SrgWorld() : new McpWorld();
        RenderAccess access = access(world, srg);
        glCalls.clear();
        color = null;

        // Single-string calls must reach GlStateManager, even while a world is loaded.
        access.state("pushMatrix");
        access.state("enableBlend");
        access.state("color", .1f, .2f, .3f, .4f);
        access.state("popMatrix");
        assertEquals(Arrays.asList("pushMatrix", "enableBlend", "color", "popMatrix"), glCalls);
        assertArrayEquals(new float[]{.1f, .2f, .3f, .4f}, color);
        assertNull(lastPosition(world));

        // GL state operations also work before entering a world.
        access(null, srg).state("enableBlend");
        assertEquals(5, glCalls.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockQueriesPassThePositionToTheWorld(boolean srg) {
        Object world = srg ? new SrgWorld() : new McpWorld();
        RenderAccess access = access(world, srg);
        BlockPos pos = new BlockPos();
        Object state = access.blockState(pos);
        assertSame(pos, lastPosition(world));
        assertSame(srg ? ((SrgWorld) world).state : ((McpWorld) world).state, state);
        assertSame(BLOCK, access.blockAt(pos));
    }

    private static RenderAccess access(Object world, final boolean srg) {
        return new RenderAccess(new Client(world)) {
            @Override public Class<?> cls(String name) {
                assertEquals("client.renderer.GlStateManager", name);
                return srg ? SrgGlStateManager.class : McpGlStateManager.class;
            }
        };
    }

    private static BlockPos lastPosition(Object world) {
        return world instanceof SrgWorld ? ((SrgWorld) world).position : ((McpWorld) world).position;
    }

    private static final Object BLOCK = new Object();
    static class Client {
        public Object theWorld;
        Client(Object world) { theWorld = world; }
    }
    static class BlockPos { }
    static class McpWorld {
        BlockPos position;
        final McpBlockState state = new McpBlockState();
        public McpBlockState getBlockState(BlockPos pos) { position = pos; return state; }
    }
    static class SrgWorld {
        BlockPos position;
        final SrgBlockState state = new SrgBlockState();
        public SrgBlockState func_180495_p(BlockPos pos) { position = pos; return state; }
    }
    static class McpBlockState { public Object getBlock() { return BLOCK; } }
    static class SrgBlockState { public Object func_177230_c() { return BLOCK; } }

    static class McpGlStateManager {
        public static void pushMatrix() { glCalls.add("pushMatrix"); }
        public static void enableBlend() { glCalls.add("enableBlend"); }
        public static void popMatrix() { glCalls.add("popMatrix"); }
        public static void color(float r, float g, float b, float a) {
            glCalls.add("color"); color = new float[]{r, g, b, a};
        }
    }
    static class SrgGlStateManager {
        public static void func_179094_E() { glCalls.add("pushMatrix"); }
        public static void func_179147_l() { glCalls.add("enableBlend"); }
        public static void func_179121_F() { glCalls.add("popMatrix"); }
        public static void func_179131_c(float r, float g, float b, float a) {
            glCalls.add("color"); color = new float[]{r, g, b, a};
        }
    }
}
