package tritium.desktop;

import tritium.desktop.input.DesktopTextInput;

public class ScreenManager {
    private DesktopScreen currentScreen;

    public DesktopScreen getCurrentScreen() {
        return currentScreen;
    }

    public void displayScreen(DesktopScreen screen) {
        if (currentScreen == screen) {
            return;
        }

        DesktopTextInput.clearFocus();
        if (currentScreen != null) {
            currentScreen.onGuiClosed();
        }

        currentScreen = screen;

        if (currentScreen != null) {
            currentScreen.initGui();
        }
    }
}
