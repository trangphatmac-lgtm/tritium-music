package tritium.screens.ncm.panels;

import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.desktop.download.SongDownloads;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.ncm.music.dto.Music;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.StrokeIcons;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.screens.ncm.NCMScreen;

/** A row action with the same hit area and hover treatment as favorite/play-next. */
public final class DownloadButton extends IconWidget {
    private static final double ICON_SCALE = .8;
    private final Music song;
    private boolean onAccent;

    public DownloadButton(double size, Music song) {
        super("", FontManager.icon30, 0, 0, size, size);
        this.song = song;
        setInheritMaterial(true);
        setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            var preferences = DesktopAppState.preferences();
            DesktopAppState.songDownloads().start(song, preferences.downloadDirectory().getValue(),
                    preferences.selectedDownloadQuality());
            return true;
        });
    }

    public DownloadButton setOnAccent(boolean value) { onAccent = value; return this; }

    @Override
    public void onRender(double mouseX, double mouseY) {
        SongDownloads.Status status = DesktopAppState.songDownloads().status(song.getId());
        boolean busy = status != null && status.busy();
        boolean complete = status != null && (status.state() == SongDownloads.State.COMPLETE || status.state() == SongDownloads.State.EXISTS);
        int color = onAccent ? NCMScreen.getColor(NCMScreen.ColorType.ON_ACCENT)
                : status != null && status.state() == SongDownloads.State.FAILED ? 0xFFE16A6A
                : busy || complete ? AppTheme.accent() : AppTheme.foreground();
        setColor(color);
        super.onRender(mouseX, mouseY);
        float opacity = getAlpha() * (busy ? .55f + .25f * (float) Math.sin(System.currentTimeMillis() / 180.0) : 1f);
        int ink = RenderSystem.reAlpha(color, opacity);
        double centerX = getX() + getWidth() / 2, centerY = getY() + getHeight() / 2;
        api.getGLStateManager().pushMatrix();
        try {
            scaleAtPos(centerX, centerY, ICON_SCALE);
            if (complete) StrokeIcons.checkmark(centerX, centerY, 10, 7, 1.4, ink);
            else StrokeIcons.download(centerX, centerY, 1.3, ink);
        } finally {
            api.getGLStateManager().popMatrix();
        }

        if (testHovered(mouseX, mouseY)) {
            String text = status == null ? I18n.text("download.action") : status.text();
            FontManager.pf14.prepareGlyphs(text + "…");
            double left = getParent() == null ? 4 : Math.max(4, getParent().getX() + 4);
            double right = getX() - 6;
            double maxWidth = Math.max(0, right - left - 12);
            if (FontManager.pf14.getStringWidthD(text) > maxWidth) {
                int end = text.length();
                while (end > 0 && FontManager.pf14.getStringWidthD(text.substring(0, end) + "…") > maxWidth)
                    end = text.offsetByCodePoints(end, -1);
                text = text.substring(0, end) + "…";
            }
            double width = FontManager.pf14.getStringWidthD(text) + 12;
            double height = FontManager.pf14.getHeight() + 8;
            double x = Math.max(left, right - width);
            double y = getY() + (getHeight() - height) / 2;
            // Stay inside the row's scroll clip; the pointer remains over the action.
            UiMaterials.surface(x, y, width, height, 4, RenderSystem.reAlpha(AppTheme.inputBackground(), getAlpha() * .95f));
            FontManager.pf14.drawString(text, x + 6, y + 4, RenderSystem.reAlpha(AppTheme.foreground(), getAlpha()));
        }
    }
}
