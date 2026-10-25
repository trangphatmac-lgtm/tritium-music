package tritium.screens.ncm.panels;

import org.lwjgl.input.Keyboard;
import tritium.desktop.*;
import tritium.desktop.minecraft.MinecraftConnectionManager;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.minecraft.protocol.HudConfig;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.screens.ncm.NCMPanel;
import java.util.*;
import java.util.function.*;

/** Desktop-only control surface. Minecraft never gets a custom screen. */
public final class MinecraftPanel extends NCMPanel {
    private final MinecraftConnectionManager connection = DesktopAppState.minecraft();
    private final MinecraftPreferences preferences = DesktopAppState.preferences().minecraft();
    private final List<ModuleCard> cards = new ArrayList<>();
    private ModuleCard recording;
    private double progress;
    private long readyAt;
    private final Grid grid = new Grid();
    private final Progress view = new Progress();

    @Override public void onInit() {
        getChildren().clear(); cards.clear();
        preferences.ensureInitialized(DesktopAppState.preferences());
        Header header = new Header();
        addChild(header); addChild(view); addChild(grid);
        cards.add(new ModuleCard("musicInfo", "settings.infoHud", 0));
        cards.add(new ModuleCard("musicLyrics", "settings.lyricsHud", 1));
        cards.add(new ModuleCard("musicSpectrum", "settings.spectrumHud", 0));
        cards.add(new ModuleCard("musicToast", "minecraft.toast", 1));
        cards.add(new ModuleCard("uninject", "minecraft.uninject", 0));
        cards.add(new ModuleCard("editHud", "minecraft.editHud", 1));
        for (ModuleCard card : cards) grid.addChild(card);
        setBeforeRenderCallback(() -> {
            boolean ready = connection.status().stage() == MinecraftConnectionManager.Stage.READY;
            if (ready && readyAt == 0) readyAt = System.nanoTime();
            if (!ready) readyAt = 0;
            boolean showCards = readyAt != 0 && System.nanoTime() - readyAt > 350_000_000L;
            header.setHidden(!showCards); grid.setHidden(!showCards); view.setHidden(showCards);
            double top = 104;
            grid.setBounds(24, top, Math.max(0, getWidth() - 48), Math.max(0, getHeight() - top - scrollBottomInset(24)));
            view.setBounds(24, 24, Math.max(0, getWidth() - 48), Math.max(0, getHeight() - 48));
        });
    }
    @Override public void onRender(double mouseX, double mouseY) { }
    public boolean captureKey(int key) {
        if (recording == null) return false;
        if (key == Keyboard.KEY_NONE || Keyboard.isRepeatEvent()) return true;
        if (key != Keyboard.KEY_ESCAPE) {
            int binding = key == Keyboard.KEY_DELETE || key == Keyboard.KEY_BACK ? 0 : key;
            String id = recording.id;
            preferences.update(c -> {
                if (id.equals("uninject")) c.uninjectKey = binding;
                else if (id.equals("editHud")) c.editKey = binding;
                else c.find(id).key = binding;
            });
        }
        recording = null; return true;
    }
    private static String text(String key) { return I18n.text(key); }

    public static final class InjectButton extends AbstractWidget<InjectButton> {
        public InjectButton() {
            setBounds(100, 48); setShouldOverrideMouseCursor(true);
            setBeforeRenderCallback(() -> setBounds(getParentWidth(), 48));
            setOnClickCallback((x, y, button) -> { if (button != 0) return false; DesktopAppState.minecraft().openAndInject(); return true; });
        }
        @Override public void onRender(double x, double y) {
            UiMaterials.control(getX(), getY(), getWidth(), getHeight(), 10, reAlpha(AppTheme.settingsSurface(), getAlpha()));
            double centerY = getY() + getHeight() * .5;
            FontManager.pf16bold.drawCenteredStringVertical("Minecraft", getX() + 16, centerY, reAlpha(AppTheme.foreground(), getAlpha()));
            String action = text(DesktopAppState.minecraft().connected() ? "minecraft.open" : "minecraft.inject");
            double w = FontManager.pf14bold.getStringWidthD(action) + 24;
            UiMaterials.control(getX() + getWidth() - w - 12, centerY - 13, w, 26, 8, reAlpha(AppTheme.accent(), getAlpha()));
            FontManager.pf14bold.drawCenteredString(action, getX() + getWidth() - w / 2 - 12,
                    centerY - FontManager.pf14bold.getFontHeight() * .5, reAlpha(AppTheme.onAccent(), getAlpha()));
        }
    }

