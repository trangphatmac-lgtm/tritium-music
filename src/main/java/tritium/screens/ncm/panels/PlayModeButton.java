package tritium.screens.ncm.panels;

import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.ui.widgets.IconWidget;

/** Shared playback mode control for the compact player and lyrics view. */
public class PlayModeButton extends IconWidget {

    public PlayModeButton(double size) {
        super(CloudMusic.playMode.getIcon(), FontManager.icon30, 0, 0, size, size);
        setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) {
                return false;
            }
            CloudMusic.playMode = CloudMusic.playMode.nextMode();
            return true;
        });
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        CloudMusic.PlayMode mode = CloudMusic.playMode;
        setIcon(mode.getIcon());
        super.onRender(mouseX, mouseY);

        if (testHovered(mouseX, mouseY)) {
            String text = mode.getDisplayName() + " · 点击切换";
            double width = FontManager.pf14.getStringWidthD(text) + 12;
            double height = FontManager.pf14.getHeight() + 8;
            double x = getX() + (getWidth() - width) * .5;
            double y = getY() - height - 6;
            roundedRect(x, y, width, height, 4, hexColor(0, 0, 0, getAlpha() * .85f));
            FontManager.pf14.drawString(text, x + 6, y + 4, hexColor(1, 1, 1, getAlpha()));
        }
    }
}
