package tritium.desktop.media;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.errors.NotSupported;
import org.freedesktop.dbus.errors.PropertyReadOnly;
import org.freedesktop.dbus.errors.UnknownInterface;
import org.freedesktop.dbus.errors.UnknownProperty;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;
import tritium.ncm.music.PlaybackState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static tritium.desktop.media.MediaCommand.Action.*;
import static tritium.desktop.media.MprisInterfaces.*;

/** A private session-bus connection; no root service or external playerctl process is needed. */
public final class LinuxMpris implements MediaSession.Backend, Root, Player, Properties {
    static final String BUS_NAME = ROOT + ".tritium.instance" + ProcessHandle.current().pid();
    private final Consumer<MediaCommand> commands;
    private final Consumer<DBusSignal> signals;
    private final LongSupplier clock;
    private DBusConnection connection;
    private MediaSession.Metadata metadata;
    private PlaybackState state = PlaybackState.empty();
    private long position, duration, positionAt;
    private boolean timelineReady, playing, closed;
    private double volume = 1;

    // Injectable transport and monotonic clock keep protocol tests independent of the desktop.
    LinuxMpris(Consumer<MediaCommand> commands, Consumer<DBusSignal> signals, LongSupplier clock) {
        this.commands = commands;
        this.signals = signals;
        this.clock = clock;
    }

    static LinuxMpris open(Consumer<MediaCommand> commands) {
        try {
            return open(DBusConnectionBuilder.forSessionBus().withShared(false).build(), commands);
        } catch (DBusException failure) {
            throw new IllegalStateException("Cannot connect to the D-Bus session bus", failure);
        }
    }

    static LinuxMpris open(DBusConnection connection, Consumer<MediaCommand> commands) {
        LinuxMpris result = new LinuxMpris(commands, connection::sendMessage, System::nanoTime);
        result.connection = connection;
        try {
            connection.exportObject(PATH, result);
            connection.requestBusName(BUS_NAME);
            return result;
        } catch (DBusException | RuntimeException failure) {
            result.close();
            throw new IllegalStateException("Cannot register the MPRIS player", failure);
        }
    }

    @Override public synchronized void metadata(MediaSession.Metadata value) {
        metadata = value;
        position = duration = 0;
        timelineReady = false;
        changed(Map.of("Metadata", metadataValue()));
    }

    @Override public synchronized void playback(PlaybackState value) {
        state = value;
        changed(Map.of("PlaybackStatus", new Variant<>(status()),
                "CanPlay", new Variant<>(state.canPlay()), "CanPause", new Variant<>(state.canPause()),
                "CanGoNext", new Variant<>(state.canNext()), "CanGoPrevious", new Variant<>(state.canPrevious()),
                "CanSeek", new Variant<>(canSeek())));
    }

    @Override public synchronized void timeline(long millis, long total) {
        long now = clock.getAsLong();
        boolean seeked = timelineReady && Math.abs(micros(millis) - positionAt(now)) >= 1_000_000;
        boolean durationChanged = duration != total;
        position = millis;
        duration = total;
        positionAt = now;
        playing = state.status() == PlaybackState.Status.PLAYING;
        timelineReady = true;
        if (durationChanged) changed(Map.of("Metadata", metadataValue()));
        if (seeked) {
            try { signals.accept(new Player.Seeked(PATH, micros(position))); }
            catch (DBusException failure) { throw new IllegalStateException(failure); }
        }
    }

    @Override public synchronized void volume(double value) {
        if (Double.compare(volume, value) == 0) return;
        volume = value;
        changed(Map.of("Volume", new Variant<>(value)));
    }

    @Override public synchronized void clear() {
        metadata = null;
        state = PlaybackState.empty();
        timelineReady = playing = false;
        position = duration = 0;
        var properties = playerProperties();
        properties.remove("Position"); // MPRIS explicitly forbids PropertiesChanged for Position.
        properties.remove("CanControl");
        changed(properties);
    }

    private void changed(Map<String, Variant<?>> values) {
        if (closed) return;
        try { signals.accept(new PropertiesChanged(PATH, PLAYER, values, List.of())); }
        catch (DBusException failure) { throw new IllegalStateException(failure); }
    }

    private String status() {
        return switch (state.status()) {
            case PLAYING -> "Playing";
            case PAUSED -> "Paused";
            default -> "Stopped";
        };
    }

    private boolean canSeek() { return state.canPlay() && state.durationMillis() > 0; }
    static long micros(long millis) { return Math.clamp(millis, 0, Long.MAX_VALUE / 1000) * 1000; }
    static DBusPath trackPath(long id) { return new DBusPath(PATH + "/track/t" + Long.toUnsignedString(id)); }

    private long positionAt(long now) {
        long elapsed = playing ? Math.max(0, (now - positionAt) / 1_000_000) : 0;
        return micros(position + Math.min(elapsed, Math.max(0, duration - position)));
    }

