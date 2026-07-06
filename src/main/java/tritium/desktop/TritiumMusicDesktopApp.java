package tritium.desktop;

import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.Framebuffer;
import tritium.rendering.MusicToast;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.cursor.CursorUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

public final class TritiumMusicDesktopApp {
    private static final int DEFAULT_WIDTH = 1650;
    private static final int DEFAULT_HEIGHT = 1080;
    private static volatile Throwable lastRenderError;

    private TritiumMusicDesktopApp() {
    }

    public static void main(String[] args) throws Exception {
        applyStartupOptions(args);
        DesktopNativeLoader.configureLibraryPath();
        DesktopAppState.markMainThread();
        DesktopAppState.loadPreferences();
        DesktopAppState.setRunning(true);

        long smokeExitAt = parseSmokeExitAt(args);

        try {
            createDisplay();
            initializeOpenGL();
            new FontManager().init();
            MultiThreadingUtil.runAsync(CloudMusic::initNCM);
            DesktopAppState.api().displayScreen(NCMScreen.getInstance());
            DesktopAppState.hudManager().start(hasArg(args, "--hud-smoke"));
            runLoop(smokeExitAt);
        } finally {
            shutdown();
        }
        System.exit(0);
    }

    private static long parseSmokeExitAt(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--smoke-exit-after=")) {
                long seconds = Long.parseLong(arg.substring("--smoke-exit-after=".length()));
                return System.currentTimeMillis() + seconds * 1000L;
            }
        }
        return -1;
    }

    private static boolean hasArg(String[] args, String target) {
        for (String arg : args) {
            if (target.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void applyStartupOptions(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--ui-scale=")) {
                System.setProperty("tritium.uiScale", arg.substring("--ui-scale=".length()));
            }
        }
    }

    private static void createDisplay() throws LWJGLException {
        System.setProperty("org.lwjgl.opengl.Display.enableHighDPI", "true");
        Display.setTitle("Tritium Music");
        Display.setResizable(true);
        Display.setDisplayMode(new DisplayMode(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        Display.create();
        Keyboard.create();
        Mouse.create();
        Keyboard.enableRepeatEvents(true);
    }

    private static void initializeOpenGL() {
        RenderSystem.updateDisplayMetrics();
        GL11.glViewport(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight());
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, RenderSystem.getWidth(), RenderSystem.getHeight(), 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glTranslated(0, 0, -2000);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glClearColor(0.08F, 0.08F, 0.08F, 1.0F);
    }

    private static void runLoop(long smokeExitAt) {
        while (DesktopAppState.isRunning() && !Display.isCloseRequested()) {
            if (smokeExitAt > 0 && System.currentTimeMillis() >= smokeExitAt) {
                break;
            }

            DesktopAppState.pumpMainThreadTasks();
            DesktopAppState.hudManager().pump();
            handleInput();
            renderFrame();
            Display.update();
            Display.sync(60);
        }
    }

    private static void handleInput() {
        while (Keyboard.next()) {
            if (Keyboard.getEventKeyState() && DesktopAppState.screenManager().getCurrentScreen() != null) {
                DesktopAppState.screenManager().getCurrentScreen().keyTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey());
            }
        }

        while (Mouse.next()) {
            int button = Mouse.getEventButton();
            if (button < 0 || DesktopAppState.screenManager().getCurrentScreen() == null) {
                continue;
            }

            int eventX = RenderSystem.toLogicalMouseX(Mouse.getEventX());
            int eventY = RenderSystem.toLogicalMouseY(RenderSystem.getFramebufferHeight() - Mouse.getEventY());
            if (Mouse.getEventButtonState()) {
                DesktopAppState.screenManager().getCurrentScreen().mouseClicked(eventX, eventY, button);
            } else {
                DesktopAppState.screenManager().getCurrentScreen().mouseReleased(eventX, eventY, button);
            }
        }
    }

    private static void renderFrame() {
        Framebuffer.updateMcFramebuffer();
        Interpolations.calcFrameDelta();
        CursorUtils.resetOverride();

        prepareFrameState();
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

        int mouseX = RenderSystem.toLogicalMouseX(Mouse.getX());
        int mouseY = RenderSystem.toLogicalMouseY(RenderSystem.getFramebufferHeight() - Mouse.getY());
        if (DesktopAppState.screenManager().getCurrentScreen() != null) {
            try {
                DesktopAppState.screenManager().getCurrentScreen().drawScreen(mouseX, mouseY);
                lastRenderError = null;
            } catch (Throwable t) {
                if (lastRenderError != t) {
                    t.printStackTrace();
                    lastRenderError = t;
                }
                renderFatalOverlay(t);
            }
        }

        if (DesktopAppState.preferences().musicToast().getValue()) {
            MusicToast.render();
        }

        CursorUtils.setOverride();
    }

    private static void renderFatalOverlay(Throwable throwable) {
        double width = RenderSystem.getWidth();
        double height = RenderSystem.getHeight();
        Rect.draw(0, 0, width, height, 0xFF1E1E1E);
        double cardWidth = Math.min(640, width - 80);
        double cardHeight = 160;
        double x = width * 0.5 - cardWidth * 0.5;
        double y = height * 0.5 - cardHeight * 0.5;
        Rect.draw(x, y, cardWidth, cardHeight, 0xFF242424);
        Rect.draw(x, y, 6, cardHeight, 0xFFC30218);
        FontManager.pf25bold.drawString("Tritium Music render error", x + 24, y + 28, 0xFFFFFFFF);
        FontManager.pf18.drawString(throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage()), x + 24, y + 72, 0xFFCFCFCF);
        FontManager.pf14.drawString("See terminal output for stack trace.", x + 24, y + 112, 0xFF888888);
    }

    private static void prepareFrameState() {
        RenderSystem.updateDisplayMetrics();

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        StencilClipManager.clear();
        StencilClipManager.disableStencilTest();
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glClearColor(0.1176F, 0.1176F, 0.1176F, 1.0F);

        GL11.glViewport(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight());
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, RenderSystem.getWidth(), RenderSystem.getHeight(), 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glTranslated(0, 0, -2000);
    }

    private static void shutdown() {
        DesktopAppState.setRunning(false);
        try {
            DesktopAppState.savePreferences();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            CloudMusic.shutdown();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            DesktopAppState.hudManager().stop();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            FontManager.deleteLoadedTextures();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        if (Display.isCreated()) {
            Display.destroy();
        }
    }
}
