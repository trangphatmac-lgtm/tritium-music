package tritium.screens.ncm;

import tritium.i18n.I18n;
import tritium.rendering.material.UiMaterials;
import lombok.Getter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import tritium.desktop.DesktopScreen;
import tritium.desktop.DesktopAppState;
import org.lwjgl.opengl.Display;
import tritium.desktop.AppTheme;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.ncm.OptionsUtil;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.widgets.RectWidget;
import tritium.screens.ncm.panels.ControlsBar;
import tritium.screens.ncm.panels.HomePanel;
import tritium.screens.ncm.panels.NavigateBar;
import tritium.screens.ncm.panels.TopNavigateBar;
import tritium.screens.ncm.panels.MusicSearchBar;
import tritium.screens.ncm.panels.PlaylistLibraryPanel;
import tritium.screens.ncm.panels.PlaylistPanel;
import tritium.screens.ncm.panels.CloudStoragePanel;
import tritium.screens.ncm.panels.SettingsPanel;
import tritium.utils.KeyboardUtils;
import tritium.utils.cursor.CursorUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 19:47
 */
public class NCMScreen extends DesktopScreen implements SharedConstants, SharedRenderingConstants {

    @Getter
    private static NCMScreen instance = new NCMScreen();

    float alpha = 0f;
    boolean closing = false;

    Panel basePanel = new Panel();

    @Getter
    NavigateBar playlistsPanel;

    private NCMPanel navigation;
    private MusicSearchBar searchBar;
    private boolean topBarLayout;
    private tritium.screens.ncm.navigation.CoverNavigationView activeCoverNavigation;
    private String activeNavigationMode = "";

    private tritium.screens.ncm.navigation.CoverNavigationView coverNavigation() {
        return currentPanel == null || DesktopAppState.preferences().navigation().isList() ? null : currentPanel.getCoverNavigation();
    }

    private void syncCoverNavigation() {
        var view = coverNavigation();
        var prefs = DesktopAppState.preferences().navigation();
        String mode = prefs.mode().getValue() + prefs.coverflow().getValue();
        if (view != activeCoverNavigation || !mode.equals(activeNavigationMode)) {
            var previous = activeCoverNavigation;
            if (previous != null) previous.leave();
            activeCoverNavigation = view;
            activeNavigationMode = mode;
            if (view != null) view.enter(previous);
            if (searchBar != null) searchBar.bindBrowser(view);
        }
    }

    public void navigateBack() {
        if (currentActionPointer > 0) actions.get(--currentActionPointer).run();
        else setCurrentPanel(new SettingsPanel());
    }

    RectWidget currentPanelBg = new RectWidget();

    float prevAnimatingPanelAlpha = 0f;
    NCMPanel prevAnimatingPanel = null;
    @Getter
    NCMPanel currentPanel = null;
    float curPanelAlphaAnimation = 0f;

    @Getter
    ControlsBar controlsBar;

    public MusicLyricsPanel musicLyricsPanel = null;

    /**
     * 表示是否需要重新布局, 当用户信息和用户歌单加载完设置为 true,
     * 然后会自动进行重新布局并设为 false
     */
    private boolean dirty = true;

    public NCMScreen() {

    }

