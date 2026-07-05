package tritium.desktop;

public class ScreenManager {
    private DesktopScreen currentScreen;

    public DesktopScreen getCurrentScreen() {
        return currentScreen;
    }

    public void displayScreen(DesktopScreen screen) {
        if (currentScreen == screen) {
            return;
        }

        if (currentScreen != null) {
            currentScreen.onGuiClosed();
        }

        currentScreen = screen;

        if (currentScreen != null) {
            currentScreen.initGui();
        }
    }
}
