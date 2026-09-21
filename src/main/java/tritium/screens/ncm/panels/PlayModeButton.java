package tritium.screens.ncm.panels;

import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.widgets.IconWidget;
import java.awt.Color;

/** Shared playback mode control for the compact player and lyrics view. */
public class PlayModeButton extends IconWidget {
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
                if ("心动模式请求已取消".equals(message)) return;
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
        setColor(Color.WHITE);
        super.onRender(mouseX, mouseY);

        boolean showFeedback = feedback != null && System.currentTimeMillis() < feedbackUntil;
        if (testHovered(mouseX, mouseY) || showFeedback) {
            String text = loading ? "正在加载心动推荐 · 点击切换到顺序播放"
                    : showFeedback ? feedback
                    : CloudMusic.heartbeatError() != null ? CloudMusic.heartbeatError()
                    : mode.getDisplayName() + " · 点击切换到" + mode.nextMode().getDisplayName();
            drawTooltip(text);
        }
    }

    private void drawTooltip(String text) {
        double width = FontManager.pf14.getStringWidthD(text) + 12;
        double height = FontManager.pf14.getHeight() + 8;
        double x = Math.max(4, Math.min(getX() + (getWidth() - width) * .5,
                RenderSystem.getWidth() - width - 4));
        double y = Math.max(4, getY() - height - 6);
        roundedRect(x, y, width, height, 4, hexColor(0, 0, 0, getAlpha() * .85f));
        FontManager.pf14.drawString(text, x + 6, y + 4, hexColor(1, 1, 1, getAlpha()));
    }
}
