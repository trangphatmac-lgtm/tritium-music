package tritium.desktop;

import tritium.rendering.MusicToast;

public class DesktopApi {
    private final DesktopGLStateManager glStateManager = new DesktopGLStateManager();
    private final DesktopShaderUtil shaderUtil = new DesktopShaderUtil();

    public DesktopGLStateManager getGLStateManager() {
        return glStateManager;
    }

    public DesktopShaderUtil getShaderUtil() {
        return shaderUtil;
    }

    public void displayScreen(DesktopScreen screen) {
        DesktopAppState.screenManager().displayScreen(screen);
    }

    public void printMessage(String message) {
        System.out.println(message);
        MusicToast.pushMusicToast(message);
    }
}
