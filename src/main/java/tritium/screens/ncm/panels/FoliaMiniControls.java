package tritium.screens.ncm.panels;

import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.widget.impl.MusicLyricsWidget;

import java.util.Locale;
import java.util.List;

/** Folia's expanded capsule layout, backed by Tritium's shared playback and favorite actions. */
public final class FoliaMiniControls extends ControlsBar {
    // Logical units: the default 2x UI scale produces Folia's 480 x 72 pixel capsule.
    public static final double WIDTH = 240;
    public static final double HEIGHT = 36;
    public static final double COLLAPSED_HEIGHT = 24;
    public static final double BOTTOM_GAP = 16;
    private static final double CONTENT_X = 38;
    private final FoliaMiniExpansion expansion = new FoliaMiniExpansion(
            !DesktopAppState.preferences().miniAutoCollapse().getValue());
    private List<AbstractWidget<?>> expandedWidgets = List.of();
    private Progress expandedProgress;
    private Progress collapsedProgress;
    private TrackButton previousTrack;
    private TrackButton nextTrack;
    private float trackButtonsAlpha;

    public void arrange(double left, double availableWidth, double bottom, double mouseX, double mouseY,
                        boolean focused, boolean captured) {
        arrange(left, availableWidth, bottom, mouseX, mouseY, focused, captured, System.nanoTime());
    }

    void arrange(double left, double availableWidth, double bottom, double mouseX, double mouseY,
                 boolean focused, boolean captured, long now) {
        position(left, availableWidth, bottom);
        expansion.update(DesktopAppState.preferences().miniAutoCollapse().getValue(),
                focused && contains(mouseX, mouseY), captured, now);
        position(left, availableWidth, bottom);
    }

    private void position(double left, double availableWidth, double bottom) {
        double fullWidth = Math.min(WIDTH, Math.max(1, availableWidth - 24));
        double amount = expansion.amount();
        double width = fullWidth * (.6 + .4 * amount);
        double height = COLLAPSED_HEIGHT + (HEIGHT - COLLAPSED_HEIGHT) * amount;
        setBounds(left + (availableWidth - width) * .5, bottom - BOTTOM_GAP - height, width, height);
    }

    public void cancelInteraction() {
        if (expandedProgress != null) expandedProgress.draggingPlayer = null;
        if (collapsedProgress != null) collapsedProgress.draggingPlayer = null;
    }

    /** Rounded corners remain transparent to the page's pointer input too. */
    public boolean contains(double mouseX, double mouseY) {
        double radius = getHeight() * .5;
        double centerX = Math.max(getX() + radius, Math.min(mouseX, getX() + getWidth() - radius));
        double dx = mouseX - centerX, dy = mouseY - (getY() + radius);
        return dx * dx + dy * dy <= radius * radius;
    }

