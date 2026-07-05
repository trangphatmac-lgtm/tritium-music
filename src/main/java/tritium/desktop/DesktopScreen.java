package tritium.desktop;

public abstract class DesktopScreen {
    public void initGui() {
    }

    public void onGuiClosed() {
    }

    public abstract void drawScreen(int mouseX, int mouseY);

    public void keyTyped(char typedChar, int keyCode) {
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
    }

    public void mouseReleased(int mouseX, int mouseY, int mouseButton) {
    }
}