    @Override
    public void initGui() {
        alpha = 1f;
        closing = false;

        this.checkDirty();

        if (this.musicLyricsPanel != null)
            this.musicLyricsPanel.onInit();

        Keyboard.enableRepeatEvents(true);
        CursorUtils.setCursor(CursorUtils.ARROW);
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void checkDirty() {
        if (this.dirty) {
            this.dirty = false;
            this.layout();

            // only when logged in
            if (CloudMusic.profile != null)
                this.setCurrentPanel(new HomePanel());
        } else if (topBarLayout != isTopBarLayout()) {
            // Only replace navigation. Keep page/scroll state, history, and player controls.
            this.layoutNavigation();
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        DesktopAppState.background().pause();
        if (activeCoverNavigation != null) activeCoverNavigation.leave();
    }

    public void layout() {
        this.basePanel.setBeforeRenderCallback(() -> this.basePanel.center());
        if (searchBar == null) searchBar = new MusicSearchBar(this);
        this.layoutNavigation();

        this.currentPanelBg.setBeforeRenderCallback(() -> {
            double left = topBarLayout ? 0 : navigation.getWidth();
            double top = topBarLayout ? navigation.getHeight() : 0;
            this.currentPanelBg.setBounds(left, top, this.getPanelWidth() - left,
                    Math.max(0, this.getPanelHeight() * .93 - top));
            this.currentPanelBg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            this.currentPanelBg.setAlpha((activeCoverNavigation != null || DesktopAppState.background().isActive() || UiMaterials.enabled()) ? 0f : 1f);
        });

        this.controlsBar = new ControlsBar();
        this.controlsBar.onInit();
    }

    private void layoutNavigation() {
        this.basePanel.getChildren().clear();
        topBarLayout = isTopBarLayout();
        searchBar.setBeforeRenderCallback(() -> { });
        if (topBarLayout) {
            playlistsPanel = null;
            navigation = new TopNavigateBar(this, searchBar);
        } else {
            playlistsPanel = new NavigateBar(this, searchBar);
            navigation = playlistsPanel;
        }
        this.basePanel.addChild(navigation);
        this.basePanel.addChild(currentPanelBg);
    }

    public boolean isTopBarLayout() {
        return DesktopAppState.preferences().layout().getValue().equals("TopBar");
    }

    public enum NavigationSection { EXPLORE, MY_PLAYLISTS, SAVED_PLAYLISTS, SETTINGS }

    public NavigationSection getNavigationSection() {
        if (currentPanel instanceof SettingsPanel) return NavigationSection.SETTINGS;
        if (currentPanel instanceof PlaylistLibraryPanel library)
            return library.isSubscribed() ? NavigationSection.SAVED_PLAYLISTS : NavigationSection.MY_PLAYLISTS;
        if (currentPanel instanceof CloudStoragePanel) return NavigationSection.MY_PLAYLISTS;
        if (currentPanel instanceof PlaylistPanel playlist && !playlist.playList.isSearchMode()) {
            if (CloudMusic.playLists != null) {
                for (var owned : CloudMusic.playLists) {
                    if (owned.equals(playlist.playList)) return owned.isSubscribed()
                            ? NavigationSection.SAVED_PLAYLISTS : NavigationSection.MY_PLAYLISTS;
                }
            }
            if (playlist.playList.isSubscribed()) return NavigationSection.SAVED_PLAYLISTS;
        }
        return NavigationSection.EXPLORE;
    }

    public double getSpacing() {
        return 0.0;
    }

    public double getPanelWidth() {
        return RenderSystem.getWidth() - this.getSpacing() * 2;
    }

    public double getPanelHeight() {
        return RenderSystem.getHeight() - this.getSpacing() * 2;
    }

    @Override
    public void drawScreen(int mX, int mY) {
        if (closing && alpha <= 0.02f)
            api.displayScreen(null);

        alpha = closing ? Interpolations.interpolate(alpha, 0f, 0.4f) : 1f;

        CursorUtils.resetOverride();

//        Shaders.GAUSSIAN_BLUR_SHADER.run(Collections.singletonList(() -> {
//            Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), hexColor(1, 1, 1, alpha));
//        }));

        this.checkDirty();

        syncCoverNavigation();
        int dWheel = Mouse.getDWheel();

        RenderSystem.FIXED_SCALE = false;
        api.getGLStateManager().pushMatrix();
        double mouseX = mX;
        double mouseY = mY;

        UiMaterials.beginFrame(mouseX, mouseY);
        boolean coverLyrics = this.musicLyricsPanel != null;
        var coverSource = tritium.desktop.background.cover.CoverArtwork.source(
                currentPanel instanceof PlaylistPanel playlist ? playlist.playList : null,
                CloudMusic.currentlyPlaying, coverLyrics, DesktopAppState.preferences().background().coverMode().getValue());
        DesktopAppState.background().render(Display.isVisible()
                && (this.musicLyricsPanel == null || this.musicLyricsPanel.alpha < .999f
                    || DesktopAppState.preferences().background().appliesToLyrics()), coverSource, coverLyrics);

        // Draw the cover field as part of the scene BEFORE the material capture, so the
        // original glass/blur controls refract the covers underneath them in this same frame.
        if (activeCoverNavigation != null && CloudMusic.profile != null
                && (musicLyricsPanel == null || musicLyricsPanel.alpha <= .9f)) {
            basePanel.setBounds(0, 0, getPanelWidth(), getPanelHeight());
            if (navigation instanceof TopNavigateBar top) top.arrange();
            else navigation.setBounds(0, 0, getPanelWidth() * .15, getPanelHeight());
            double left = topBarLayout ? 0 : navigation.getWidth();
            double top = topBarLayout ? navigation.getHeight() : 0;
            double bottom = getPanelHeight() * .93;
            activeCoverNavigation.setSafeArea(left, top, getPanelWidth() - left, bottom - top);
            boolean overShell = mouseX < left || mouseY < top || mouseY >= bottom;
            activeCoverNavigation.render(0, 0, getPanelWidth(), getPanelHeight(),
                    mouseX, mouseY, overShell ? 0 : dWheel);
        }
        if (musicLyricsPanel == null || musicLyricsPanel.alpha <= .9f) UiMaterials.captureBackdrop(false);

        boolean loggedIn = CloudMusic.profile != null;
        if (!loggedIn) {
            renderLoginGate(mouseX, mouseY);
            this.renderDownloadingPanel();
            api.getGLStateManager().popMatrix();
            RenderSystem.FIXED_SCALE = false;
            CursorUtils.setOverride();
            api.getGLStateManager().enableTexture2D();
            return;
        }

        if (activeCoverNavigation != null && DesktopAppState.preferences().navigation().isFullscreen()) {
            basePanel.setBounds(0, 0, getPanelWidth(), getPanelHeight());
            // The same shell and search field remain the only navigation controls.
            navigation.setAlpha(alpha);
            navigation.renderWidget(mouseX, mouseY, dWheel);
            renderLyricsPanel(mouseX, mouseY, dWheel);
            renderDownloadingPanel();
            api.getGLStateManager().popMatrix();
            RenderSystem.FIXED_SCALE = false;
            CursorUtils.setOverride();
            api.getGLStateManager().enableTexture2D();
            return;
        }

        this.basePanel.setBounds(this.getPanelWidth(), this.getPanelHeight());

        if (this.musicLyricsPanel == null || this.musicLyricsPanel.alpha <= .9f) {
            this.basePanel.setAlpha(alpha);
            this.basePanel.renderWidget(mouseX, mouseY, dWheel);

            float alphaInterpolateSpeed = 0.4f;
            if (activeCoverNavigation != null) {
                prevAnimatingPanel = null;
                currentPanel.setBounds(currentPanelBg.getX(), currentPanelBg.getY(), currentPanelBg.getWidth(), currentPanelBg.getHeight());
            } else if (this.prevAnimatingPanel != null) {
                this.prevAnimatingPanel.setAlpha(this.prevAnimatingPanelAlpha = Interpolations.interpolate(this.prevAnimatingPanelAlpha, 0f, alphaInterpolateSpeed));
                this.prevAnimatingPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                api.getGLStateManager().pushMatrix();
                this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 0.9 + (this.prevAnimatingPanel.getAlpha() * 0.1));
                this.prevAnimatingPanel.renderWidget(mouseX, mouseY, dWheel);
                api.getGLStateManager().popMatrix();

                if (this.prevAnimatingPanelAlpha <= 0.02f)
                    this.prevAnimatingPanel = null;
            } else if (this.currentPanel != null) {
                curPanelAlphaAnimation = Interpolations.interpolate(curPanelAlphaAnimation, 1f, alphaInterpolateSpeed);
                this.currentPanel.setAlpha(Math.min(this.basePanel.getAlpha(), curPanelAlphaAnimation));
                this.currentPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));

                api.getGLStateManager().pushMatrix();
                this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 1.1 - (curPanelAlphaAnimation * 0.1));

                this.currentPanel.renderWidget(mouseX, mouseY, dWheel);
                api.getGLStateManager().popMatrix();

                StencilClipManager.endClip();
            }