    @Override
    public void onInit() {
        getChildren().clear();
        IconWidget play = new IconWidget("B", FontManager.icon30, 6, 6, 24, 24) {
            @Override
            public void onRender(double mouseX, double mouseY) {
                boolean available = CloudMusic.player != null && CloudMusic.currentlyPlaying != null;
                boolean paused = !available || CloudMusic.player.isPausing();
                roundedRect(getX(), getY(), getWidth(), getHeight(), 12,
                        reAlpha(AppTheme.foreground(), getAlpha() * (available ? 1f : .45f)));
                setIcon(paused ? "B" : "A");
                setColor(AppTheme.contrastingText(AppTheme.foreground()));
                super.onRender(mouseX, mouseY);
            }
        };
        play.setInheritMaterial(true);
        play.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null)
                CloudMusic.setPaused(!CloudMusic.player.isPausing());
            return true;
        });
        addChild(play);

        LabelWidget title = new LabelWidget(() -> CloudMusic.currentlyPlaying == null
                ? I18n.text("player.idle") : CloudMusic.currentlyPlaying.getName(), FontManager.pf14bold);
        title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        title.setBeforeRenderCallback(() -> {
            double width = Math.max(1, contentWidth() - 32);
            title.setMaxWidth(width).setColor(AppTheme.foreground())
                    .setPosition(CONTENT_X + 16 + Math.max(0, width - FontManager.pf14bold.getStringWidthD(title.getLabel())) * .5,
                            9);
        });
        title.setShouldOverrideMouseCursor(true);
        title.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (CloudMusic.currentlyPlaying != null)
                NCMScreen.getInstance().toggleLyrics();
            return true;
        });
        addChild(title);

        Progress progress = new Progress();
        expandedProgress = progress;
        progress.setBeforeRenderCallback(() -> progress.setBounds(CONTENT_X, 21, contentWidth(), 10));
        addChild(progress);

        PlayModeButton mode = new PlayModeButton(16);
        mode.setInheritMaterial(true);
        mode.setFitIconToBounds(true, Math.sqrt(.6));
        mode.setBeforeRenderCallback(() -> mode.setPosition(getWidth() - 42, 10));
        addChild(mode);

        FavoriteButton favorite = new FavoriteButton(16);
        favorite.setInheritMaterial(true);
        favorite.setFitIconToBounds(true, Math.sqrt(.6));
        favorite.setBeforeRenderCallback(() -> favorite.setPosition(getWidth() - 24, 10));
        addChild(favorite);

        previousTrack = new TrackButton(false);
        previousTrack.setBeforeRenderCallback(() -> previousTrack.setBounds(CONTENT_X, 5, 14, 14));
        addChild(previousTrack);
        nextTrack = new TrackButton(true);
        nextTrack.setBeforeRenderCallback(() -> nextTrack.setBounds(CONTENT_X + contentWidth() - 14, 5, 14, 14));
        addChild(nextTrack);

        expandedWidgets = List.copyOf(getChildren());
        collapsedProgress = new Progress();
        collapsedProgress.setBeforeRenderCallback(() -> collapsedProgress.setBounds(16,
                (getHeight() - 10) * .5, Math.max(1, getWidth() - 32), 10));
        addChild(collapsedProgress);
    }

    private double contentWidth() {
        return Math.max(1, getWidth() - CONTENT_X - 50);
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        double amount = expansion.amount();
        for (AbstractWidget<?> widget : expandedWidgets) {
            widget.setHidden(amount == 0).setAlpha((float) (amount * amount)).setClickable(amount == 1);
        }
        boolean overTitle = mouseX >= getX() + CONTENT_X && mouseX <= getX() + CONTENT_X + contentWidth()
                && mouseY >= getY() + 5 && mouseY <= getY() + 20;
        boolean available = CloudMusic.player != null && CloudMusic.currentlyPlaying != null && !CloudMusic.playList.isEmpty();
        trackButtonsAlpha = Interpolations.interpolate(trackButtonsAlpha, overTitle && available ? 1f : 0f, .25f);
        for (TrackButton button : List.of(previousTrack, nextTrack)) {
            button.setHidden(amount == 0 || !available)
                    .setAlpha((float) (amount * amount) * trackButtonsAlpha)
                    .setClickable(amount == 1 && overTitle && available);
        }
        collapsedProgress.setHidden(amount == 1).setAlpha((float) (1 - amount)).setClickable(amount == 0);
        UiMaterials.surface(getX(), getY(), getWidth(), getHeight(), getHeight() * .5,
                reAlpha(AppTheme.surface(), getAlpha() * (float) (.55 + .39 * amount)));
    }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        boolean transitioning = expansion.amount() > 0 && expansion.amount() < 1;
        if (transitioning) StencilClipManager.beginClip(() ->
                roundedRect(getX(), getY(), getWidth(), getHeight(), getHeight() * .5, 0xFFFFFFFF));
        try {
            super.renderWidget(mouseX, mouseY, dWheel);
        } finally {
            if (transitioning) StencilClipManager.endClip();
        }
    }

    static String formatTime(float millis) {
        long seconds = Float.isFinite(millis) ? Math.max(0, (long) (millis / 1000)) : 0;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Folia reveals track navigation beside the title without occupying the action slots. */
    static final class TrackButton extends AbstractWidget<TrackButton> {
        private final boolean next;

        TrackButton(boolean next) {
            this.next = next;
            setShouldOverrideMouseCursor(true);
        }

        @Override
        public boolean onMouseClicked(double x, double y, int button) {
            if (button != 0 || CloudMusic.player == null || CloudMusic.currentlyPlaying == null
                    || CloudMusic.playList.isEmpty()) return false;
            if (next) CloudMusic.next(); else CloudMusic.prev();
            return true;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            boolean hovered = testHovered(mouseX, mouseY);
            if (hovered) roundedRect(getX(), getY(), getWidth(), getHeight(), 7,
                    reAlpha(AppTheme.foreground(), getAlpha() * .12f));
            double cx = getX() + getWidth() * .5, cy = getY() + getHeight() * .5;
            double length = Math.sqrt(12.5);
            int color = reAlpha(AppTheme.foreground(), getAlpha() * (hovered ? 1 : .7f));
            for (int direction : new int[]{-1, 1}) {
                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(cx, cy + direction * 1.25, 0);
                api.getGLStateManager().rotate((next ? -1 : 1) * direction * 45, 0, 0, 1);
                api.getGLStateManager().scale(1, .6, 1);
                roundedRect(-length / 2 - 1, -1.5, length + 2, 3, .5, color);
                api.getGLStateManager().popMatrix();
            }
        }
    }

    /** Capture only presses in the seek target; commit once on release, including outside the pill. */
    static final class Progress extends AbstractWidget<Progress> {
        private AudioPlayer draggingPlayer;
        private float preview;

        Progress() {
            setShouldOverrideMouseCursor(true);
        }

        private float duration() {
            return CloudMusic.player == null ? 0 : CloudMusic.player.getTotalTimeMillis();
        }

        private double timeWidth() {
            return Math.max(14, FontManager.pf9.getStringWidthD(formatTime(duration())));
        }

        private double trackX() { return timeWidth() + 4; }
        private double trackWidth() { return Math.max(1, getWidth() - 2 * trackX()); }

        private float seekTime(double relativeX) {
            double fraction = Math.max(0, Math.min(1, (relativeX - trackX()) / trackWidth()));
            return (float) (fraction * duration());
        }

        @Override
        public boolean onMouseClicked(double relativeX, double relativeY, int button) {
            float total = duration();
            if (button != 0 || relativeX < trackX() - 2 || relativeX > trackX() + trackWidth() + 2
                    || !Float.isFinite(total) || total <= 0) return false;
            draggingPlayer = CloudMusic.player;
            preview = seekTime(relativeX);
            return true;
        }

        @Override
        public void onMouseReleased(double relativeX, double relativeY, int button) {
            if (button != 0 || draggingPlayer == null) return;
            if (draggingPlayer == CloudMusic.player && Float.isFinite(duration()) && duration() > 0) {
                float time = seekTime(relativeX);
                CloudMusic.seek(time);
                MusicLyricsWidget.resetProgress(time);
                MusicLyricsPanel.resetProgress(time);
            }
            draggingPlayer = null;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            if (draggingPlayer != CloudMusic.player) draggingPlayer = null;
            if (draggingPlayer != null) preview = seekTime(mouseX - getX());
            float total = duration();
            float current = draggingPlayer != null ? preview
                    : CloudMusic.player == null ? 0 : CloudMusic.player.getCurrentTimeMillis();
            double fraction = Float.isFinite(current) && Float.isFinite(total) && total > 0
                    ? Math.max(0, Math.min(1, current / total)) : 0;
            double x = getX() + trackX(), y = getY() + 3.5;
            double width = trackWidth();
            int foreground = reAlpha(AppTheme.foreground(), getAlpha() * .8f);
            roundedRect(x, y, width, 3, 1.5, reAlpha(AppTheme.foreground(), getAlpha() * .16f));
            if (fraction > 0) roundedRect(x, y, width * fraction, 3, 1.5, foreground);
            if (total > 0) roundedRect(x + width * fraction - 1, y + .5, 2, 2, 1, foreground);
            int secondary = reAlpha(AppTheme.secondary(), getAlpha() * .65f);
            double textY = getY() + (getHeight() - FontManager.pf9.getHeight()) * .5;
            FontManager.pf9.drawString(formatTime(current), getX(), textY, secondary);
            String end = formatTime(total);
            FontManager.pf9.drawString(end, getX() + getWidth() - FontManager.pf9.getStringWidthD(end), textY, secondary);
        }
    }
}
