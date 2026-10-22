package tritium.screens.ncm.panels;

import tritium.desktop.AppTheme;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.TextureManager;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.texture.Textures;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.RoundedButtonWidget;
import tritium.rendering.ui.widgets.RoundedImageWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;

import java.util.List;

/** The user's created or saved playlists, using the already-loaded account library. */
public final class PlaylistLibraryPanel extends NCMPanel {
    private static final double MARGIN = 24;
    private static final double LIST_TOP = 76;
    private static final double ROW_HEIGHT = 64 * 2.0 / 3;
    private static final double ROW_SPACING = 8 * 2.0 / 3;
    private static final double COVER_SIZE = 30;
    private static final double COVER_X = 8;
    private static final double COVER_Y = (ROW_HEIGHT - COVER_SIZE) * .5;
    private static final double TEXT_X = COVER_X + COVER_SIZE + 10;
    private final NCMScreen screen;
    private final boolean subscribed;
    private final ScrollPanel list = new ScrollPanel();
    private List<PlayList> source;
    private List<PlayList> playlists = List.of();

    public PlaylistLibraryPanel(NCMScreen screen, boolean subscribed) {
        this.screen = screen;
        this.subscribed = subscribed;
        navigationContent = tritium.screens.ncm.navigation.NavigationContent.playlists(subscribed ? "saved" : "mine",
                () -> I18n.text(subscribed ? "nav.subscribed" : "nav.playlists"), () -> {
                    if (source != CloudMusic.playLists) refreshPlaylists();
                    return playlists;
                }, () -> CloudMusic.playLists == null);
    }

    public boolean isSubscribed() {
        return subscribed;
    }

    @Override
    public void onInit() {
        getChildren().clear();
        refreshPlaylists();
        setBeforeRenderCallback(() -> {
            if (source != CloudMusic.playLists) refreshPlaylists();
        });
        LabelWidget title = new LabelWidget(() -> I18n.text(subscribed ? "nav.subscribed" : "nav.playlists"), FontManager.pf32);
        title.setClickable(false);
        title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        title.setBeforeRenderCallback(() -> title.setPosition(MARGIN, MARGIN)
                .setColor(AppTheme.foreground()).setMaxWidth(Math.max(0, getWidth() - MARGIN * 2 - (subscribed ? 0 : 120))));
        addChild(title);
        LabelWidget subtitle = new LabelWidget(() -> source == null ? I18n.text("library.loading")
                : I18n.text("library.count", playlists.size()), FontManager.pf14bold);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle.setPosition(MARGIN, MARGIN + title.getHeight() + 6)
                .setColor(AppTheme.foreground()).setAlpha(.5f));
        addChild(subtitle);

