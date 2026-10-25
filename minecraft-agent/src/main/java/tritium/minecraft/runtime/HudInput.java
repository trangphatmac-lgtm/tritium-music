package tritium.minecraft.runtime;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState.Event;
import java.util.function.Consumer;

/** Pure input state machine, also exercised without Minecraft or OpenGL. */
public final class HudInput {
    private final boolean[] previousKeys = new boolean[256];
    private final Consumer<Event> events;
    private String dragging;
    private double offsetX, offsetY;
    private boolean resizing, sawChat;
    public boolean editing;

    public HudInput(Consumer<Event> events) { this.events = events; }

    public boolean keys(HudConfig config, boolean allowed, boolean[] down) {
        boolean uninject = allowed && rising(config.uninjectKey, down);
        boolean editToggle = allowed && !uninject && rising(config.editKey, down);
        if (editToggle) {
            finish(config); edit(!editing);
            Event event = new Event(); event.action = "edit"; event.enabled = editing; events.accept(event);
        }
        if (allowed && !editing && !uninject && !editToggle) for (HudConfig.Module module : config.modules) {
            if (rising(module.key, down)) {
                module.enabled = !module.enabled;
                Event event = new Event(); event.action = "toggle"; event.id = module.id; event.enabled = module.enabled;
                events.accept(event);
            }
        }
        System.arraycopy(down, 0, previousKeys, 0, Math.min(down.length, previousKeys.length));
        return uninject;
    }
    private boolean rising(int key, boolean[] down) { return key > 0 && key < down.length && down[key] && !previousKeys[key]; }

    public void edit(boolean enabled) {
        editing = enabled; sawChat = false; dragging = null;
    }

    public void tick(HudConfig config, boolean chat, double mouseX, double mouseY, int width, int height) {
        if (!editing) return;
        if (chat) sawChat = true;
        if (!chat && sawChat) {
            finish(config); sawChat = false;
        } else if (chat && dragging != null) move(config, mouseX, mouseY, width, height);
    }

    public boolean mouse(HudConfig config, boolean chat, double mx, double my, int button, boolean pressed,
                         int wheel, int width, int height) {
        if (!editing || !chat) return false;
        if (dragging != null) {
            move(config, mx, my, width, height);
            if (button == 0 && !pressed) finish(config);
            return true;
        }
        for (int i = config.modules.length - 1; i >= 0; i--) {
            HudConfig.Module module = config.modules[i];
            if ("worldLyrics".equals(module.id)) continue;
            double[] b = module.bounds(width, height);
            if (mx < b[0] || my < b[1] || mx > b[0] + b[2] || my > b[1] + b[3]) continue;
            if (wheel != 0) {
                module.scale = HudConfig.clamp(module.scale + Math.signum(wheel) * .05, .5, 2);
                publish(module); return true;
            }
            if (button == 0 && pressed) {
                dragging = module.id;
                resizing = mx >= b[0] + b[2] - 10 && my >= b[1] + b[3] - 10;
                offsetX = resizing ? b[0] : mx - b[0];
                offsetY = resizing ? b[1] : my - b[1];
                return true;
            }
        }
        return false;
    }

    private void move(HudConfig config, double mx, double my, int width, int height) {
        HudConfig.Module module = config.find(dragging);
        if (module == null) return;
        if (resizing) {
            module.width = HudConfig.clamp((mx - offsetX) / module.scale, 100, width / module.scale);
            module.height = HudConfig.clamp((my - offsetY) / module.scale, 24, height / module.scale);
            module.x = HudConfig.clamp(offsetX / Math.max(1, width - module.width * module.scale), 0, 1);
            module.y = HudConfig.clamp(offsetY / Math.max(1, height - module.height * module.scale), 0, 1);
        } else {
            double[] b = module.bounds(width, height);
            module.x = HudConfig.clamp((mx - offsetX) / Math.max(1, width - b[2]), 0, 1);
            module.y = HudConfig.clamp((my - offsetY) / Math.max(1, height - b[3]), 0, 1);
        }
    }
    public void finish(HudConfig config) {
        if (dragging != null) { HudConfig.Module module = config.find(dragging); if (module != null) publish(module); }
        dragging = null;
    }
    public boolean dragging() { return dragging != null; }
    private void publish(HudConfig.Module module) {
        Event event = new Event(); event.action = "layout"; event.id = module.id;
        event.x = module.x; event.y = module.y; event.width = module.width; event.height = module.height; event.scale = module.scale;
        events.accept(event);
    }
}
