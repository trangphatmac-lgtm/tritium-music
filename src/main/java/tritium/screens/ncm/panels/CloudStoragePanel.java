package tritium.screens.ncm.panels;

import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.CloudStorage;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.RoundedButtonWidget;
import tritium.rendering.ui.widgets.TextFieldWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** A private library view; refreshing replaces its snapshot without changing the playback queue. */
public class CloudStoragePanel extends NCMPanel {
    private final Supplier<List<Music>> loader;
    private final Executor background;
    private final Executor ui;
    private final ScrollPanel songsPanel = new ScrollPanel();
    private final TextFieldWidget search = new TextFieldWidget(FontManager.pf14bold);
    private PlayList snapshot;
    private boolean loading;
    private Supplier<String> status = () -> "";
    private String error = "";

    public CloudStoragePanel() {
        this(CloudStorage::loadAll, MultiThreadingUtil::runAsync, MultiThreadingUtil::runOnMainThread);
    }

    CloudStoragePanel(Supplier<List<Music>> loader, Executor background, Executor ui) {
        this.loader = loader;
        this.background = background;
        this.ui = ui;
    }

    @Override
    public void onInit() {
        if (!getChildren().isEmpty()) return;
        LabelWidget title = new LabelWidget(() -> I18n.text("cloud.title"), FontManager.pf32);
        addChild(title);
        title.setBeforeRenderCallback(() -> title.setPosition(24, 24)
                .setColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT)));

        LabelWidget info = new LabelWidget(() -> status.get(), FontManager.pf14);
        addChild(info);
        info.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        info.setBeforeRenderCallback(() -> info.setPosition(24, 51)
                .setMaxWidth(Math.max(0, getWidth() - 48))
                .setColor(getColor(NCMScreen.ColorType.SECONDARY_TEXT)));

        RoundedButtonWidget refresh = new RoundedButtonWidget(() -> loading ? I18n.text("common.loading") : I18n.text("common.refresh"), FontManager.pf14bold);
        addChild(refresh);
        refresh.setBeforeRenderCallback(() -> {
            double buttonWidth = Math.max(56, FontManager.pf14bold.getStringWidthD(loading
                    ? I18n.text("common.loading") : I18n.text("common.refresh")) + 16);
            refresh.setBounds(getWidth() - buttonWidth - 24, 27, buttonWidth, 20);
            refresh.setRadius(3).setColor(getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            refresh.setTextColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            refresh.setClickable(!loading);
        });
        refresh.setOnClickCallback((x, y, button) -> {
            if (button == 0) refresh();
            return true;
        });

        RoundedButtonWidget play = new RoundedButtonWidget(() -> I18n.text("playlist.playAll"), FontManager.pf16bold);
        addChild(play);
        play.setBeforeRenderCallback(() -> {
            boolean enabled = snapshot != null && !snapshot.musics.isEmpty();
            play.setBounds(24, 78, 68, 22);
            play.setRadius(3).setColor(getColor(NCMScreen.ColorType.ACCENT));
            play.setTextColor(getColor(NCMScreen.ColorType.ON_ACCENT));
            play.setClickable(enabled).setAlpha(enabled ? 1f : .45f);
        });
        play.setOnClickCallback((x, y, button) -> {
            if (button == 0 && snapshot != null) CloudMusic.play(snapshot.musics, 0);
            return true;
        });

        addChild(search);
        search.setPlaceholder(() -> I18n.text("cloud.search"));
        search.setBeforeRenderCallback(() -> {
            double width = Math.min(210, Math.max(80, getWidth() - 128));
            search.setBounds(getWidth() - width - 24, 78, width, 22);
            search.setColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            search.setDisabledTextColor(getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        search.setTextChangedCallback(text -> filter());

        addChild(songsPanel);
        songsPanel.setBeforeRenderCallback(() -> songsPanel.setBounds(24, 114,
                Math.max(0, getWidth() - 48), Math.max(0, getHeight() - 130)));
        refresh();
    }

    void refresh() {
        if (loading) return;
        if (CloudMusic.profile == null) {
            snapshot = null;
            songsPanel.getChildren().clear();
            status = () -> I18n.text("cloud.signIn");
            return;
        }
        long owner = CloudMusic.profile.getId();
        loading = true;
        error = "";
        status = () -> I18n.text("cloud.loading");
        background.execute(() -> {
            List<Music> songs = null;
            Supplier<String> failure = () -> I18n.text("cloud.failed");
            try {
                songs = loader.get();
            } catch (Exception e) {
                if (e instanceof IllegalStateException && e.getMessage() != null && !e.getMessage().isBlank()) {
                    failure = e::getMessage;
                }
            }
            List<Music> loaded = songs;
            Supplier<String> message = failure;
            ui.execute(() -> {
                loading = false;
                if (CloudMusic.profile == null || CloudMusic.profile.getId() != owner) {
                    snapshot = null;
                    songsPanel.getChildren().clear();
                    status = () -> I18n.text("cloud.sessionChanged");
                    return;
                }
                if (loaded == null) {
                    error = message.get();
                    status = message;
                    return;
                }
                snapshot = new PlayList(-1, I18n.text("cloud.title"), "", loaded.size(), 0, CloudMusic.profile, "", false, 0, 0);
                snapshot.musics = List.copyOf(loaded);
                snapshot.musicsLoaded = true;
                songsPanel.getChildren().clear();
                songsPanel.actualScrollOffset = songsPanel.targetScrollOffset = 0;
                for (int i = 0; i < loaded.size(); i++) {
                    songsPanel.addChild(new MusicWidget(loaded.get(i), snapshot, i).setShouldOverrideMouseCursor(true));
                }
                filter();
            });
        });
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        long visible = 0;
        for (var child : songsPanel.getChildren()) {
            Music music = ((MusicWidget) child).music;
            String text = music.getName() + " " + music.getArtistsName() + " " + music.getAlbum().getName();
            boolean matches = text.toLowerCase(Locale.ROOT).contains(query);
            child.setHidden(!matches);
            if (matches) visible++;
        }
        songsPanel.actualScrollOffset = songsPanel.targetScrollOffset = 0;
        if (!loading && error.isEmpty() && snapshot != null) {
            long visibleCount = visible;
            status = () -> snapshot.musics.isEmpty() ? I18n.text("cloud.empty") : query.isEmpty()
                    ? I18n.songCount(snapshot.musics.size()) : I18n.text("cloud.matches",
                            I18n.songCount(visibleCount), I18n.songCount(snapshot.musics.size()));
        }
    }
}
