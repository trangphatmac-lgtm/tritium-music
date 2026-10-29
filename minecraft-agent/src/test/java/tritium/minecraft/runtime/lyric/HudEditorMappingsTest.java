package tritium.minecraft.runtime.lyric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tritium.minecraft.runtime.hook.GameAccess;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the generated class against real local MCP/SRG GuiScreen bytecode. */
@EnabledIfSystemProperty(named = "minecraft.mappingClasspathFile", matches = ".+")
class HudEditorMappingsTest {
    @Test void createsARealClientScreenWithWorkingOverrides() throws Exception {
        List<URL> urls = new ArrayList<URL>();
        for (String path : Files.readAllLines(Paths.get(System.getProperty("minecraft.mappingClasspathFile"))))
            if (!path.trim().isEmpty()) urls.add(Paths.get(path).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
            Class<?> base = Class.forName("net.minecraft.client.gui.GuiScreen", false, loader);
            List<Integer> events = new ArrayList<Integer>();
            Object screen = HudEditorScreen.create(base, kind -> { events.add(kind); return false; });
            assertTrue(base.isInstance(screen));
            assertFalse((Boolean) GameAccess.method(base, "doesGuiPauseGame", "func_73868_f").invoke(screen));
            GameAccess.method(base, "handleMouseInput", "func_146274_d").invoke(screen);
            GameAccess.method(base, "handleKeyboardInput", "func_146282_l").invoke(screen);
            GameAccess.method(base, "drawScreen", "func_73863_a", int.class, int.class, float.class).invoke(screen, 1, 2, 0f);
            GameAccess.method(base, "onGuiClosed", "func_146281_b").invoke(screen);
            assertEquals(Arrays.asList(2, 14, 15), events);
            ((AutoCloseable) screen).close();
        }
    }
}