    private final class Header extends AbstractWidget<Header> {
        Header() {
            setBeforeRenderCallback(() -> setBounds(24, 24, getParentWidth() - 48, 76));
            setOnClickCallback((x, y, button) -> {
                if (button != 0 || !connection.connected() || y < 34 || y > 66) return false;
                if (x >= 0 && x <= 128) connection.resetLayout(); else return false;
                return true;
            });
        }
        @Override public void onRender(double mx, double my) {
            FontManager.pf32.drawString("Minecraft", getX(), getY(), reAlpha(AppTheme.foreground(), getAlpha()));
            if (connection.connected()) {
                pill(0, 36, 128, text("minecraft.reset"));
            }
        }
        private void pill(double x, double y, double w, String label) {
            UiMaterials.withoutMaterials(() -> UiMaterials.control(getX() + x, getY() + y, w, 28, 8, reAlpha(AppTheme.settingsSurface(), getAlpha())));
            FontManager.pf14bold.drawCenteredString(label, getX() + x + w / 2, getY() + y + 8, reAlpha(AppTheme.foreground(), getAlpha()));
        }
    }

    private final class Progress extends AbstractWidget<Progress> {
        private double contentY, contentLeft, contentWidth;
        Progress() {
            setOnClickCallback((x, y, button) -> {
                if (button != 0 || x < contentLeft || x > contentLeft + contentWidth) return false;
                var status = connection.status();
                if (status.stage() == MinecraftConnectionManager.Stage.SELECTING) {
                    int index = (int)((y - contentY) / 44);
                    if (y >= contentY && index < status.candidates().size()) { connection.select(status.candidates().get(index)); return true; }
                }
                if ((status.stage() == MinecraftConnectionManager.Stage.FAILED || status.stage() == MinecraftConnectionManager.Stage.IDLE)
                        && y >= contentY && y <= contentY + 34) { progress = 0; connection.begin(); return true; }
                return false;
            });
        }
        @Override public void onRender(double mx, double my) {
            var status = connection.status();
            progress += (status.progress() - progress) * .12;
            boolean loading = status.stage() != MinecraftConnectionManager.Stage.FAILED
                    && status.stage() != MinecraftConnectionManager.Stage.SELECTING
                    && status.stage() != MinecraftConnectionManager.Stage.IDLE;
            double centerX = getX() + getWidth() / 2;
            String title = text("minecraft.stage." + status.stage().name());
            if (loading) {
                double width = Math.max(6, Math.min(240, getWidth() - 32));
                double barY = getY() + getHeight() * .46;
                FontManager.pf40bold.drawCenteredString("Tritium Music", centerX, barY - 48,
                        reAlpha(AppTheme.foreground(), getAlpha()));
                drawProgressBar(centerX - width / 2, barY, width);
                FontManager.pf14.drawCenteredString(title, centerX, barY + 22,
                        reAlpha(AppTheme.foreground(), getAlpha() * .48f));
                return;
            }
            double width = Math.max(6, Math.min(500, getWidth() - 32)), left = centerX - width / 2;
            contentLeft = left - getX(); contentWidth = width;
            double y = getY() + Math.max(16, Math.min(120, getHeight() * .22));
            FontManager.pf25bold.drawCenteredString(title, centerX, y, reAlpha(AppTheme.foreground(), getAlpha()));
            y += 42;
            String details = status.detail();
            if (!details.isBlank()) {
                for (String paragraph : details.split("\\n")) for (String line : FontManager.pf14bold.fitWidth(paragraph, width)) {
                    FontManager.pf14bold.drawString(line, left, y, reAlpha(AppTheme.foreground(), getAlpha() * .65f)); y += 18;
                }
                y += 14;
            }
            contentY = y - getY();
            for (var candidate : status.candidates()) {
                roundedRect(left, y, width, 36, 9, reAlpha(AppTheme.settingsSurface(), getAlpha()));
                FontManager.pf16bold.drawString(candidate.name() + "  ·  PID " + candidate.pid(), left + 14, y + 11, reAlpha(AppTheme.foreground(), getAlpha())); y += 44;
            }
            if (status.stage() == MinecraftConnectionManager.Stage.FAILED || status.stage() == MinecraftConnectionManager.Stage.IDLE) {
                roundedRect(left, y, width, 34, 9, reAlpha(AppTheme.accent(), getAlpha()));
                FontManager.pf16bold.drawCenteredString(text("minecraft.retry"), left + width / 2, y + 10, reAlpha(AppTheme.onAccent(), getAlpha()));
            }
        }
        private void drawProgressBar(double left, double y, double width) {
            double height = 6, radius = height / 2;
            double filled = Math.min(width, Math.max(height, width * Math.max(0, Math.min(1, progress))));
            roundedRect(left, y, width, height, radius, reAlpha(AppTheme.foreground(), getAlpha() * .07f));
            // Draw the capsule itself with the rounded shader: an alpha-only stencil mask
            // also writes its transparent corners and would square off the gradient.
            roundedRectGradientHorizontal(left, y, filled, height, radius,
                    new java.awt.Color(reAlpha(AppTheme.accent(), getAlpha() * .72f), true),
                    new java.awt.Color(reAlpha(AppTheme.accent(), getAlpha()), true));
            double wave = (System.nanoTime() / 1_000_000_000.0 % 1.8) / 1.8;
            double shineWidth = Math.min(52, filled);
            roundedRect(left + (filled - shineWidth) * wave, y, shineWidth, height, radius,
                    reAlpha(0xffffffff, getAlpha() * .12f * (float)Math.sin(Math.PI * wave)));
        }
    }

