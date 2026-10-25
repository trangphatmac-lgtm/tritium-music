package tritium.desktop.media;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tritium.ncm.music.PlaybackState;
import tritium.ncm.music.dto.Music;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.desktop.media.WinRt.*;

/** Exercises the real Windows broker in both directions; requires an interactive Windows 10/11 desktop. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "tritium.testSmtc", matches = "true")
class WindowsSmtcTest {
    private Path shortcut;
    private Path shortcutDirectory;

    @AfterEach void removeTestShortcut() throws Exception {
        if (shortcut != null) Files.deleteIfExists(shortcut);
        if (shortcutDirectory != null) Files.deleteIfExists(shortcutDirectory);
    }

    interface Strings extends StdCallLibrary {
        Strings API = Native.load("combase", Strings.class);
        Pointer WindowsGetStringRawBuffer(Pointer value, IntByReference length);
    }

    interface ShellLookup extends StdCallLibrary {
        ShellLookup API = Native.load("shell32", ShellLookup.class);
        int SHCreateItemInKnownFolder(com.sun.jna.platform.win32.Guid.GUID folder, int flags,
                                     com.sun.jna.WString item, com.sun.jna.platform.win32.Guid.GUID iid,
                                     PointerByReference result);
    }

    @Test void publishesUnicodeMetadataTimelineAndReceivesSystemCommands() throws Exception {
        String appId = "Tritium.Music.Test." + UUID.randomUUID();
        shortcutDirectory = Path.of(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                com.sun.jna.platform.win32.KnownFolders.FOLDERID_Programs)).resolve(appId);
        shortcut = shortcutDirectory.resolve("Tritium Music.lnk");
        var launch = WindowsAppIdentity.jarLaunch(Path.of(System.getProperty("java.home"), "bin", "javaw.exe"),
                Path.of("target", "tritium-music-1.0.3.jar"));
        WindowsAppIdentity.registerShortcut(shortcut, launch, appId);
        WindowsAppIdentity.registerShortcut(shortcut, launch, appId); // Refresh an owned shortcut on relaunch.
        assertThrows(java.io.IOException.class,
                () -> WindowsAppIdentity.registerShortcut(shortcut, launch, "Another.Application"));
        WindowsAppIdentity.setProcessIdentity(appId);
        Frame window = new Frame("Tritium SMTC integration test");
        SwingUtilities.invokeAndWait(window::addNotify); // Real HWND without showing a test window.
        LinkedBlockingQueue<MediaCommand> commands = new LinkedBlockingQueue<>();
        byte[] cover = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        HttpServer images = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        images.createContext("/cover.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, cover.length);
            try (var output = exchange.getResponseBody()) { output.write(cover); }
            finally { exchange.close(); }
        });
        images.start();
        try (WindowsSmtc smtc = WindowsSmtc.open(Pointer.nativeValue(Native.getWindowPointer(window)), commands::add)) {
            String title = "SMTC 中文 🎵 " + UUID.randomUUID();
            smtc.metadata(new MediaSession.Metadata(1, title, "歌手", "专辑",
                    "http://127.0.0.1:" + images.getAddress().getPort() + "/cover.png"));
            Music song = new com.google.gson.Gson().fromJson("{\"id\":1}", Music.class);
            smtc.playback(new PlaybackState(song, PlaybackState.Status.PLAYING, 12_345, 60_000,
                    true, true, true, true));
            smtc.timeline(12_345, 60_000);

            try (ComObject factory = factory("Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager",
                    "2050c4ee-11a0-57de-aed7-c97c70338245");
                 ComObject request = factory.object(6);
                 ComObject manager = asyncObject(request);
                 ComObject session = findSession(manager, title)) {
                assertEquals(appId, string(session, 6), "SMTC must use the player's identity, not its Java host or launcher");
                assertAppDisplayName(appId);
                try (ComObject mediaRequest = session.object(7); ComObject media = asyncObject(mediaRequest)) {
                    assertEquals(title, string(media, 6));
                    assertEquals("歌手", string(media, 9));
                    assertEquals("专辑", string(media, 10));
                    try (ComObject thumbnail = media.object(15)) {
                        assertNotNull(thumbnail.pointer);
                        try (ComObject read = thumbnail.object(6); ComObject content = asyncObject(read);
                             ComObject stream = content.query("905a0fe1-bc53-11df-8c49-001e4fc686da")) {
                            LongByReference size = new LongByReference();
                            stream.call(6, size);
                            assertEquals(cover.length, size.getValue(), "Windows must be able to read the cover image");
                        }
                    }
                }
                try (ComObject timeline = session.object(8); ComObject playback = session.object(9)) {
                    LongByReference ticks = new LongByReference();
                    timeline.call(10, ticks);
                    assertEquals(123_450_000, ticks.getValue());
                    timeline.call(7, ticks);
                    assertEquals(600_000_000, ticks.getValue());
                    IntByReference status = new IntByReference();
                    playback.call(7, status);
                    assertEquals(4, status.getValue()); // Global session enum: Playing=4 (SMTC enum: Playing=3)
                }
                requestButton(session, 11, MediaCommand.Action.PAUSE, commands);
                smtc.playback(new PlaybackState(song, PlaybackState.Status.PAUSED, 12_345, 60_000,
                        true, true, true, true));
                awaitPlaybackStatus(session, 5);
                requestButton(session, 10, MediaCommand.Action.PLAY, commands);
                smtc.playback(new PlaybackState(song, PlaybackState.Status.PLAYING, 12_345, 60_000,
                        true, true, true, true));
                awaitPlaybackStatus(session, 4);
                requestButton(session, 16, MediaCommand.Action.NEXT, commands);
                requestButton(session, 17, MediaCommand.Action.PREVIOUS, commands);
                try (ComObject seek = session.object(24, 300_000_000L)) {
                    assertTrue(asyncBoolean(seek));
                    assertEquals(MediaCommand.seek(30_000), commands.poll(5, TimeUnit.SECONDS));
                }
                smtc.playback(new PlaybackState(song, PlaybackState.Status.PAUSED, 30_000, 60_000,
                        true, true, false, false));
                smtc.timeline(30_000, 60_000);
                awaitPlaybackStatus(session, 5); // Global session enum: Paused=5
                smtc.metadata(new MediaSession.Metadata(2, title + " next", "", "", "invalid cover"));
                try (ComObject next = findSession(manager, title + " next");
                     ComObject mediaRequest = next.object(7); ComObject media = asyncObject(mediaRequest)) {
                    assertEquals("", string(media, 9));
                    assertEquals("", string(media, 10));
                    PointerByReference thumbnail = new PointerByReference();
                    media.call(15, thumbnail);
                    assertNull(thumbnail.getValue(), "Missing artwork must clear the preceding cover");
                }
                String sourceId = string(session, 6);
                smtc.clear();
                smtc.close();
                awaitSessionRemoved(manager, sourceId);
            }
        } finally {
            images.stop(0);
            SwingUtilities.invokeAndWait(window::dispose);
        }
    }

    private static void assertAppDisplayName(String appId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Exception lastFailure = null;
        do {
            try {
                // AppInfo is package-oriented. Resolve an unpackaged desktop app through AppsFolder.
                PointerByReference result = new PointerByReference();
                check(ShellLookup.API.SHCreateItemInKnownFolder(
                        new com.sun.jna.platform.win32.Guid.GUID("1e87508d-89c2-42f0-8a7e-645a0f50ca58"), 0,
                        new com.sun.jna.WString(appId),
                        new com.sun.jna.platform.win32.Guid.GUID("43826d1e-e718-42ee-bc55-a1e261c37bfe"), result));
                try (ComObject app = new ComObject(result.getValue())) {
                    PointerByReference name = new PointerByReference();
                    app.call(5, 0, name); // IShellItem.GetDisplayName(SIGDN_NORMALDISPLAY)
                    try { assertEquals("Tritium Music", name.getValue().getWideString(0)); }
                    finally { com.sun.jna.platform.win32.Ole32.INSTANCE.CoTaskMemFree(name.getValue()); }
                    return;
                }
            } catch (IllegalStateException pendingShellIndex) {
                lastFailure = pendingShellIndex;
                Thread.sleep(100);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Windows could not resolve the registered app display name", lastFailure);
    }

    private static void awaitSessionRemoved(ComObject manager, String sourceId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            boolean found = false;
            try (ComObject sessions = manager.object(7)) {
                IntByReference size = new IntByReference();
                sessions.call(7, size);
                for (int index = 0; index < size.getValue(); index++) {
                    try (ComObject session = sessions.object(6, index)) {
                        found |= sourceId.equals(string(session, 6));
                    }
                }
            }
            if (!found) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail("Closed media session is still registered with Windows");
    }

    private static void requestButton(ComObject session, int slot, MediaCommand.Action button,
                                      LinkedBlockingQueue<MediaCommand> commands) throws Exception {
        try (ComObject operation = session.object(slot)) {
            assertTrue(asyncBoolean(operation));
            assertEquals(MediaCommand.button(button), commands.poll(5, TimeUnit.SECONDS));
        }
    }

    private static void awaitPlaybackStatus(ComObject session, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int actual;
        do {
            try (ComObject playback = session.object(9)) {
                IntByReference value = new IntByReference();
                playback.call(7, value);
                actual = value.getValue();
                if (actual == expected) return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, actual);
    }

    private static ComObject findSession(ComObject manager, String title) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            try (ComObject sessions = manager.object(7)) {
                IntByReference size = new IntByReference();
                sessions.call(7, size);
                for (int index = 0; index < size.getValue(); index++) {
                    ComObject session = sessions.object(6, index);
                    boolean found = false;
                    try (ComObject request = session.object(7); ComObject media = asyncObject(request)) {
                        found = title.equals(string(media, 6));
                        if (found) return session;
                    } finally {
                        if (!found) session.close();
                    }
                }
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Tritium session was not visible to the Windows media broker");
    }

    private static String string(ComObject object, int slot) {
        PointerByReference value = new PointerByReference();
        object.call(slot, value);
        try {
            IntByReference length = new IntByReference();
            Pointer data = Strings.API.WindowsGetStringRawBuffer(value.getValue(), length);
            return length.getValue() == 0 ? "" : new String(data.getCharArray(0, length.getValue()));
        } finally {
            WinRt.Runtime.API.WindowsDeleteString(value.getValue());
        }
    }

    private static ComObject asyncObject(ComObject operation) throws Exception {
        await(operation);
        return operation.object(8); // IAsyncOperation<T>.GetResults
    }

    private static boolean asyncBoolean(ComObject operation) throws Exception {
        await(operation);
        ByteByReference value = new ByteByReference();
        operation.call(8, value);
        return value.getValue() != 0;
    }

    private static void await(ComObject operation) throws Exception {
        try (ComObject info = operation.query("00000036-0000-0000-c000-000000000046")) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            IntByReference status = new IntByReference();
            do {
                info.call(7, status);
                if (status.getValue() != 0) {
                    assertEquals(1, status.getValue(), "WinRT asynchronous operation failed");
                    return;
                }
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            fail("Timed out waiting for WinRT");
        }
    }
}
