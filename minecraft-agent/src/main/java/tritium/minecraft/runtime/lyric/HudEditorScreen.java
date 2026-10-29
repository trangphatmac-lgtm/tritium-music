package tritium.minecraft.runtime.lyric;

import org.objectweb.asm.*;
import tritium.minecraft.runtime.hook.GameAccess;
import java.util.function.IntPredicate;

/** A real, non-pausing GuiScreen, generated against the client's exact MCP/SRG methods.
 * Only JDK types cross its class loader boundary; Minecraft is never bundled in the agent. */
public final class HudEditorScreen implements AutoCloseable {
    public static final int MOUSE = 2, KEYBOARD = 14, CLOSED = 15;
    private final GameAccess game;
    private final Object screen;

    public HudEditorScreen(GameAccess game, ClassLoader gameLoader, IntPredicate callback) throws Exception {
        this.game = game;
        screen = create(Class.forName("net.minecraft.client.gui.GuiScreen", false, gameLoader), callback);
    }

    public boolean isOpen() throws IllegalAccessException { return game.currentScreen() == screen; }
    public void open() throws ReflectiveOperationException { if (!isOpen()) game.displayScreen(screen); }
    public void hide() throws ReflectiveOperationException { if (isOpen()) game.displayScreen(null); }

    @Override public void close() {
        try { hide(); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot close HUD editor", e); }
        finally {
            try { ((AutoCloseable) screen).close(); }
            catch (Exception e) { throw new IllegalStateException("Cannot detach HUD editor", e); }
        }
    }

    static Object create(Class<?> base, IntPredicate callback) throws ReflectiveOperationException {
        final String name = "tritium/minecraft/runtime/lyric/GeneratedHudEditor";
        String parent = Type.getInternalName(base), predicate = "Ljava/util/function/IntPredicate;";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, parent,
                new String[]{"java/lang/AutoCloseable"});
        writer.visitField(Opcodes.ACC_PRIVATE, "callback", predicate, null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + predicate + ")V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "callback", predicate);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        String[][] callbacks = {{"handleMouseInput", "func_146274_d"},
                {"handleKeyboardInput", "func_146282_l"}, {"onGuiClosed", "func_146281_b"}};
        int[] events = {MOUSE, KEYBOARD, CLOSED};
        for (int i = 0; i < callbacks.length; i++) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                    GameAccess.method(base, callbacks[i][0], callbacks[i][1]).getName(), "()V", null, null);
            method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, name, "callback", predicate);
            Label done = new Label(); method.visitJumpInsn(Opcodes.IFNULL, done);
            method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, name, "callback", predicate);
            method.visitIntInsn(Opcodes.BIPUSH, events[i]);
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/IntPredicate", "test", "(I)Z", true);
            method.visitInsn(Opcodes.POP); method.visitLabel(done);
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        // Rendering runs once at frame end, after the game has finished its own UI pass.
        MethodVisitor draw = writer.visitMethod(Opcodes.ACC_PUBLIC,
                GameAccess.method(base, "drawScreen", "func_73863_a", int.class, int.class, float.class).getName(), "(IIF)V", null, null);
        draw.visitCode(); draw.visitInsn(Opcodes.RETURN); draw.visitMaxs(0, 0); draw.visitEnd();
        MethodVisitor pause = writer.visitMethod(Opcodes.ACC_PUBLIC,
                GameAccess.method(base, "doesGuiPauseGame", "func_73868_f").getName(), "()Z", null, null);
        pause.visitCode(); pause.visitInsn(Opcodes.ICONST_0); pause.visitInsn(Opcodes.IRETURN); pause.visitMaxs(0, 0); pause.visitEnd();
        MethodVisitor close = writer.visitMethod(Opcodes.ACC_PUBLIC, "close", "()V", null, null);
        close.visitCode(); close.visitVarInsn(Opcodes.ALOAD, 0); close.visitInsn(Opcodes.ACONST_NULL);
        close.visitFieldInsn(Opcodes.PUTFIELD, name, "callback", predicate);
        close.visitInsn(Opcodes.RETURN); close.visitMaxs(0, 0); close.visitEnd(); writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(base.getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
        return type.getConstructor(IntPredicate.class).newInstance(callback);
    }
}