    private final class Grid extends ScrollPanel {
        private double layoutCards(boolean position) {
            boolean two = getWidth() >= 664; double width = two ? (getWidth() - 24) / 2 : getWidth();
            double[] bottom = new double[2];
            for (ModuleCard card : cards) {
                int column = two ? card.column : 0; card.measure(width);
                if (position) card.setPosition(column * (width + 24), bottom[column] - actualScrollOffset);
                bottom[column] += card.getHeight() + 16;
            }
            return Math.max(0, Math.max(bottom[0], bottom[1]) - 16);
        }
        @Override protected double getChildrenHeightSum() { return layoutCards(false); }
        @Override public void alignChildren() { super.alignChildren(); layoutCards(true); }
    }

    private final class ModuleCard extends AbstractWidget<ModuleCard> {
        private final String id, title;
        private final int column;
        private boolean expanded;
        private double keyX, keyWidth;
        ModuleCard(String id, String title, int column) {
            this.id = id; this.title = title; this.column = column;
            setShouldOverrideMouseCursor(true); buildRows();
            for (AbstractWidget<?> row : getChildren())
                row.setBeforeRenderCallback(() -> row.setBounds(Math.max(0, getWidth() - 20), row.getHeight()));
            setOnClickCallback((x, y, button) -> {
                if (button != 0 || y >= 64) return false;
                if (y < 42 && x >= keyX && x <= keyX + keyWidth) { recording = recording == this ? null : this; return true; }
                if (id.equals("editHud")) { connection.setEditing(!connection.editing()); return true; }
                if (y < 42 && x >= getWidth() - 64) {
                    if (id.equals("uninject")) connection.uninject(); else preferences.update(c -> { var m = c.find(id); m.enabled = !m.enabled; });
                } else expanded = !expanded;
                return true;
            });
        }
        private HudConfig.Module module() { return preferences.snapshot().find(id); }
        private void edit(Consumer<HudConfig.Module> edit) { preferences.update(c -> edit.accept(c.find(id))); }
        void measure(double width) {
            double y = 64;
            for (AbstractWidget<?> row : getChildren()) {
                row.setHidden(!expanded); row.setBounds(Math.max(0, width - 20), row.getHeight()); row.setPosition(10, y);
                if (expanded) y += row.getHeight() + 6;
            }
            setBounds(width, expanded ? y + 4 : 64);
        }
        @Override public void renderWidget(double x, double y, int wheel) {
            // This scopes the card AND every expanded child, including the color picker, to Static.
            UiMaterials.withoutMaterials(() -> super.renderWidget(x, y, wheel));
        }
        @Override public void onRender(double mx, double my) {
            int surface = DesktopAppState.preferences().appearance().getValue().equals("Light") ? 0xfff7f7f7 : 0xff111111;
            roundedRect(getX(), getY(), getWidth(), getHeight(), 16, reAlpha(surface, getAlpha()));
            String label = FontManager.pf25bold.trim(text(title), Math.max(60, getWidth() - 150));
            FontManager.pf25bold.drawString(label, getX() + 16, getY() + 18, reAlpha(AppTheme.foreground(), getAlpha()));
            HudConfig config = preferences.snapshot();
            int key = id.equals("uninject") ? config.uninjectKey : id.equals("editHud") ? config.editKey : config.find(id).key;
            String keyText = recording == this ? "…" : key == 0 ? "NONE" : Keyboard.getKeyName(key);
            if (keyText == null) keyText = "NONE";
            keyX = 24 + FontManager.pf25bold.getStringWidthD(label);
            keyWidth = Math.min(72, FontManager.pf14bold.getStringWidthD(keyText) + 14);
            roundedRect(getX() + keyX, getY() + 16, keyWidth, 18, 4, reAlpha(AppTheme.accent(), getAlpha() * .16f));
            FontManager.pf14bold.drawCenteredString(FontManager.pf14bold.trim(keyText, keyWidth - 8), getX() + keyX + keyWidth / 2, getY() + 21, reAlpha(AppTheme.accent(), getAlpha()));
            double right = getX() + getWidth() - 48;
            if (id.equals("uninject")) {
                roundedRect(right - 10, getY() + 16, 42, 20, 6, reAlpha(AppTheme.accent(), getAlpha()));
                FontManager.pf14bold.drawCenteredString(text("common.run"), right + 11, getY() + 22, reAlpha(AppTheme.onAccent(), getAlpha()));
            } else {
                drawToggle(id.equals("editHud") ? connection.editing() : config.find(id).enabled);
            }
            if (recording == this) FontManager.pf14bold.drawString(text("minecraft.keyHint"), getX() + 16, getY() + 46, reAlpha(AppTheme.accent(), getAlpha()));
            if (id.equals("editHud")) return;
            double cx = getX() + getWidth() / 2, cy = getY() + 52, direction = expanded ? -1 : 1;
            chevronStroke(cx - 4, cy - direction * 2, direction * 45);
            chevronStroke(cx, cy + direction * 2, -direction * 45);
        }
        private void drawToggle(boolean on) {
            double right = getX() + getWidth() - 48;
            roundedRect(right, getY() + 22, 24, 10, 5, reAlpha(on ? AppTheme.accent() : AppTheme.foreground(), getAlpha() * .22f));
            roundedRect(right + (on ? 15 : 1), getY() + 23, 8, 8, 4, reAlpha(on ? AppTheme.accent() : AppTheme.foreground(), getAlpha() * (on ? 1 : .5f)));
        }
        private void chevronStroke(double x, double y, double angle) {
            org.lwjgl.opengl.GL11.glPushMatrix();
            try {
                org.lwjgl.opengl.GL11.glTranslated(x, y, 0); org.lwjgl.opengl.GL11.glRotated(angle, 0, 0, 1);
                roundedRect(0, -.5, Math.sqrt(32), 1, .5, reAlpha(AppTheme.foreground(), getAlpha() * .6f));
            } finally { org.lwjgl.opengl.GL11.glPopMatrix(); }
        }
        private void buildRows() {
            if (id.equals("editHud")) return;
            if (id.equals("uninject")) { addChild(new SettingsPanel.ActionRow("minecraft.disconnect", connection::uninject)); return; }
            slider("minecraft.scale", m -> m.scale, (m,v) -> m.scale=v, .5, 2, .05);
            slider("settings.opacity", m -> m.opacity, (m,v) -> m.opacity=v, .05, 1, .05);
            if (id.equals("musicInfo")) toggle("settings.artistLyric", m -> m.artistLyric, (m,v) -> m.artistLyric=v);
            if (id.equals("musicLyrics") || id.equals("musicSpectrum")) {
                slider("minecraft.width", m -> m.width, (m,v) -> m.width=v, 100, 1920, 10);
                slider("minecraft.height", m -> m.height, (m,v) -> m.height=v, 24, 720, 2);
            }
            if (id.equals("musicLyrics")) {
                choice("settings.scrollEffect", m -> m.scrollEffect, (m,v) -> m.scrollEffect=v, "Scroll", "FadeIn", "SlideIn");
                choice("settings.align", m -> m.align, (m,v) -> m.align=v, "Left", "Center", "Right");
                toggle("settings.singleLine", m -> m.singleLine, (m,v) -> m.singleLine=v);
                toggle("settings.shadow", m -> m.shadow, (m,v) -> m.shadow=v);
                toggle("settings.graceScroll", m -> m.graceScroll, (m,v) -> m.graceScroll=v);
                slider("settings.lyricHeight", m -> m.lyricHeight, (m,v) -> m.lyricHeight=v, 14, 50, .5);
                toggle("settings.translation", m -> m.translation, (m,v) -> m.translation=v);
                toggle("settings.roman", m -> m.romanization, (m,v) -> m.romanization=v);
            }
            if (id.equals("musicSpectrum")) {
                choice("settings.spectrumStyle", m -> m.spectrumStyle, (m,v) -> m.spectrumStyle=v, "Rect", "Line");
                toggle("settings.compact", m -> m.compact, (m,v) -> m.compact=v);
                toggle("settings.indicator", m -> m.indicator, (m,v) -> m.indicator=v);
                toggle("settings.absoluteVolume", m -> m.absoluteVolume, (m,v) -> m.absoluteVolume=v);
                slider("settings.multiplier", m -> m.multiplier, (m,v) -> m.multiplier=v, .1, 3, .1);
                addChild(new SettingsPanel.ColorRow("settings.spectrumColor", preference(m -> m.color, (m,v) -> m.color=v), true));
            }
            if (id.equals("musicToast")) {
                slider("minecraft.hold", m -> m.holdSeconds, (m,v) -> m.holdSeconds=v, 1, 15, .5);
                addChild(new SettingsPanel.ActionRow("minecraft.preview", connection::previewToast));
            }
        }
        private <T> PreferenceValue<T> preference(Function<HudConfig.Module,T> get, BiConsumer<HudConfig.Module,T> set) {
            return new PreferenceValue<T>("Minecraft", get.apply(module())) {
                @Override public T getValue() { return get.apply(module()); }
                @Override public void setValue(T value) { edit(m -> set.accept(m, value)); }
            };
        }
        private void toggle(String label, Function<HudConfig.Module,Boolean> get, BiConsumer<HudConfig.Module,Boolean> set) {
            addChild(new SettingsPanel.ToggleRow(label, preference(get, set)));
        }
        private void slider(String label, Function<HudConfig.Module,Double> get, BiConsumer<HudConfig.Module,Double> set, double min, double max, double step) {
            NumberPreference pref = new NumberPreference(label, get.apply(module()), min, max, step) {
                @Override public Double getValue() { return get.apply(module()); }
                @Override public void setValue(Double value) { edit(m -> set.accept(m, value)); }
            };
            addChild(new SettingsPanel.SliderRow(label, pref, value -> String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "")));
        }
        private void choice(String label, Function<HudConfig.Module,String> get, BiConsumer<HudConfig.Module,String> set, String... modes) {
            ModePreference pref = new ModePreference(label, get.apply(module()), modes) {
                @Override public String getValue() { return get.apply(module()); }
                @Override public void setValue(String value) { edit(m -> set.accept(m, value)); }
            };
            addChild(new SettingsPanel.ChoiceRow(label, pref));
        }
    }
}
