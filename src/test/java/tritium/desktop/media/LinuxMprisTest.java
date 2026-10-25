package tritium.desktop.media;

import com.google.gson.Gson;
import org.freedesktop.dbus.errors.NotSupported;
import org.freedesktop.dbus.errors.PropertyReadOnly;
import org.freedesktop.dbus.errors.UnknownInterface;
import org.freedesktop.dbus.errors.UnknownProperty;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.PlaybackState;
import tritium.ncm.music.dto.Music;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.desktop.media.MprisInterfaces.*;
import static tritium.ncm.music.PlaybackState.Status.*;

class LinuxMprisTest {
    private final List<MediaCommand> commands = new ArrayList<>();
    private final List<DBusSignal> signals = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private final LinuxMpris backend = new LinuxMpris(commands::add, signals::add, clock::get);
    private final MediaSession session = new MediaSession(backend);
    private final Music song = new Gson().fromJson("""
            {"id":1,"name":"歌曲 🎵","ar":[{"name":"歌手"}],"al":{"name":"专辑","picUrl":"https://example.com/art.jpg"}}
            """, Music.class);

    @Test void publishesTypedMetadataAndExtrapolatesWithoutPositionPropertySignals() {
        update(PLAYING, 10_000);
        Map<String, Variant<?>> properties = backend.GetAll(PLAYER);
        assertEquals("Playing", properties.get("PlaybackStatus").getValue());
        assertEquals("a{sv}", properties.get("Metadata").getSig());
        Map<String, Variant<?>> metadata = metadata();
        assertEquals("o", metadata.get("mpris:trackid").getSig());
        assertEquals("x", metadata.get("mpris:length").getSig());
        assertEquals(60_000_000L, metadata.get("mpris:length").getValue());
        assertEquals("歌曲 🎵", metadata.get("xesam:title").getValue());
        assertEquals("as", metadata.get("xesam:artist").getSig());
        clock.set(2_000_000_000L);
        assertEquals(12_000_000L, backend.GetAll(PLAYER).get("Position").getValue());
        update(PAUSED, 12_000);
        clock.set(20_000_000_000L);
        assertEquals(12_000_000L, backend.GetAll(PLAYER).get("Position").getValue());
        assertEquals(0, signals.stream().filter(Player.Seeked.class::isInstance).count());
        update(PAUSED, 35_000);
        assertEquals(35_000_000L, signals.stream().filter(Player.Seeked.class::isInstance)
                .map(Player.Seeked.class::cast).findFirst().orElseThrow().getPosition());
        assertTrue(signals.stream().filter(Properties.PropertiesChanged.class::isInstance)
                .map(Properties.PropertiesChanged.class::cast)
                .noneMatch(signal -> signal.getPropertiesChanged().containsKey("Position")));
    }

    @Test void validatesTrackSpecificSeeksAndConvertsMicroseconds() {
        update(PAUSED, 0);
        backend.SetPosition(LinuxMpris.trackPath(2), 1_000_000);
        backend.SetPosition(LinuxMpris.trackPath(1), -1);
        backend.SetPosition(LinuxMpris.trackPath(1), 60_000_001);
        assertTrue(commands.isEmpty());
        backend.SetPosition(LinuxMpris.trackPath(1), 2_345_000);
        backend.Seek(-1_500_000);
        backend.PlayPause();
        assertEquals(List.of(MediaCommand.seek(2345, 1), MediaCommand.relativeSeek(-1500),
                MediaCommand.button(MediaCommand.Action.PLAY_PAUSE)), commands);
        backend.close();
        backend.Play();
        assertEquals(3, commands.size());
    }

    @Test void emptySessionDropsArtworkAndCapabilitiesAndNewTracksDoNotEmitSeeked() {
        update(PLAYING, 0);
        assertTrue(metadata().containsKey("mpris:artUrl"));
        Music next = new Gson().fromJson("{\"id\":2,\"name\":\"Next\"}", Music.class);
        session.update(new PlaybackState(next, PAUSED, 0, 10_000, true, true, false, false), 1);
        assertFalse(metadata().containsKey("mpris:artUrl"));
        assertEquals(LinuxMpris.trackPath(2), metadata().get("mpris:trackid").getValue());
        assertFalse(signals.stream().anyMatch(Player.Seeked.class::isInstance));
        session.update(PlaybackState.empty(), 2);
        assertTrue(metadata().isEmpty());
        assertEquals(false, backend.GetAll(PLAYER).get("CanSeek").getValue());
        assertEquals("Stopped", backend.GetAll(PLAYER).get("PlaybackStatus").getValue());
    }

    @Test void propertiesEnforceCapabilitiesAndReflectVolumeOnlyAfterPlayerUpdate() {
        backend.Set(PLAYER, "Volume", new Variant<>(-0.3));
        assertEquals(MediaCommand.volume(0), commands.getFirst());
        backend.volume(0.25);
        assertEquals(0.25, backend.GetAll(PLAYER).get("Volume").getValue());
        assertThrows(NotSupported.class, () -> backend.Set(PLAYER, "Volume", Double.NaN));
        backend.Set(PLAYER, "Rate", 1.0);
        assertThrows(NotSupported.class, () -> backend.Set(PLAYER, "Rate", 2.0));
        assertThrows(PropertyReadOnly.class, () -> backend.Set(PLAYER, "Position", 0L));
        assertThrows(UnknownProperty.class, () -> backend.Get(PLAYER, "Missing"));
        assertThrows(UnknownInterface.class, () -> backend.GetAll("Missing"));
        assertEquals("Tritium Music", backend.GetAll(ROOT).get("Identity").getValue());
        assertEquals("as", backend.GetAll(ROOT).get("SupportedUriSchemes").getSig());
        assertEquals(Long.MAX_VALUE / 1000 * 1000, LinuxMpris.micros(Long.MAX_VALUE));
    }

    private void update(PlaybackState.Status status, long position) {
        session.update(new PlaybackState(song, status, position, 60_000, true, true, false, true), clock.get());
    }

    @SuppressWarnings("unchecked") private Map<String, Variant<?>> metadata() {
        return (Map<String, Variant<?>>) backend.GetAll(PLAYER).get("Metadata").getValue();
    }
}
