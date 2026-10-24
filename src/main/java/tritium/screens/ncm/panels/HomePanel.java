package tritium.screens.ncm.panels;

import tritium.i18n.I18n;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import tritium.management.FontManager;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.texture.Textures;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.RoundedImageWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.Location;
import tritium.utils.json.JsonUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author IzumiiKonata
 * Date: 2025/11/4 22:30
 */
public class HomePanel extends NCMPanel {

    public HomePanel() {
        super();
        navigationContent = tritium.screens.ncm.navigation.NavigationContent.playlists("explore", () -> I18n.text("home.recommended"), () -> playLists, () -> loading);
    }

    static final List<PlayList> playLists = new CopyOnWriteArrayList<>();
    private int renderedPlaylistCount = -1;
    private static volatile boolean loading;

    @Override
    public void onInit() {

        layout();
        setBeforeRenderCallback(() -> {
            if (renderedPlaylistCount != playLists.size()) layout();
        });
        if (!playLists.isEmpty() || loading) return;
        loading = true;
        MultiThreadingUtil.runAsync(() -> {
            List<PlayList> loaded = new ArrayList<>();
            try {
                JsonObject response = CloudMusicApi.recommendResource().toJsonObject();
                JsonElement code = response.get("code");
                if (code == null || !code.isJsonPrimitive() || code.getAsInt() != 200) return;
                JsonArray recommend = response.getAsJsonArray("recommend");
                if (recommend == null) return;
                for (JsonElement element : recommend) {
                    if (!element.isJsonObject()) continue;
                    JsonObject object = element.getAsJsonObject();
                    // fix for missing @SerializedName(..., alternate = {})
                    if (object.has("picUrl")) object.addProperty("coverImgUrl", object.get("picUrl").getAsString());
                    if (object.has("playcount")) object.addProperty("playCount", object.get("playcount").getAsLong());
                    loaded.add(JsonUtils.parse(object, PlayList.class));
                }
            } finally {
                MultiThreadingUtil.runOnMainThread(() -> {
                    playLists.addAll(loaded);
                    loading = false;
                    layout();
                });
            }
        });
    }

    public ScrollPanel scrollPanel;

    private void layout() {
        renderedPlaylistCount = playLists.size();
        getChildren().clear();
        LabelWidget lblWelcome = new LabelWidget(() -> I18n.text("home.welcome"), FontManager.pf25bold);

        this.addChild(lblWelcome);

        int margin = 12;

        lblWelcome.setBeforeRenderCallback(() -> lblWelcome
                .setPosition(margin, margin)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));

        LabelWidget lblRecommendations = new LabelWidget(() -> I18n.text("home.recommended"), FontManager.pf14bold);

        this.addChild(lblRecommendations);

        lblRecommendations.setBeforeRenderCallback(() -> lblRecommendations
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(margin, lblWelcome.getRelativeY() + lblWelcome.getHeight() + 2 + margin * .5 - lblRecommendations.getHeight() * .5));

        scrollPanel = new ScrollPanel();

//        RectWidget rw = new RectWidget();
        this.addChild(scrollPanel);

        scrollPanel.setSpacing(margin);

        scrollPanel
                .setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
                .setBeforeRenderCallback(() -> {
                    double top = margin * 2 + lblWelcome.getHeight();
                    scrollPanel.setBounds(margin, top, Math.max(0, getWidth() - margin * 2),
                            Math.max(0, getHeight() - top - scrollBottomInset(margin)));
                });

        playLists.forEach(pl -> scrollPanel.addChild(new PlaylistWidget(pl).setShouldOverrideMouseCursor(true)));

    }

    private static class PlaylistWidget extends AbstractWidget<PlaylistWidget> {

        @Getter
        private final PlayList playList;

        double emphasizeAnim = 0;

        boolean coverLoaded = false;

        public PlaylistWidget(PlayList playList) {
            this.playList = playList;

            double size = 100;
            double emphasizeAnimMax = 5;

            this.setBounds(size + emphasizeAnimMax, size + emphasizeAnimMax + 16);

            RoundedImageWidget cover = new RoundedImageWidget(this::getCoverLocation, 0, 0, size, size);

            this.addChild(cover);

            cover
                    .setClickable(false)
                    .fadeIn()
                    .setLinearFilter(true)
                    .setBeforeRenderCallback(() -> {

                        if (!coverLoaded) {
                            coverLoaded = true;
                            this.loadCover();
                        }

                        this.emphasizeAnim = Interpolations.interpolate(this.emphasizeAnim, cover.isHovering() ? emphasizeAnimMax : 0, .2f);

                        cover
                                .setBounds(size + this.emphasizeAnim)
                                .setRadius(4)
                                .centerHorizontally()
                                .setBounds(cover.getRelativeX(), this.getWidth() * .5 - size * .5 - emphasizeAnim * .5, cover.getWidth(), cover.getHeight());
                    });

            CFontRenderer pf14bold = FontManager.pf14bold;
            LabelWidget lblName = new LabelWidget(() -> String.join("\n", pf14bold.fitWidth(playList.getName(), size)), pf14bold);

            this.addChild(lblName);

            lblName
                    .setClickable(false)
                    .setBeforeRenderCallback(() -> lblName
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                            .setPosition(cover.getRelativeX(), cover.getRelativeY() + cover.getHeight() + 4));

            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList));
                }

                return true;
            });

        }


        @Override
        public void onRender(double mouseX, double mouseY) {

        }

        private void loadCover() {

            TextureManager textureManager = TextureManager.getInstance();
            Location coverLoc = this.getCoverLocation();
            if (textureManager.getTexture(coverLoc) != null)
                return;

            Textures.downloadTextureAndLoadAsync(playList.getCoverUrl() + "?param=256y256", coverLoc);

        }

        private Location getCoverLocation() {
            return Location.of("tritium/textures/playlist/" + this.playList.getId() + "/cover.png");
        }

    }

}