            this.controlsBar.setAlpha(alpha);
            double controlsY = this.currentPanelBg.getY() + this.currentPanelBg.getHeight();
            this.controlsBar.setBounds(this.currentPanelBg.getX(), controlsY, this.currentPanelBg.getWidth(), this.getPanelHeight() - controlsY);
            this.controlsBar.renderWidget(mouseX, mouseY, dWheel);
        }

        renderLyricsPanel(mouseX, mouseY, dWheel);

        this.renderDownloadingPanel();

        api.getGLStateManager().popMatrix();
        RenderSystem.FIXED_SCALE = false;
        CursorUtils.setOverride();
        api.getGLStateManager().enableTexture2D();

    }

    private void renderLyricsPanel(double mouseX, double mouseY, int dWheel) {
        if (this.musicLyricsPanel != null) {
            StencilClipManager.beginClip(() -> Rect.draw(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(), -1));
            if (!DesktopAppState.background().isAppliedToLyrics()) {
                Rect.draw(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(), RenderSystem.reAlpha(0xFF1E1E1E, this.musicLyricsPanel.alpha));
            }
            this.musicLyricsPanel.onRender(mouseX, mouseY, basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(), dWheel);
            StencilClipManager.endClip();

            if (this.musicLyricsPanel.shouldClose())
                this.musicLyricsPanel = null;
        }
    }

    private void renderLoginGate(double mouseX, double mouseY) {
        if (CloudMusic.loadingSession) {
            renderSessionLoading();
            if (System.currentTimeMillis() - CloudMusic.loadingSessionStartedAt <= 8000L) {
                return;
            }
        }

        if (this.loginRenderer == null) {
            this.loginRenderer = new LoginRenderer();
        }

        this.loginRenderer.render(mouseX, mouseY, 0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), 1f);

        if (this.loginRenderer.canClose() && !OptionsUtil.getCookie().isEmpty()) {
            this.loginRenderer = null;
            MultiThreadingUtil.runAsync(() -> {
                CloudMusic.loadingSession = true;
                CloudMusic.loadingSessionStartedAt = System.currentTimeMillis();
                CloudMusic.sessionStatusText = I18n.text("login.loadingUser");
                try {
                    CloudMusic.loadNCM(OptionsUtil.getCookie());
                } finally {
                    CloudMusic.loadingSession = false;
                    CloudMusic.loadingSessionStartedAt = 0L;
                    CloudMusic.sessionStatusText = "";
                }

                MultiThreadingUtil.runOnMainThread(() -> {
                    this.layout();

                    if (CloudMusic.profile != null)
                        this.setCurrentPanel(new HomePanel());
                });
            });
        }
    }

    private void renderSessionLoading() {
        double centerX = RenderSystem.getWidth() * .5;
        double centerY = RenderSystem.getHeight() * .5;
        double cardWidth = Math.min(360, RenderSystem.getWidth() * .4);
        double cardHeight = 112;
        double cardX = centerX - cardWidth * .5;
        double cardY = centerY - cardHeight * .5;

        UiMaterials.panel(cardX, cardY, cardWidth, cardHeight, RenderSystem.reAlpha(AppTheme.color(0xFF222222, 0xFFF5F5F5), 230 / 255f));
        Rect.draw(cardX, cardY, 4, cardHeight, AppTheme.accent());

        long elapsed = Math.max(0L, System.currentTimeMillis() - CloudMusic.loadingSessionStartedAt);
        double progress = ((elapsed % 1800L) / 1800.0);
        double barWidth = cardWidth - 48;
        Rect.draw(cardX + 24, cardY + cardHeight - 28, barWidth, 4, hexColor(70, 70, 70, 255));
        Rect.draw(cardX + 24, cardY + cardHeight - 28, Math.max(24, barWidth * progress), 4, AppTheme.accent());

        FontManager.pf25bold.drawCenteredString(I18n.text("login.loadingSession"), centerX, centerY - FontManager.pf25bold.getHeight(), RenderSystem.reAlpha(AppTheme.foreground(), alpha));
        if (!CloudMusic.sessionStatusText.isBlank()) {
            FontManager.pf18.drawCenteredString(CloudMusic.sessionStatusText, centerX, centerY + 10, hexColor(180, 180, 180, alpha));
        }
    }

    public boolean downloading = false;
    public double downloadProgress = 0;
    public String downloadSpeed = "0 b/s";
    float downloadPanelAlpha = 0.0f;

    private void renderDownloadingPanel() {
//        this.downloading = true;
        this.downloadPanelAlpha = Interpolations.interpolate(this.downloadPanelAlpha, this.downloading ? 1f : 0f, 0.3f);

        if (this.downloadPanelAlpha <= 0.02f)
            return;

        double downloadPanelWidth = 240;
        double downloadPanelHeight = 60;
        double progressBarWidth = downloadPanelWidth - 16;
        double progressBarHeight = 8;

        double offsetY = 8 + -(8 + downloadPanelHeight) * (1 - downloadPanelAlpha);
        int background = musicLyricsPanel != null ? 0xFF202020 : AppTheme.color(0xFF202020, 0xFFF5F5F5);
        int foreground = musicLyricsPanel != null ? 0xFFFFFFFF : AppTheme.foreground();
        UiMaterials.panel(RenderSystem.getWidth() * .5 - downloadPanelWidth * .5, offsetY, downloadPanelWidth, downloadPanelHeight, RenderSystem.reAlpha(background, downloadPanelAlpha * alpha));
        FontManager.pf34bold.drawCenteredString("Downloading...", RenderSystem.getWidth() * .5, offsetY + 8, RenderSystem.reAlpha(foreground, downloadPanelAlpha * alpha));
        FontManager.pf25bold.drawCenteredString(String.valueOf(downloadSpeed), RenderSystem.getWidth() * .5, offsetY + 8 + FontManager.pf34bold.getHeight(), RenderSystem.reAlpha(foreground, downloadPanelAlpha * alpha));
        UiMaterials.control(RenderSystem.getWidth() * .5 - progressBarWidth * .5, offsetY + downloadPanelHeight - 8 - progressBarHeight, progressBarWidth, progressBarHeight, 3, RenderSystem.reAlpha(foreground, .5f * downloadPanelAlpha * alpha));

        StencilClipManager.beginClip(() -> Rect.draw(RenderSystem.getWidth() * .5 - progressBarWidth * .5, offsetY + downloadPanelHeight - 8 - progressBarHeight, progressBarWidth * downloadProgress, progressBarHeight, -1));
        UiMaterials.control(RenderSystem.getWidth() * .5 - progressBarWidth * .5, offsetY + downloadPanelHeight - 8 - progressBarHeight, progressBarWidth, progressBarHeight, 3, RenderSystem.reAlpha(foreground, downloadPanelAlpha * alpha));
        StencilClipManager.endClip();
    }

    public LoginRenderer loginRenderer = null;

    int currentActionPointer = 0;
    List<Runnable> actions = new ArrayList<>();

    public void setCurrentPanel(NCMPanel panel) {
        this.innerSetCurrentPanel(panel, true);

        if (panel != null) {
            Runnable action = () -> this.innerSetCurrentPanel(panel, false);

            if (actions.isEmpty()) {
                currentActionPointer = 0;
                actions.add(action);
            } else {
                ++ currentActionPointer;

                while (actions.size() > currentActionPointer + 1)
                    actions.removeLast();

                if (currentActionPointer < actions.size()) {
                    actions.set(currentActionPointer, action);
                } else {
                    actions.add(action);
                }
            }
        }
    }

    private void innerSetCurrentPanel(NCMPanel panel, boolean shouldCallInit) {
        if (activeCoverNavigation != null) activeCoverNavigation.leave();
        this.prevAnimatingPanel = activeCoverNavigation == null ? this.currentPanel : null;
        this.prevAnimatingPanelAlpha = 1.0f;
        this.currentPanel = panel;
        if (panel != null) {
            if (shouldCallInit)
                this.currentPanel.onInit();
            this.currentPanel.setAlpha(0);
            this.curPanelAlphaAnimation = 0f;
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (this.loginRenderer != null && this.loginRenderer.keyTyped(typedChar, keyCode)) {
            return;
        }

        if (musicLyricsPanel == null && searchBar != null && searchBar.isFocused()
                && basePanel.onKeyTypedReceived(typedChar, keyCode)) return;
        if (musicLyricsPanel == null && coverNavigation() != null && coverNavigation().key(typedChar, keyCode)) return;

        if (this.musicLyricsPanel == null && searchBar != null && CloudMusic.profile != null
                && (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_F) || KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_G))) {
            searchBar.focus();
            return;
        }

        if (this.musicLyricsPanel == null && this.basePanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (this.musicLyricsPanel == null && coverNavigation() == null && this.currentPanel != null && this.currentPanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (this.musicLyricsPanel != null) {
                this.musicLyricsPanel.close();
            }
        }

        if (keyCode == Keyboard.KEY_SPACE && CloudMusic.currentlyPlaying != null && CloudMusic.player != null && !CloudMusic.player.isFinished()) {
            if (CloudMusic.player.isPausing())
                CloudMusic.player.unpause();
            else
                CloudMusic.player.pause();
        }

    }

    @Override
    public void mouseClicked(int mX, int mY, int mouseButton) {

        double mouseX = mX;
        double mouseY = mY;

        if (this.loginRenderer != null && this.loginRenderer.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }

        if (musicLyricsPanel == null) {
            if (mouseButton == 3) {
                if (currentActionPointer > 0) actions.get(--currentActionPointer).run();
                return;
            }
            if (mouseButton == 4) {
                if (currentActionPointer < actions.size() - 1) actions.get(++currentActionPointer).run();
                return;
            }
            if (searchBar != null) searchBar.unfocusOutside(mouseX, mouseY);
            this.basePanel.onMouseClickReceived(mouseX, mouseY, mouseButton);
            if (mouseButton < 3 && navigation != null
                    && mouseX >= navigation.getX() && mouseX <= navigation.getX() + navigation.getWidth()
                    && mouseY >= navigation.getY() && mouseY <= navigation.getY() + navigation.getHeight()) return;

            if (coverNavigation() != null && DesktopAppState.preferences().navigation().isFullscreen()) {
                coverNavigation().click(mouseX, mouseY, mouseButton);
                return;
            }
            if (coverNavigation() != null) {
                if (mouseY < controlsBar.getY()) coverNavigation().click(mouseX, mouseY, mouseButton);
            } else if (this.currentPanel != null)
                this.currentPanel.onMouseClickReceived(mouseX, mouseY, mouseButton);

            this.controlsBar.onMouseClickReceived(mouseX, mouseY, mouseButton);


        } else {
            this.musicLyricsPanel.mouseClicked(mouseX, mouseY, mouseButton);
        }

    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int mouseButton) {
        if (coverNavigation() != null && musicLyricsPanel == null) {
            coverNavigation().release(mouseX, mouseY, mouseButton);
            if (DesktopAppState.preferences().navigation().isFullscreen()) return;
        }
        basePanel.onMouseReleaseReceived(mouseX, mouseY, mouseButton);
        if (currentPanel != null) currentPanel.onMouseReleaseReceived(mouseX, mouseY, mouseButton);
        if (prevAnimatingPanel != null) prevAnimatingPanel.onMouseReleaseReceived(mouseX, mouseY, mouseButton);
        if (controlsBar != null) controlsBar.onMouseReleaseReceived(mouseX, mouseY, mouseButton);
    }

    public enum ColorType {

        ACCENT,
        ON_ACCENT,
        GENERIC_BACKGROUND,
        ELEMENT_BACKGROUND,
        ELEMENT_HOVER,
        PRIMARY_TEXT,
        SECONDARY_TEXT

    }

    public static int getColor(ColorType type) {

        return switch (type) {
            case ACCENT -> AppTheme.accent();
            case ON_ACCENT -> AppTheme.onAccent();
            case GENERIC_BACKGROUND -> AppTheme.background();
            case ELEMENT_BACKGROUND -> AppTheme.surface();
            case ELEMENT_HOVER -> AppTheme.hover();
            case PRIMARY_TEXT -> AppTheme.foreground();
            case SECONDARY_TEXT -> DesktopAppState.preferences().background().mode().getValue().equals("latent")
                    ? AppTheme.secondary() : 0x6B6B6B;
        };

    }
}
