package tritium.desktop.media;

import com.google.gson.Gson;
import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Pointer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tritium.ncm.music.PlaybackState;
import tritium.ncm.music.dto.Music;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.desktop.media.MacObjectiveC.*;
import static tritium.desktop.media.MediaCommand.Action.*;

/** Real framework/ABI test. Native test events exercise our registered target-action selector. */
@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "tritium.testNowPlaying", matches = "true")
class MacNowPlayingTest {
    public interface EventCommand extends Callback { Pointer invoke(Pointer self, Pointer selector); }
    public interface EventPosition extends Callback { double invoke(Pointer self, Pointer selector); }
    private static final AtomicReference<Pointer> EVENT_COMMAND = new AtomicReference<>();
    private static final EventCommand GET_COMMAND = (self, selector) -> EVENT_COMMAND.get();
    private static final EventPosition GET_POSITION = (self, selector) -> 23.456;

    @Test void publishesUnicodeArtworkTimelineAndHandlesNativeCallbacksThenCleansUp() throws Exception {
        // Starts the Cocoa event loop without showing a window. Do not run Surefire with -XstartOnFirstThread.
        Frame window = new Frame("Tritium Now Playing test");
        SwingUtilities.invokeAndWait(window::addNotify);
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        HttpServer images = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        images.createContext("/cover.png", exchange -> {
            exchange.sendResponseHeaders(200, png.length);
            try (var output = exchange.getResponseBody()) { output.write(png); }
            finally { exchange.close(); }
        });
        images.start();
        List<MediaCommand> commands = new ArrayList<>();
        try (MacNowPlaying backend = MacNowPlaying.open(commands::add)) {
            backend.metadata(new MediaSession.Metadata(1, "歌曲 🎵", "歌手", "专辑",
                    "http://127.0.0.1:" + images.getAddress().getPort() + "/cover.png"));
            Music song = new Gson().fromJson("{\"id\":1}", Music.class);
            backend.playback(new PlaybackState(song, PlaybackState.Status.PLAYING, 12_345, 60_000, true, true, true, true));
            backend.timeline(12_345, 60_000);
            onMain(() -> {
                Pointer center = object(type("MPNowPlayingInfoCenter"), "defaultCenter");
                Pointer info = object(center, "nowPlayingInfo");
                assertEquals("歌曲 🎵", text(property(info, "MPMediaItemPropertyTitle")));
                assertEquals("歌手", text(property(info, "MPMediaItemPropertyArtist")));
                assertEquals("专辑", text(property(info, "MPMediaItemPropertyAlbumTitle")));
                assertEquals(12.345, number(property(info, "MPNowPlayingInfoPropertyElapsedPlaybackTime"), "doubleValue"), 0.001);
                assertEquals(60.0, number(property(info, "MPMediaItemPropertyPlaybackDuration"), "doubleValue"));
                assertEquals(1, integer(center, "playbackState"));
            });
            long deadline = System.nanoTime() + 5_000_000_000L;
            AtomicReference<Boolean> artworkReady = new AtomicReference<>(false);
            do {
                onMain(() -> {
                    Pointer info = object(object(type("MPNowPlayingInfoCenter"), "defaultCenter"), "nowPlayingInfo");
                    Pointer artwork = property(info, "MPMediaItemPropertyArtwork");
                    if (artwork != null) {
                        assertNotNull(object(artwork, "imageWithSize:", new MacArtwork.Size(64, 64)),
                                "The copied native block must retain its image beyond the Java download call");
                        artworkReady.set(true);
                    }
                });
                if (!artworkReady.get()) Thread.sleep(50);
            } while (!artworkReady.get() && System.nanoTime() < deadline);
            assertTrue(artworkReady.get(), "Artwork download and publication timed out");
            var targetField = MacNowPlaying.class.getDeclaredField("target");
            targetField.setAccessible(true);
            Pointer target = (Pointer) targetField.get(backend);
            onMain(() -> {
                Pointer eventClass = OBJC.getFunction("objc_allocateClassPair").invokePointer(new Object[]{
                        type("NSObject"), "TritiumTestMediaEvent", 0L});
                OBJC.getFunction("class_addMethod").invokeInt(new Object[]{eventClass, selector("command"),
                        CallbackReference.getFunctionPointer(GET_COMMAND), "@@:"});
                OBJC.getFunction("class_addMethod").invokeInt(new Object[]{eventClass, selector("positionTime"),
                        CallbackReference.getFunctionPointer(GET_POSITION), "d@:"});
                OBJC.getFunction("objc_registerClassPair").invokeVoid(new Object[]{eventClass});
                Pointer event = object(eventClass, "new");
                try {
                    Pointer center = object(type("MPRemoteCommandCenter"), "sharedCommandCenter");
                    for (String name : List.of("playCommand", "pauseCommand", "togglePlayPauseCommand",
                            "previousTrackCommand", "nextTrackCommand", "changePlaybackPositionCommand")) {
                        EVENT_COMMAND.set(object(center, name));
                        assertEquals(0, integer(target, "handleCommand:", event));
                    }
                } finally { release(event); }
            });
            assertEquals(List.of(MediaCommand.button(PLAY), MediaCommand.button(PAUSE), MediaCommand.button(PLAY_PAUSE),
                    MediaCommand.button(PREVIOUS), MediaCommand.button(NEXT), MediaCommand.seek(23_456, 1)), commands);
            backend.playback(new PlaybackState(song, PlaybackState.Status.PAUSED, 23_456, 60_000, true, true, false, false));
            backend.metadata(new MediaSession.Metadata(2, "Next", "", "", ""));
            onMain(() -> {
                Pointer center = object(type("MPNowPlayingInfoCenter"), "defaultCenter");
                assertEquals(2, integer(center, "playbackState"));
                assertNull(property(object(center, "nowPlayingInfo"), "MPMediaItemPropertyArtwork"));
            });
            backend.clear();
            backend.close();
            onMain(() -> {
                assertNull(object(object(type("MPNowPlayingInfoCenter"), "defaultCenter"), "nowPlayingInfo"));
                Pointer command = object(object(type("MPRemoteCommandCenter"), "sharedCommandCenter"), "playCommand");
                assertEquals(0, integer(command, "isEnabled"));
            });
        } finally {
            images.stop(0);
            SwingUtilities.invokeAndWait(window::dispose);
        }
    }

    private static Pointer property(Pointer info, String name) { return object(info, "objectForKey:", key(name)); }
    private static String text(Pointer string) { return object(string, "UTF8String").getString(0, "UTF-8"); }
}
