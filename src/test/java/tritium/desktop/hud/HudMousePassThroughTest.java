package tritium.desktop.hud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tritium.desktop.MusicPreferences;

import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.MAC, OS.WINDOWS})
@EnabledIfSystemProperty(named = "tritium.testHud", matches = "true")
class HudMousePassThroughTest {
    @Test
    void restoresOverwrittenNativeFlagsWithoutTogglingHud() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HudManager manager = new HudManager();
            try {
                Field smoke = HudManager.class.getDeclaredField("smokeMode");
                smoke.setAccessible(true);
                smoke.setBoolean(manager, true);
                Method ensure = HudManager.class.getDeclaredMethod("ensureRendererWindows");
                ensure.setAccessible(true);
                ensure.invoke(manager);
                Field windows = HudManager.class.getDeclaredField("rendererWindows");
                windows.setAccessible(true);
                MusicPreferences preferences = new MusicPreferences();
                for (Object rendererWindow : (List<?>) windows.get(manager)) {
                    Method sync = rendererWindow.getClass().getDeclaredMethod("sync", MusicPreferences.class);
                    sync.setAccessible(true);
                    Field field = rendererWindow.getClass().getDeclaredField("window");
                    field.setAccessible(true);
                    sync.invoke(rendererWindow, preferences);
                    JWindow window = (JWindow) field.get(rendererWindow);
                    assertTrue(HudPlatformWindow.isPassThroughApplied(window, true));

                    // Simulate a delayed native initialization overwriting the cached state.
                    assertTrue(HudPlatformWindow.apply(window, false));
                    assertTrue(HudPlatformWindow.isPassThroughApplied(window, false));
                    sync.invoke(rendererWindow, preferences);
                    assertTrue(HudPlatformWindow.isPassThroughApplied(window, true));

                    preferences.hud().editMode().setValue(true);
                    sync.invoke(rendererWindow, preferences);
                    assertTrue(HudPlatformWindow.isPassThroughApplied(window, false));
                    preferences.hud().editMode().setValue(false);
                    sync.invoke(rendererWindow, preferences);
                    assertTrue(HudPlatformWindow.isPassThroughApplied(window, true));

                    window.setVisible(false);
                    sync.invoke(rendererWindow, preferences);
                    assertTrue(HudPlatformWindow.isPassThroughApplied(window, true));
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            } finally {
                manager.stop();
            }
        });
    }
}
