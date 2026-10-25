package tritium.screens.ncm.panels;

import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.desktop.AppTheme;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.TextureManager;
import tritium.rendering.texture.Textures;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.*;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.Location;

import java.awt.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 22:00
 */
public class NavigateBar extends NCMPanel {

    private final NCMScreen screen;
    private final MusicSearchBar searchBar;
    ScrollPanel playlistPanel = new ScrollPanel();

    public NavigateBar() {
        this(NCMScreen.getInstance(), new MusicSearchBar(NCMScreen.getInstance()));
    }

    public NavigateBar(NCMScreen screen, MusicSearchBar searchBar) {
        this.screen = screen;
        this.searchBar = searchBar;
        this.layout();
    }

    private void layout() {
        RectWidget bg = new RectWidget().setMaterial(true);
        this.addChild(bg);

        this.setBeforeRenderCallback(() -> {
            this.setBounds(screen.getPanelWidth() * .15, screen.getPanelHeight());
            this.setPosition(0, 0);

            bg.setMargin(0);
            bg.setColor(this.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            bg.setAlpha(.9f * AppTheme.backgroundPanelOpacity());
        });

        this.addChild(searchBar);
        searchBar.setBeforeRenderCallback(() -> searchBar.setBounds(8, 8, Math.max(0, getWidth() - 16), 20));

        this.addChild(playlistPanel);
        this.playlistPanel.setBeforeRenderCallback(() -> {
            this.playlistPanel.setMargin(0);
            this.playlistPanel.setPosition(this.playlistPanel.getRelativeX(), searchBar.getRelativeY() + searchBar.getHeight() + 8);
            this.playlistPanel.setBounds(this.playlistPanel.getWidth(), this.playlistPanel.getHeight() - searchBar.getHeight() - 16 - 32);
        });

        this.playlistPanel.setSpacing(4);

        LabelWidget lbl = new LabelWidget("Tritium Music", FontManager.pf14bold);
        lbl.setBeforeRenderCallback(() -> {
            lbl.setColor(Color.GRAY);
            lbl.setPosition(6, lbl.getRelativeY());
        });

        this.playlistPanel.addChild(lbl);

        {
            PlaylistItem item = new PlaylistItem("A", () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT), () -> I18n.text("nav.home"), () -> screen.setCurrentPanel(new HomePanel()));
            item.selectedWhen = () -> screen.getCurrentPanel() instanceof HomePanel;

            item.setShouldOverrideMouseCursor(true);

            this.playlistPanel.addChild(item);
        }

        LabelWidget lblPlaylists = new LabelWidget(() -> I18n.text("nav.playlists"), FontManager.pf14bold);
        if (tritium.desktop.DesktopAppState.minecraft().connected()) {
            PlaylistItem minecraft = new PlaylistItem("D", AppTheme::accent, () -> "Minecraft", () -> screen.setCurrentPanel(new MinecraftPanel()));
            minecraft.selectedWhen = () -> screen.getCurrentPanel() instanceof MinecraftPanel;
            this.playlistPanel.addChild(minecraft);
        }
        lblPlaylists.setBeforeRenderCallback(() -> {
            lblPlaylists.setColor(Color.GRAY);
            lblPlaylists.setPosition(6, lblPlaylists.getRelativeY());
        });

        lblPlaylists.setShouldOverrideMouseCursor(true);
        lblPlaylists.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            screen.setCurrentPanel(new PlaylistLibraryPanel(screen, false));
            return true;
        });
        this.playlistPanel.addChild(lblPlaylists);

        PlaylistItem cloudStorage = new PlaylistItem("D", Color.GRAY::getRGB, () -> I18n.text("cloud.title"),
                () -> screen.setCurrentPanel(new CloudStoragePanel()));
        cloudStorage.setShouldOverrideMouseCursor(true);
        cloudStorage.selectedWhen = () -> screen.getCurrentPanel() instanceof CloudStoragePanel;
        this.playlistPanel.addChild(cloudStorage);

        List<PlayList> pl = CloudMusic.playLists;

        if (pl != null) {
            List<PlayList> playLists = pl.stream().filter(playList -> !playList.isSubscribed()).toList();
            for (int i = 0; i < playLists.size(); i++) {
                PlayList playList = playLists.get(i);
                PlaylistItem item = new PlaylistItem(i == 0 ? "C" : "D", Color.GRAY::getRGB, playList::getName, () -> screen.setCurrentPanel(new PlaylistPanel(playList)));
                item.selectedWhen = () -> isCurrentPlaylist(playList);
                item.setShouldOverrideMouseCursor(true);

                if (CloudMusic.isLikedPlaylist(playList)) {
                    item.trailingWidth = 24;
                    HeartbeatButton heartbeatButton = new HeartbeatButton(16);
                    heartbeatButton.setInheritMaterial(true);
                    item.addChild(heartbeatButton);
                    heartbeatButton.setBeforeRenderCallback(() -> heartbeatButton.setPosition(
                            item.getWidth() - heartbeatButton.getWidth() - 8, 0));
                }

                this.playlistPanel.addChild(item);
            }
        }

        LabelWidget lblSubscribed = new LabelWidget(() -> I18n.text("nav.subscribed"), FontManager.pf14bold);
        lblSubscribed.setBeforeRenderCallback(() -> {
            lblSubscribed.setColor(Color.GRAY);
            lblSubscribed.setPosition(6, lblSubscribed.getRelativeY());
        });

        lblSubscribed.setShouldOverrideMouseCursor(true);
        lblSubscribed.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            screen.setCurrentPanel(new PlaylistLibraryPanel(screen, true));
            return true;
        });
        this.playlistPanel.addChild(lblSubscribed);

        if (pl != null) {
            pl.stream().filter(PlayList::isSubscribed).forEach(playList -> {
                PlaylistItem item = new PlaylistItem("D", Color.GRAY::getRGB, playList::getName, () -> screen.setCurrentPanel(new PlaylistPanel(playList)));
                item.selectedWhen = () -> isCurrentPlaylist(playList);
                item.setShouldOverrideMouseCursor(true);

                this.playlistPanel.addChild(item);
            });
        }

        RoundedImageWidget creatorAvatar = new RoundedImageWidget(this.getUserAvatarLocation(), 0, 0, 0, 0);
        this.addChild(creatorAvatar);
        creatorAvatar.fadeIn();
        creatorAvatar.setLinearFilter(true);
        creatorAvatar.setShouldOverrideMouseCursor(true);
        creatorAvatar.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                screen.setCurrentPanel(new SettingsPanel());
            }

            return true;
        });

        this.loadAvatar();

        creatorAvatar.setBeforeRenderCallback(() -> {
            creatorAvatar.setBounds(16, 16);
            creatorAvatar.setPosition(12, this.getHeight() - 8 - creatorAvatar.getHeight());
            creatorAvatar.setRadius(7.25);
        });

        LabelWidget lblCreator = new LabelWidget(() -> CloudMusic.profile == null ? I18n.text("login.signedOut") : CloudMusic.profile.getName(), FontManager.pf16bold);
        this.addChild(lblCreator);

        lblCreator.setBeforeRenderCallback(() -> {
            lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4, creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
            lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int mouseButton) {
        return false;
    }

    private boolean isCurrentPlaylist(PlayList playlist) {
        return screen.getCurrentPanel() instanceof PlaylistPanel panel && playlist.equals(panel.playList);
    }

    private void loadAvatar() {

        if (CloudMusic.profile == null) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location avatarLoc = this.getUserAvatarLocation();
        if (textureManager.getTexture(avatarLoc) != null)
            return;

        Textures.downloadTextureAndLoadAsync(CloudMusic.profile.getAvatarUrl() + "?param=32y32", avatarLoc);
    }

    private Location getUserAvatarLocation() {
        if (CloudMusic.profile == null) {
            return null;
        }

        return CloudMusic.profile.getAvatarLocation();
    }

    @Override
    public void onInit() {

    }

    public static class PlaylistItem extends Panel {

        String icon;
        Supplier<Integer> iconColorSupplier;
        Supplier<String> label;
        Runnable onClick;
        RoundedRectWidget bg = new RoundedRectWidget();
        double trailingWidth;

        BooleanSupplier selectedWhen = () -> false;

        public boolean isSelected() {
            return selectedWhen.getAsBoolean();
        }

        public PlaylistItem(String icon, Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this.icon = icon;
            this.iconColorSupplier = iconColorSupplier;
            this.label = label;
            this.onClick = onClick;

            this.setBeforeRenderCallback(() -> {
                this.setBounds(this.getParentWidth(), 16);
                this.setPosition(4, this.getRelativeY());
                bg.setHidden(!isSelected());
            });

            bg.setClickable(false);

            this.addChild(bg);
            this.bg.setBeforeRenderCallback(() -> {
                bg.setMargin(0);
                bg.setColor(Color.BLACK);
                bg.setAlpha(isSelected() ? 0.2f : 0f);
                bg.setRadius(4);
            });

            LabelWidget lblIcon = new LabelWidget(icon, FontManager.music18);
            this.addChild(lblIcon);
            lblIcon.setBeforeRenderCallback(() -> {
                lblIcon.setColor(iconColorSupplier.get());
                lblIcon.centerVertically();
                lblIcon.setPosition(8, lblIcon.getRelativeY()/* + .5*/);
            });

            lblIcon.setClickable(false);

            LabelWidget lbl = new LabelWidget(label, FontManager.pf14bold);
            this.addChild(lbl);
            lbl.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);

            lbl.setBeforeRenderCallback(() -> {
                lbl.centerVertically();
                lbl.setPosition(lblIcon.getRelativeX() + lblIcon.getWidth() + 4, lbl.getRelativeY());
                lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lbl.setMaxWidth(Math.max(0, this.getWidth() - lbl.getRelativeX() - 8 - trailingWidth));
            });

            lbl.setClickable(false);

            this.setOnClickCallback(((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    this.onClick.run();
                }

                return true;
            }));
        }

    }
}
