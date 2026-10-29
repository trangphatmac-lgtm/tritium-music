package tritium.minecraft.runtime.lyric;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import tritium.minecraft.protocol.*;
import tritium.minecraft.runtime.hook.GameAccess;
import tritium.minecraft.runtime.hook.RuntimeSession;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HudEditorScreenTest {
    @Test void overridesMcpAndSrgInputWithoutCallingVanillaOrPausingAndDetachesCallback() throws Exception {
        for (Class<?> base : new Class<?>[]{McpScreen.class, SrgScreen.class}) {
            boolean srg = base == SrgScreen.class;
            List<Integer> events = new ArrayList<Integer>();
            Object screen = HudEditorScreen.create(base, event -> { events.add(event); return true; });
            String mouse = srg ? "func_146274_d" : "handleMouseInput";
            String keyboard = srg ? "func_146282_l" : "handleKeyboardInput";
            String closed = srg ? "func_146281_b" : "onGuiClosed";
            screen.getClass().getMethod(mouse).invoke(screen);
            screen.getClass().getMethod(keyboard).invoke(screen);
            screen.getClass().getMethod(closed).invoke(screen);
            screen.getClass().getMethod(srg ? "func_73863_a" : "drawScreen", int.class, int.class, float.class).invoke(screen, 1, 2, .5f);
            assertEquals(false, screen.getClass().getMethod(srg ? "func_73868_f" : "doesGuiPauseGame").invoke(screen));
            assertFalse(base.getField("allowUserInput").getBoolean(screen));
            assertEquals(Arrays.asList(2, 14, 15), events);
            ((AutoCloseable) screen).close();
            screen.getClass().getMethod(mouse).invoke(screen);
            screen.getClass().getMethod(keyboard).invoke(screen);
            screen.getClass().getMethod(closed).invoke(screen);
            assertEquals(3, events.size(), "Disposed screen must not retain or invoke the runtime callback");
            Field callback = screen.getClass().getDeclaredField("callback"); callback.setAccessible(true);
            assertNull(callback.get(screen));
        }
    }

    @Test void desktopRequestOpensImmediatelyAndReplacementSavesDragWithoutClosingNewScreen() throws Exception {
        Client.instance = new Client(); ClassLoader loader = fixtureLoader(); GameAccess game = new GameAccess(loader);
        RuntimeSession runtime = new RuntimeSession("1:12345:" + String.join("", Collections.nCopies(64, "a")), loader);
        HudConfig config = new HudConfig(); List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add);
        set(runtime, "game", game); set(runtime, "input", input); set(runtime, "config", config); set(runtime, "installed", true);
        request(runtime, true);
        Object editor = game.currentScreen();
        assertNotNull(editor); assertFalse(game.chatOpen()); assertTrue(input.editing);
        assertFalse(Client.instance.inGameHasFocus); assertEquals(1, Client.instance.opens);
        invoke(runtime, "syncEditor"); assertSame(editor, game.currentScreen()); assertEquals(1, Client.instance.opens);
        double[] b = config.modules[0].bounds(1000, 700);
        assertTrue(input.mouse(config, true, b[0] + 5, b[1] + 5, 0, true, 0, 1000, 700));
        input.tick(config, true, 300, 200, 1000, 700);
        Object otherScreen = new Object(); game.displayScreen(otherScreen);
        assertFalse(input.editing); assertFalse(input.dragging());
        assertEquals("layout", events.get(0).action); assertEquals("edit", events.get(1).action);
        assertEquals(295, config.modules[0].bounds(1000, 700)[0], .001);
        invoke(runtime, "syncEditor"); assertSame(otherScreen, game.currentScreen());
        invoke(runtime, "cleanupRenderResources"); assertSame(otherScreen, game.currentScreen());
        Field callback = editor.getClass().getDeclaredField("callback"); callback.setAccessible(true); assertNull(callback.get(editor));
    }

    @Test void desktopToggleWorldExitAndUninjectAllCloseEditorAndRestoreFocus() throws Exception {
        Client.instance = new Client(); ClassLoader loader = fixtureLoader(); GameAccess game = new GameAccess(loader);
        RuntimeSession runtime = new RuntimeSession("1:12345:" + String.join("", Collections.nCopies(64, "b")), loader);
        HudInput input = new HudInput(event -> {});
        set(runtime, "game", game); set(runtime, "input", input); set(runtime, "config", new HudConfig()); set(runtime, "installed", true);
        request(runtime, true); request(runtime, false);
        assertNull(game.currentScreen()); assertFalse(input.editing); assertTrue(Client.instance.inGameHasFocus);
        request(runtime, true); Client.instance.theWorld = null; invoke(runtime, "syncEditor");
        assertNull(game.currentScreen()); assertFalse(input.editing);
        request(runtime, true); assertNull(game.currentScreen()); assertFalse(input.editing);
        Client.instance.theWorld = new Object(); request(runtime, true);
        Object editor = game.currentScreen(); set(runtime, "stop", true); runtime.test(1);
        assertNull(game.currentScreen()); assertTrue(Client.instance.inGameHasFocus);
        Field callback = editor.getClass().getDeclaredField("callback"); callback.setAccessible(true); assertNull(callback.get(editor));
    }

    @SuppressWarnings("unchecked") private static void request(RuntimeSession runtime, boolean value) throws Exception {
        Field field = RuntimeSession.class.getDeclaredField("pendingEdit"); field.setAccessible(true);
        ((AtomicReference<Boolean>) field.get(runtime)).set(value); invoke(runtime, "syncEditor");
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void invoke(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object);
    }
    private static ClassLoader fixtureLoader() {
        return new ClassLoader(HudEditorScreenTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                Class<?> base;
                if (name.equals("net.minecraft.client.Minecraft")) base = Client.class;
                else if (name.equals("net.minecraft.client.gui.GuiScreen")) base = McpScreen.class;
                else if (name.equals("net.minecraft.client.gui.GuiChat")) base = Object.class;
                else throw new ClassNotFoundException(name);
                ClassWriter w = new ClassWriter(0); String parent = org.objectweb.asm.Type.getInternalName(base);
                w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, parent, null);
                MethodVisitor ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
                ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
                ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(1, 1); ctor.visitEnd(); w.visitEnd();
                byte[] bytes = w.toByteArray(); return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
    public static class McpScreen {
        public boolean allowUserInput;
        public void handleMouseInput() { fail("Vanilla mouse input leaked"); }
        public void handleKeyboardInput() { fail("Vanilla keyboard input leaked"); }
        public void onGuiClosed() { fail("Vanilla close handler called"); }
        public void drawScreen(int x, int y, float partial) { fail("Vanilla UI drawn"); }
        public boolean doesGuiPauseGame() { return true; }
    }
    public static class SrgScreen {
        public boolean allowUserInput;
        public void func_146274_d() { fail("Vanilla mouse input leaked"); }
        public void func_146282_l() { fail("Vanilla keyboard input leaked"); }
        public void func_146281_b() { fail("Vanilla close handler called"); }
        public void func_73863_a(int x, int y, float partial) { fail("Vanilla UI drawn"); }
        public boolean func_73868_f() { return true; }
    }
    public static class Client {
        static Client instance;
        public Object theWorld = new Object(), currentScreen;
        public Settings gameSettings = new Settings();
        public boolean inGameHasFocus = true;
        public int displayWidth = 1000, displayHeight = 700, opens;
        public static Client getMinecraft() { return instance; }
        public String getVersion() { return "1.8.9"; }
        public boolean isUnicode() { return false; }
        public void displayGuiScreen(Object next) throws Exception {
            if (currentScreen instanceof McpScreen) ((McpScreen) currentScreen).onGuiClosed();
            currentScreen = next; inGameHasFocus = next == null; opens++;
        }
    }
    public static class Settings { public boolean hideGUI; public int guiScale; }
}
