package tritium.screens.ncm.panels;

import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.screens.ncm.NCMScreen;

import java.util.function.Supplier;

/** Shared favorite control for song rows, the compact player and lyrics view. */
public class FavoriteButton extends IconWidget {
    private record Feedback(long songId, String text, long expiresAt) {}

    private volatile Feedback feedback;
    private final Supplier<Music> songSupplier;
    private boolean accentBackground;

    public FavoriteButton setAccentBackground(boolean accentBackground) {
        this.accentBackground = accentBackground;
        return this;
    }

    public FavoriteButton(double size) {
        this(size, () -> CloudMusic.currentlyPlaying);
    }

    public FavoriteButton(double size, Supplier<Music> songSupplier) {
        super("e", FontManager.icon30, 0, 0, size, size);
        this.songSupplier = songSupplier;
        setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            Music song = this.songSupplier.get();
            if (song == null || CloudMusic.isLikePending(song.getId())) return true;
            feedback = null;
            CloudMusic.toggleLike(song).thenAccept(message ->
                    feedback = new Feedback(song.getId(), message, System.currentTimeMillis() + 4000));
            return true;
        });
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        Music song = songSupplier.get();
        boolean liked = song != null && CloudMusic.isLiked(song.getId());
        boolean pending = song != null && CloudMusic.isLikePending(song.getId());
        boolean available = song != null && CloudMusic.profile != null && CloudMusic.likeList != null;
        setIcon(liked ? "S" : "e");
        setColor(NCMScreen.getColor(liked && !accentBackground
                ? NCMScreen.ColorType.ACCENT : NCMScreen.ColorType.PRIMARY_TEXT));
        float alpha = getAlpha();
        float widgetAlpha = getWidgetAlpha();
        setAlpha(widgetAlpha * (!available || pending ? .45f : 1f));
        super.onRender(mouseX, mouseY);
        setAlpha(widgetAlpha);

        Feedback notice = feedback;
        boolean showFeedback = song != null && notice != null && notice.songId() == song.getId()
                && System.currentTimeMillis() < notice.expiresAt();
        if (testHovered(mouseX, mouseY) || showFeedback) {
            String text = showFeedback ? notice.text()
                    : song == null ? "未在播放"
                    : CloudMusic.profile == null ? "请先登录后收藏歌曲"
                    : CloudMusic.likeList == null ? "收藏列表尚未加载"
                    : pending ? "正在更新收藏…"
                    : liked ? "取消收藏" : "收藏歌曲";
            double width = FontManager.pf14.getStringWidthD(text) + 12;
            double height = FontManager.pf14.getHeight() + 8;
            double x = Math.max(4, Math.min(getX() + (getWidth() - width) * .5,
                    RenderSystem.getWidth() - width - 4));
            double y = getY() - height - 6;
            roundedRect(x, y, width, height, 4, hexColor(0, 0, 0, alpha * .85f));
            FontManager.pf14.drawString(text, x + 6, y + 4, hexColor(1, 1, 1, alpha));
        }
    }
}
