package tritium.desktop.media;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import tritium.ncm.music.PlaybackState;

import java.net.URI;
import java.util.function.Consumer;

import static tritium.desktop.media.WinRt.*;

/**
 * Desktop SMTC via ISystemMediaTransportControlsInterop.GetForWindow.
 * Vtable slots/IIDs follow Windows SDK windows.media.h and
 * SystemMediaTransportControlsInterop.h (IInspectable occupies slots 0..5).
 * All owned COM objects are created, used and released on one MTA worker.
 */
final class WindowsSmtc implements MediaSession.Backend {
    private static final String CONTROLS_IID = "99fa3ff4-1742-42a6-902e-087d41f965ec";
    private ComObject controls, controls2, updater, timeline;
    private EventHandler buttons, seeking;
    private long buttonToken, seekToken;
    private boolean buttonRegistered, seekRegistered, initialized;

    enum Button { PLAY, PAUSE, PREVIOUS, NEXT }
    record Command(Button button, Long positionMillis) {
        static Command button(Button button) { return new Command(button, null); }
        static Command seek(long millis) { return new Command(null, millis); }
    }

    @Structure.FieldOrder("value")
    public static class Int64Value extends Structure implements Structure.ByValue {
        public long value;
        public Int64Value() { }
        Int64Value(long value) { this.value = value; }
    }

    static WindowsSmtc open(long hwnd, Consumer<Command> commands) {
        WindowsSmtc result = new WindowsSmtc();
        try {
            check(WinRt.Runtime.API.RoInitialize(1)); // RO_INIT_MULTITHREADED
            result.initialized = true;
            result.initialize(hwnd, commands);
            return result;
        } catch (RuntimeException | LinkageError failure) {
            result.close();
            throw failure;
        }
    }

    private void initialize(long hwnd, Consumer<Command> commands) {
        if (hwnd == 0) throw new IllegalArgumentException("SMTC needs the main window handle");
        try (ComObject interop = factory("Windows.Media.SystemMediaTransportControls",
                "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a")) {
            controls = interop.object(6, new Pointer(hwnd), new Guid.GUID(CONTROLS_IID));
        }
        controls2 = controls.query("ea98d2f6-7f3c-4af2-a586-72889808efb1");
        updater = controls.object(8);
        timeline = activate("Windows.Media.SystemMediaTransportControlsTimelineProperties");
        controls.call(11, (byte) 0); // IsEnabled: no session until a song is selected
        controls.call(15, (byte) 0); // Stop is not exposed by the player
        controls2.call(11, 1.0); // PlaybackRate
        buttons = new EventHandler("0557e996-7b23-5bae-aa81-ea0d671143a4", args -> {
            IntByReference value = new IntByReference();
            check(invoke(args, 6, value));
            Button button = switch (value.getValue()) {
                case 0 -> Button.PLAY;
                case 1 -> Button.PAUSE;
                case 6 -> Button.NEXT;
                case 7 -> Button.PREVIOUS;
                default -> null;
            };
            if (button != null) commands.accept(Command.button(button));
        });
        LongByReference token = new LongByReference();
        controls.call(32, buttons.pointer, token);
        buttonToken = token.getValue();
        buttonRegistered = true;
        seeking = new EventHandler("44e34f15-bdc0-50a7-ace4-39e91fb753f1", args -> {
            LongByReference ticks = new LongByReference();
            check(invoke(args, 6, ticks));
            commands.accept(Command.seek(ticks.getValue() / 10_000));
        });
        controls2.call(13, seeking.pointer, token);
        seekToken = token.getValue();
        seekRegistered = true;
    }

    @Override public void metadata(MediaSession.Metadata metadata) {
        updater.call(16); // ClearAll also removes the preceding song's thumbnail
        updater.call(7, 1); // MediaPlaybackType.Music
        updater.text(9, Long.toString(metadata.id()));
        try (ComObject music = updater.object(12);
             ComObject album = music.query("00368462-97d3-44b9-b00f-008afcefaf18")) {
            music.text(7, metadata.title());
            music.text(11, metadata.artist());
            album.text(7, metadata.album());
        }
        if (!metadata.artworkUrl().isBlank()) {
            try {
                setArtwork(metadata.artworkUrl());
            } catch (RuntimeException failure) {
                // A bad/missing cover must not disable transport controls or retain stale art.
                System.err.println("[SMTC] Cover unavailable: " + failure.getMessage());
            }
        }
        updater.call(17); // Update
    }

    private void setArtwork(String url) {
        String scheme = URI.create(url).getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
        try (ComObject uriFactory = factory("Windows.Foundation.Uri", "44a9796f-723e-4fdf-a218-033e75b0c084");
             HString text = new HString(url);
             ComObject uri = uriFactory.object(6, text.pointer);
             ComObject streams = factory("Windows.Storage.Streams.RandomAccessStreamReference",
                     "857309dc-3fbf-4e7d-986f-ef3b1a07a964");
             ComObject thumbnail = streams.object(7, uri.pointer)) {
            updater.call(11, thumbnail.pointer);
        }
    }

    @Override public void playback(PlaybackState state) {
        controls.call(13, flag(state.canPlay()));
        controls.call(17, flag(state.canPause()));
        controls.call(25, flag(state.canPrevious()));
        controls.call(27, flag(state.canNext()));
        int status = switch (state.status()) {
            case CLOSED -> 0;
            case OPENING -> 1;
            case STOPPED -> 2;
            case PLAYING -> 3;
            case PAUSED -> 4;
        };
        controls.call(7, status);
        controls.call(11, flag(state.song() != null));
    }

    @Override public void timeline(long positionMillis, long durationMillis) {
        long duration = ticks(durationMillis);
        timeline.call(7, new Int64Value(0)); // StartTime
        timeline.call(9, new Int64Value(duration)); // EndTime
        timeline.call(11, new Int64Value(0)); // MinSeekTime
        timeline.call(13, new Int64Value(duration)); // MaxSeekTime
        timeline.call(15, new Int64Value(Math.min(duration, ticks(positionMillis))));
        controls2.call(12, timeline.pointer);
    }

    private static byte flag(boolean value) { return (byte) (value ? 1 : 0); }
    private static long ticks(long millis) { return Math.clamp(millis, 0, Long.MAX_VALUE / 10_000) * 10_000; }

    @Override public void clear() {
        controls.call(11, (byte) 0);
        controls.call(7, 0);
        updater.call(16);
        updater.call(17);
    }

    @Override public void close() {
        if (!initialized) return;
        // Disable callbacks before unregistering; late native invocations become harmless.
        if (buttons != null) buttons.close();
        if (seeking != null) seeking.close();
        if (buttonRegistered) cleanup(() -> controls.call(33, new Int64Value(buttonToken)));
        if (seekRegistered) cleanup(() -> controls2.call(14, new Int64Value(seekToken)));
        if (controls != null) cleanup(() -> controls.call(11, (byte) 0));
        if (updater != null) cleanup(() -> { updater.call(16); updater.call(17); });
        if (timeline != null) cleanup(timeline::close);
        if (updater != null) cleanup(updater::close);
        if (controls2 != null) cleanup(controls2::close);
        if (controls != null) cleanup(controls::close);
        initialized = false;
        WinRt.Runtime.API.RoUninitialize();
    }

    private static void cleanup(Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException | LinkageError ignored) { /* Release the other COM objects as well. */ }
    }
}