    private Variant<?> metadataValue() {
        Map<String, Variant<?>> data = new LinkedHashMap<>();
        if (metadata != null) {
            data.put("mpris:trackid", new Variant<>(trackPath(metadata.id())));
            data.put("mpris:length", new Variant<>(micros(duration)));
            data.put("xesam:title", new Variant<>(metadata.title()));
            data.put("xesam:artist", new Variant<>(new String[]{metadata.artist()}));
            data.put("xesam:album", new Variant<>(metadata.album()));
            if (!metadata.artworkUrl().isBlank()) data.put("mpris:artUrl", new Variant<>(metadata.artworkUrl()));
        }
        return new Variant<>(data, "a{sv}");
    }

    private Map<String, Variant<?>> playerProperties() {
        Map<String, Variant<?>> values = new LinkedHashMap<>();
        values.put("PlaybackStatus", new Variant<>(status()));
        values.put("Rate", new Variant<>(1.0));
        values.put("MinimumRate", new Variant<>(1.0));
        values.put("MaximumRate", new Variant<>(1.0));
        values.put("Metadata", metadataValue());
        values.put("Volume", new Variant<>(volume));
        values.put("Position", new Variant<>(positionAt(clock.getAsLong())));
        values.put("CanGoNext", new Variant<>(state.canNext()));
        values.put("CanGoPrevious", new Variant<>(state.canPrevious()));
        values.put("CanPlay", new Variant<>(state.canPlay()));
        values.put("CanPause", new Variant<>(state.canPause()));
        values.put("CanSeek", new Variant<>(canSeek()));
        values.put("CanControl", new Variant<>(true));
        return values;
    }

    @Override public synchronized Map<String, Variant<?>> GetAll(String name) {
        if (PLAYER.equals(name)) return playerProperties();
        if (ROOT.equals(name)) return Map.of("CanQuit", new Variant<>(false), "CanRaise", new Variant<>(false),
                "HasTrackList", new Variant<>(false), "Identity", new Variant<>("Tritium Music"),
                "SupportedUriSchemes", new Variant<>(new String[0]), "SupportedMimeTypes", new Variant<>(new String[0]));
        throw new UnknownInterface(name);
    }

    @Override @SuppressWarnings("unchecked") public synchronized <A> A Get(String name, String property) {
        Variant<?> value = GetAll(name).get(property);
        if (value == null) throw new UnknownProperty(property);
        return (A) value; // Get returns a wire variant, including complex metadata.
    }

    @Override public synchronized <A> void Set(String name, String property, A value) {
        if (!GetAll(name).containsKey(property)) throw new UnknownProperty(property);
        Object raw = value instanceof Variant<?> variant ? variant.getValue() : value;
        if (PLAYER.equals(name) && "Volume".equals(property)) {
            if (!(raw instanceof Double level) || !Double.isFinite(level)) throw new NotSupported("Invalid volume");
            send(MediaCommand.volume(Math.clamp(level, 0, 1)));
        } else if (PLAYER.equals(name) && "Rate".equals(property)) {
            if (!(raw instanceof Double rate) || rate != 1.0) throw new NotSupported("Only rate 1.0 is supported");
        } else throw new PropertyReadOnly(property);
    }

    private synchronized void send(MediaCommand command) { if (!closed) commands.accept(command); }
    @Override public void Play() { send(MediaCommand.button(PLAY)); }
    @Override public void Pause() { send(MediaCommand.button(PAUSE)); }
    @Override public void PlayPause() { send(MediaCommand.button(PLAY_PAUSE)); }
    @Override public void Next() { send(MediaCommand.button(NEXT)); }
    @Override public void Previous() { send(MediaCommand.button(PREVIOUS)); }
    @Override public void Seek(long offset) { send(MediaCommand.relativeSeek(offset / 1000)); }
    @Override public synchronized void SetPosition(DBusPath trackId, long position) {
        if (metadata != null && canSeek() && trackPath(metadata.id()).equals(trackId)
                && position >= 0 && position <= micros(state.durationMillis())) {
            send(MediaCommand.seek(position / 1000, metadata.id()));
        }
    }
    // The player exposes pause/resume, not stop, external URI loading or remote application lifecycle.
    @Override public void Stop() { throw new NotSupported("Use Pause to suspend playback"); }
    @Override public void OpenUri(String uri) { throw new NotSupported("External URIs are not supported"); }
    @Override public void Raise() { throw new NotSupported("CanRaise is false"); }
    @Override public void Quit() { throw new NotSupported("CanQuit is false"); }
    @Override public String getObjectPath() { return PATH; }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        // Never wait for D-Bus receiver threads while holding their property/command lock.
        if (connection != null) connection.disconnect();
    }
}
