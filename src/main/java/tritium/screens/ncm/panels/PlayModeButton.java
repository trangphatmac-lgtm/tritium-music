package tritium.screens.ncm.panels;

import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.rendering.material.UiMaterials;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.desktop.AppTheme;

/** Shared playback mode control for the compact player and lyrics view. */
public class PlayModeButton extends IconWidget {
    private boolean followTheme = true;

    public PlayModeButton setFollowTheme(boolean followTheme) {
        this.followTheme = followTheme;
        return this;
    }

    private volatile String feedback;
    private volatile long feedbackUntil;

    public PlayModeButton(double size) {
        super(CloudMusic.playMode.getIcon(), FontManager.icon30, 0, 0, size, size);
        setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) {
                return false;
            }
            feedback = null;
            CloudMusic.cyclePlayMode().thenAccept(message -> {
                if (CloudMusic.HEARTBEAT_CANCELLED.equals(message)) return;
                feedback = message;
                feedbackUntil = System.currentTimeMillis() + 4500;
            });
            return true;
        });
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        CloudMusic.PlayMode mode = CloudMusic.playMode;
        boolean loading = CloudMusic.isHeartbeatLoading();
        boolean heartbeat = loading || mode == CloudMusic.PlayMode.Heartbeat;
        setIcon(loading ? CloudMusic.PlayMode.Heartbeat.getIcon() : mode.getIcon());
        fr = heartbeat ? FontManager.icon24 : FontManager.icon30;
        setColor(followTheme ? AppTheme.foreground() : 0xFFFFFFFF);
        super.onRender(mouseX, mouseY);

        boolean showFeedback = feedback != null && System.currentTimeMillis() < feedbackUntil;
        if (testHovered(mouseX, mouseY) || showFeedback) {
            String text = loading ? I18n.text("heartbeat.loading")
                    : showFeedback ? feedback
                    : CloudMusic.heartbeatError() != null ? CloudMusic.heartbeatError()
                    : I18n.text("playMode.next", mode.getDisplayName(), mode.nextMode().getDisplayName());
            drawTooltip(text);
        }
    }

    private void drawTooltip(String text) {
        double width = FontManager.pf14.getStringWidthD(text) + 12;
        double height = FontManager.pf14.getHeight() + 8;
        double x = Math.max(4, Math.min(getX() + (getWidth() - width) * .5,
                RenderSystem.getWidth() - width - 4));
        double y = Math.max(4, getY() - height - 6);
        UiMaterials.surface(x, y, width, height, 4, RenderSystem.reAlpha(followTheme ? AppTheme.inputBackground() : 0xFF000000, getAlpha() * .85f));
        FontManager.pf14.drawString(text, x + 6, y + 4, RenderSystem.reAlpha(followTheme ? AppTheme.foreground() : 0xFFFFFFFF, getAlpha()));
    }
}
