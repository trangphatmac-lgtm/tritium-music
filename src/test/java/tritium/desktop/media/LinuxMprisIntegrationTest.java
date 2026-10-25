package tritium.desktop.media;

import com.google.gson.Gson;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.Introspectable;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tritium.ncm.music.PlaybackState;
import tritium.ncm.music.dto.Music;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.desktop.media.MprisInterfaces.*;

/** Run inside dbus-run-session; talks to the exported object over two independent Unix sockets. */
@EnabledIfSystemProperty(named = "tritium.testMpris", matches = "true")
class LinuxMprisIntegrationTest {
    @Test void exportsDiscoverableInterfacesReceivesCommandsEmitsSignalsAndReleasesBusName() throws Exception {
        var commands = new LinkedBlockingQueue<MediaCommand>();
        var propertiesChanged = new LinkedBlockingQueue<Properties.PropertiesChanged>();
        var seeked = new LinkedBlockingQueue<Player.Seeked>();
        try (var client = DBusConnectionBuilder.forSessionBus().withShared(false).build();
             var backend = LinuxMpris.open(commands::add)) {
            var bus = client.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
            assertTrue(bus.NameHasOwner(LinuxMpris.BUS_NAME));
            var properties = client.getRemoteObject(LinuxMpris.BUS_NAME, PATH, Properties.class);
            var player = client.getRemoteObject(LinuxMpris.BUS_NAME, PATH, Player.class);
            var introspection = client.getRemoteObject(LinuxMpris.BUS_NAME, PATH, Introspectable.class).Introspect();
            assertTrue(introspection.contains("org.mpris.MediaPlayer2.Player"));
            assertTrue(introspection.contains("a{sv}"));
            assertTrue(introspection.contains("SetPosition"));
            assertTrue(introspection.contains("Seeked"));
            assertEquals("Tritium Music", unwrap(properties.Get(ROOT, "Identity")));
            client.addSigHandler(Properties.PropertiesChanged.class, propertiesChanged::add);
            client.addSigHandler(Player.Seeked.class, seeked::add);
            Music song = new Gson().fromJson("{\"id\":42,\"name\":\"歌曲 🎵\"}", Music.class);
            var session = new MediaSession(backend);
            session.update(new PlaybackState(song, PlaybackState.Status.PAUSED, 10_000, 60_000,
                    true, true, true, true), System.nanoTime());
            assertNotNull(propertiesChanged.poll(5, TimeUnit.SECONDS));
            Map<String, Variant<?>> all = properties.GetAll(PLAYER);
            assertEquals("Paused", all.get("PlaybackStatus").getValue());
            assertEquals(10_000_000L, all.get("Position").getValue());
            Map<?, ?> metadata = (Map<?, ?>) unwrap(properties.Get(PLAYER, "Metadata"));
            assertEquals("歌曲 🎵", unwrap(metadata.get("xesam:title")));
            assertEquals(new DBusPath(PATH + "/track/t42"), unwrap(metadata.get("mpris:trackid")));
            player.PlayPause();
            assertEquals(MediaCommand.button(MediaCommand.Action.PLAY_PAUSE), commands.poll(5, TimeUnit.SECONDS));
            player.SetPosition(LinuxMpris.trackPath(42), 20_000_000L);
            assertEquals(MediaCommand.seek(20_000, 42), commands.poll(5, TimeUnit.SECONDS));
            player.Seek(-2_000_000L);
            assertEquals(MediaCommand.relativeSeek(-2000), commands.poll(5, TimeUnit.SECONDS));
            properties.Set(PLAYER, "Volume", 0.4);
            assertEquals(MediaCommand.volume(0.4), commands.poll(5, TimeUnit.SECONDS));
            session.update(new PlaybackState(song, PlaybackState.Status.PAUSED, 20_000, 60_000,
                    true, true, true, true), System.nanoTime());
            var seek = seeked.poll(5, TimeUnit.SECONDS);
            assertNotNull(seek);
            assertEquals(20_000_000L, seek.getPosition());
            session.update(PlaybackState.empty(), System.nanoTime());
            assertTrue(((Map<?, ?>) unwrap(properties.Get(PLAYER, "Metadata"))).isEmpty());
            backend.close();
            assertFalse(bus.NameHasOwner(LinuxMpris.BUS_NAME));
        }
    }

    private static Object unwrap(Object value) { return value instanceof Variant<?> variant ? variant.getValue() : value; }
}
