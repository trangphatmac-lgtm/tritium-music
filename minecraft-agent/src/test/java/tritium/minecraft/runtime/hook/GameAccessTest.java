package tritium.minecraft.runtime.hook;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class GameAccessTest {
    @Test void cameraAndNativeFontUseMcpMappingsWithoutLinkingMinecraftClasses() throws Exception {
        McpClient.instance = new McpClient();
        GameAccess access = new GameAccess(loader(McpClient.class));
        McpClient client = (McpClient) access.minecraft;
        GameAccess.Camera camera = access.camera(.25f);
        assertSame(client.theWorld, camera.worldIdentity);
        assertEquals(11, camera.originX); assertEquals(22, camera.originY); assertEquals(33, camera.originZ);
        assertEquals(23.62, camera.eyeY, .0001); assertEquals(45, camera.yaw); assertEquals(-10, camera.pitch);
        assertEquals(80, camera.fov); assertEquals(1280, camera.pixelWidth); assertEquals(720, camera.pixelHeight);
        assertEquals(18, access.nativeWidth("abc"));
        access.drawNative("abc", 1, 2, 0xff123456, true);
        assertEquals("abc", client.fontRendererObj.drawn); assertTrue(client.fontRendererObj.shadow);
        client.theWorld = null; assertNull(access.camera(.5f));
    }

    @Test void cameraAndNativeFontUseSrgMappingsAndBoundPartialTicks() throws Exception {
        SrgClient.instance = new SrgClient();
        GameAccess access = new GameAccess(loader(SrgClient.class));
        SrgClient client = (SrgClient) access.minecraft;
        assertEquals("Forge", access.client);
        assertEquals(10, access.camera(Float.NaN).originX);
        assertEquals(14, access.camera(2).originX);
        assertEquals(10, access.camera(-1).originX);
        assertEquals(18, access.nativeWidth("abc"));
        access.drawNative("srg", 0, 0, 0xffffffff, false);
        assertEquals("srg", client.field_71466_p.drawn); assertFalse(client.field_71466_p.shadow);
        client.view = null; assertNull(access.camera(0));
    }

    private static ClassLoader loader(final Class<?> base) {
        return new ClassLoader(GameAccessTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                String superclass;
                if (name.equals("net.minecraft.client.Minecraft")) superclass = base.getName().replace('.', '/');
                else if (name.equals("net.minecraft.client.gui.GuiChat")) superclass = "java/lang/Object";
                else throw new ClassNotFoundException(name);
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, superclass, null);
                writer.visitEnd(); byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    public static class McpClient {
        static McpClient instance;
        public Object theWorld = new Object(), currentScreen;
        public McpSettings gameSettings = new McpSettings();
        public boolean inGameHasFocus;
        public int displayWidth = 1280, displayHeight = 720;
        public McpFont fontRendererObj = new McpFont();
        public McpEntity view = new McpEntity();
        public static McpClient getMinecraft() { return instance; }
        public String getVersion() { return "1.8.9"; }
        public boolean isUnicode() { return false; }
        public McpEntity getRenderViewEntity() { return view; }
    }
    public static class McpSettings {
        public boolean hideGUI;
        public int guiScale;
        public float fovSetting = 80;
    }
    public static class McpEntityBase {
        public double lastTickPosX = 10, lastTickPosY = 20, lastTickPosZ = 30;
        public double posX = 14, posY = 28, posZ = 42;
        public float rotationYaw = 45, rotationPitch = -10;
        public float getEyeHeight() { return 1.62f; }
    }
    public static class McpEntity extends McpEntityBase { }
    public static class McpFont {
        String drawn; boolean shadow;
        public int getStringWidth(String text) { return text.length() * 6; }
        public int drawString(String text, float x, float y, int color, boolean shadow) {
            drawn = text; this.shadow = shadow; return 1;
        }
    }
    public static class SrgClient {
        static SrgClient instance;
        public Object field_71441_e = new Object(), field_71462_r;
        public SrgSettings field_71474_y = new SrgSettings();
        public boolean field_71415_G;
        public int field_71443_c = 1280, field_71440_d = 720;
        public SrgFont field_71466_p = new SrgFont();
        public SrgEntity view = new SrgEntity();
        public static SrgClient func_71410_x() { return instance; }
        public String func_175600_c() { return "1.8.9"; }
        public boolean func_152349_b() { return false; }
        public SrgEntity func_175606_aa() { return view; }
    }
    public static class SrgSettings {
        public boolean field_74319_N;
        public int field_74335_Z;
        public float field_74334_X = 70;
    }
    public static class SrgEntityBase {
        public double field_70142_S = 10, field_70137_T = 20, field_70136_U = 30;
        public double field_70165_t = 14, field_70163_u = 28, field_70161_v = 42;
        public float field_70177_z = 45, field_70125_A = -10;
        public float func_70047_e() { return 1.62f; }
    }
    public static class SrgEntity extends SrgEntityBase { }
    public static class SrgFont {
        String drawn; boolean shadow;
        public int func_78256_a(String text) { return text.length() * 6; }
        public int func_175065_a(String text, float x, float y, int color, boolean shadow) {
            drawn = text; this.shadow = shadow; return 1;
        }
    }
}
