package tritium.screens.ncm.panels;

import org.lwjgl.input.Keyboard;
import tritium.desktop.AppTheme;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.TextFieldWidget;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.concurrent.CopyOnWriteArrayList;

/** Shared by both navigation layouts, including text and keyboard focus. */
public final class MusicSearchBar extends Panel {
    private final NCMScreen screen;
    private final TextFieldWidget field = new TextFieldWidget(FontManager.pf16bold);

    public MusicSearchBar(NCMScreen screen) {
        this.screen = screen;
        LabelWidget icon = new LabelWidget("K", FontManager.music18);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> icon.setPosition(8, (getHeight() - icon.getHeight()) * .5)
                .setColor(AppTheme.foreground()).setAlpha(.5f));
        addChild(icon);
        field.setPlaceholder(() -> I18n.text("nav.search"));
        field.drawUnderline(false);
        field.setBeforeRenderCallback(() -> {
            double inset = icon.getRelativeX() + icon.getWidth() + 6;
            field.setBounds(inset, 2, Math.max(0, getWidth() - inset - 8), getHeight() - 4);
            field.setColor(AppTheme.foreground());
            field.setDisabledTextColor(reAlpha(AppTheme.foreground(), .45f));
        });
        field.setOnKeyTypedCallback((character, keyCode) -> {
            if (!field.isFocused()) return false;
            if (keyCode == Keyboard.KEY_ESCAPE) field.setFocused(false);
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) submit();
            return true;
        });
        addChild(field);
        setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            field.setFocused(true);
            return true;
        });
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        double radius = getHeight() * .5;
        if (UiMaterials.enabled()) {
            // A single glass surface avoids stacking two rims and shadows around the input.
            UiMaterials.control(getX(), getY(), getWidth(), getHeight(), radius,
                    reAlpha(AppTheme.hover(), getAlpha() * .55f));
            return;
        }
        UiMaterials.control(getX(), getY(), getWidth(), getHeight(), radius,
                reAlpha(AppTheme.foreground(), getAlpha() * .25f));
        UiMaterials.control(getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2, radius - 1,
                reAlpha(AppTheme.surface(), getAlpha()));
    }

    public void focus() {
        field.setFocused(true);
        field.getTextField().selectAll();
    }

    public void unfocusOutside(double x, double y) {
        if (!testHovered(x, y)) field.setFocused(false);
    }

    private void submit() {
        String query = field.getText().strip();
        if (query.isEmpty()) return;
        // Capture the query before dispatch; typing another query must not change this request.
        PlayList playlist = new PlayList(0, query, "", 0, 0, null, "", false, 0, 0);
        playlist.setSearchMode(true);
        playlist.musics = new CopyOnWriteArrayList<>();
        PlaylistPanel results = new PlaylistPanel(playlist);
        results.setSearchStatus("search.loading");
        screen.setCurrentPanel(results);
        MultiThreadingUtil.runAsync(() -> {
            try {
                var songs = CloudMusic.search(query);
                MultiThreadingUtil.runOnMainThread(() -> {
                    playlist.musics.addAll(songs);
                    results.setSearchStatus(songs.isEmpty() ? "search.empty" : "");
                    results.onInit();
                });
            } catch (Exception e) {
                MultiThreadingUtil.runOnMainThread(() -> {
                    results.setSearchStatus("search.failed");
                    results.onInit();
                });
            }
        });
    }
}
