package tritium.desktop.media;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.TypeRef;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

import static org.freedesktop.dbus.annotations.DBusProperty.Access.READ;
import static org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal.FALSE;

/** Wire signatures and introspection for https://specifications.freedesktop.org/mpris/latest/. */
public final class MprisInterfaces {
    static final String ROOT = "org.mpris.MediaPlayer2";
    static final String PLAYER = ROOT + ".Player";
    static final String PATH = "/org/mpris/MediaPlayer2";
    private MprisInterfaces() { }

    public interface MetadataMap extends TypeRef<Map<String, Variant<?>>> { }

    @DBusInterfaceName(ROOT)
    @DBusProperty(name = "CanQuit", type = Boolean.class, access = READ)
    @DBusProperty(name = "CanRaise", type = Boolean.class, access = READ)
    @DBusProperty(name = "HasTrackList", type = Boolean.class, access = READ)
    @DBusProperty(name = "Identity", type = String.class, access = READ)
    @DBusProperty(name = "SupportedUriSchemes", type = String[].class, access = READ)
    @DBusProperty(name = "SupportedMimeTypes", type = String[].class, access = READ)
    public interface Root extends DBusInterface {
        void Raise();
        void Quit();
    }

    @DBusInterfaceName(PLAYER)
    @DBusProperty(name = "PlaybackStatus", type = String.class, access = READ)
    @DBusProperty(name = "Rate", type = Double.class)
    @DBusProperty(name = "Metadata", type = MetadataMap.class, access = READ)
    @DBusProperty(name = "Volume", type = Double.class)
    @DBusProperty(name = "Position", type = Long.class, access = READ, emitChangeSignal = FALSE)
    @DBusProperty(name = "MinimumRate", type = Double.class, access = READ)
    @DBusProperty(name = "MaximumRate", type = Double.class, access = READ)
    @DBusProperty(name = "CanGoNext", type = Boolean.class, access = READ)
    @DBusProperty(name = "CanGoPrevious", type = Boolean.class, access = READ)
    @DBusProperty(name = "CanPlay", type = Boolean.class, access = READ)
    @DBusProperty(name = "CanPause", type = Boolean.class, access = READ)
    @DBusProperty(name = "CanSeek", type = Boolean.class, access = READ)
    @DBusProperty(name = "CanControl", type = Boolean.class, access = READ, emitChangeSignal = FALSE)
    public interface Player extends DBusInterface {
        void Next();
        void Previous();
        void Pause();
        void PlayPause();
        void Stop();
        void Play();
        void Seek(long offset);
        void SetPosition(DBusPath trackId, long position);
        void OpenUri(String uri);

        class Seeked extends DBusSignal {
            private final long position;
            public Seeked(String path, long position) throws DBusException {
                super(path, position);
                this.position = position;
            }
            public long getPosition() { return position; }
        }
    }
}