        if (!subscribed) {
            RoundedButtonWidget cloud = new RoundedButtonWidget(() -> I18n.text("cloud.title"), FontManager.pf16bold);
            cloud.setBeforeRenderCallback(() -> {
                cloud.setBounds(Math.max(84, FontManager.pf16bold.getStringWidthD(I18n.text("cloud.title")) + 24), 28);
                cloud.setPosition(getWidth() - MARGIN - cloud.getWidth(), MARGIN);
                cloud.setRadius(14);
                cloud.setColor(AppTheme.hover());
                cloud.setTextColor(AppTheme.foreground());
            });
            cloud.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                screen.setCurrentPanel(new CloudStoragePanel());
                return true;
            });
            addChild(cloud);
        }

        list.setSpacing(ROW_SPACING);
        list.setBeforeRenderCallback(() -> list.setBounds(MARGIN, LIST_TOP,
                Math.max(0, getWidth() - MARGIN * 2), Math.max(0, getHeight() - LIST_TOP - MARGIN)));
        addChild(list);
        LabelWidget empty = new LabelWidget(() -> I18n.text(source == null ? "library.loading"
                : subscribed ? "library.emptySaved" : "library.emptyMine"), FontManager.pf18bold);
        empty.setClickable(false);
        empty.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        empty.setBeforeRenderCallback(() -> empty.setMaxWidth(Math.max(0, getWidth() - MARGIN * 2))
                .setColor(AppTheme.foreground()).setAlpha(playlists.isEmpty() ? .5f : 0f)
                .setPosition((getWidth() - empty.getWidth()) * .5, Math.max(108, getHeight() * .45)));
        addChild(empty);
    }

    private void refreshPlaylists() {
        source = CloudMusic.playLists;
        playlists = source == null ? List.of() : source.stream()
                .filter(playlist -> playlist.isSubscribed() == subscribed).toList();
        list.getChildren().clear();
        list.actualScrollOffset = list.targetScrollOffset = 0;
        for (PlayList playlist : playlists) list.addChild(new PlaylistRow(playlist));
    }

    private final class PlaylistRow extends AbstractWidget<PlaylistRow> {
        private final PlayList playlist;
        private final boolean liked;
        private boolean coverRequested;

        private PlaylistRow(PlayList playlist) {
            this.playlist = playlist;
            this.liked = CloudMusic.isLikedPlaylist(playlist);
            setBounds(100, ROW_HEIGHT);
            setBeforeRenderCallback(() -> setWidth(getParentWidth()));
            setShouldOverrideMouseCursor(true);
            setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                screen.setCurrentPanel(new PlaylistPanel(playlist));
                return true;
            });
            RoundedImageWidget cover = new RoundedImageWidget(playlist.getCoverLocation(), COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE);
            cover.setClickable(false).setLinearFilter(true).setRadius(5).fadeIn();
            addChild(cover);
            if (liked) {
                HeartbeatButton heartbeat = new HeartbeatButton(20);
                heartbeat.setInheritMaterial(true);
                heartbeat.setBeforeRenderCallback(() -> heartbeat.setPosition(getWidth() - 32,
                        (getHeight() - heartbeat.getHeight()) * .5));
                addChild(heartbeat);
            }
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            if (!coverRequested) {
                coverRequested = true;
                if (playlist.getCoverUrl() != null && !playlist.getCoverUrl().isBlank()
                        && TextureManager.getInstance().getTexture(playlist.getCoverLocation()) == null) {
                    Textures.downloadTextureAndLoadAsync(playlist.getCoverUrl() + "?param=96y96", playlist.getCoverLocation());
                }
            }
            UiMaterials.surface(getX(), getY(), getWidth(), getHeight(), 7,
                    reAlpha(isHovering() ? AppTheme.settingsHover() : AppTheme.settingsSurface(),
                            getAlpha() * AppTheme.backgroundPanelOpacity()));
            double centerY = getY() + getHeight() * .5;
            UiMaterials.control(getX() + COVER_X, getY() + COVER_Y, COVER_SIZE, COVER_SIZE, 5, reAlpha(AppTheme.hover(), getAlpha()));
            FontManager.music18.drawCenteredString("D", getX() + COVER_X + COVER_SIZE * .5,
                    centerY - FontManager.music18.getHeight() * .5,
                    reAlpha(AppTheme.foreground(), getAlpha() * .35f));
            String count = I18n.songCount(playlist.getCount());
            double countX = getWidth() - 44 - FontManager.pf14bold.getStringWidthD(count);
            double textWidth = Math.max(0, countX - TEXT_X - 12);
            String name = playlist.getName() == null ? "" : playlist.getName();
            FontManager.pf20bold.drawCenteredStringVertical(FontManager.pf20bold.trim(name, textWidth), getX() + TEXT_X, centerY,
                    reAlpha(AppTheme.foreground(), getAlpha()));
            FontManager.pf14bold.drawCenteredStringVertical(count,
                    getX() + countX, centerY, reAlpha(AppTheme.foreground(), getAlpha() * .5f));
            if (!liked) FontManager.pf20bold.drawCenteredStringVertical("›", getX() + getWidth() - 20, centerY,
                    reAlpha(AppTheme.foreground(), getAlpha() * .4f));
        }
    }
}
