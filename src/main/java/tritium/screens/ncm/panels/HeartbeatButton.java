package tritium.screens.ncm.panels;

import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.widgets.IconWidget;

import java.awt.Color;

/** Separate from the favorite action: this heart contains a pulse. */
public class HeartbeatButton extends IconWidget {
    private volatile String feedback;
    private volatile long feedbackUntil;

    public HeartbeatButton(double size) {
        super(CloudMusic.PlayMode.Heartbeat.getIcon(), FontManager.icon22, 0, 0, size, size);
        setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            feedback = null;
            CloudMusic.toggleHeartbeat().thenAccept(message -> {
                if ("心动模式请求已取消".equals(message)) return;
                feedback = message;
                feedbackUntil = System.currentTimeMillis() + 4500;
            });
            return true;
        });
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        boolean loading = CloudMusic.isHeartbeatLoading();
        boolean active = CloudMusic.playMode == CloudMusic.PlayMode.Heartbeat;
        setColor(Color.WHITE);
        super.onRender(mouseX, mouseY);
        boolean showFeedback = feedback != null && System.currentTimeMillis() < feedbackUntil;
        }
    }



