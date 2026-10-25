package tritium.desktop.media;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Pointer;
import tritium.ncm.music.PlaybackState;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static tritium.desktop.media.MacObjectiveC.*;
import static tritium.desktop.media.MediaCommand.Action.*;

/** MPNowPlayingInfoCenter and MPRemoteCommandCenter, using public macOS APIs only. */
final class MacNowPlaying implements MediaSession.Backend {
    private static final String[] COMMANDS = {"playCommand", "pauseCommand", "togglePlayPauseCommand",
            "previousTrackCommand", "nextTrackCommand", "changePlaybackPositionCommand"};
    private static final Map<Long, MacNowPlaying> TARGETS = new ConcurrentHashMap<>();
    public interface CommandAction extends Callback { long invoke(Pointer target, Pointer selector, Pointer event); }
    private static final CommandAction ACTION = (target, selector, event) -> {
        MacNowPlaying owner = TARGETS.get(Pointer.nativeValue(target));
        if (owner == null || owner.closed) return 110; // NoActionableNowPlayingItem
        try { return owner.command(event); }
        catch (RuntimeException | LinkageError failure) {
            System.err.println("[System media] macOS command failed: " + failure);
            return 200; // CommandFailed; exceptions must not unwind through native frames.
        }
    };
    private static final Pointer TARGET_CLASS = targetClass();
    private final Consumer<MediaCommand> commands;
    private final ThreadPoolExecutor artworkWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "Now Playing artwork");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile boolean closed;
    private volatile MediaSession.Metadata metadata;
    private volatile PlaybackState state = PlaybackState.empty();
    // All native fields below are confined to the Cocoa main queue.
    private Pointer center, commandCenter, target, info;

    private MacNowPlaying(Consumer<MediaCommand> commands) { this.commands = commands; }

    static MacNowPlaying open(Consumer<MediaCommand> commands) {
        MacNowPlaying result = new MacNowPlaying(commands);
        try {
            onMain(result::initialize);
            return result;
        } catch (RuntimeException | LinkageError failure) {
            result.close();
            throw failure;
        }
    }

    private static Pointer targetClass() {
        Pointer type = OBJC.getFunction("objc_allocateClassPair").invokePointer(new Object[]{
                type("NSObject"), "TritiumMediaCommandTarget", 0L});
        if (type == null) throw new IllegalStateException("Cannot allocate media command target");
        int added = OBJC.getFunction("class_addMethod").invokeInt(new Object[]{type, selector("handleCommand:"),
                CallbackReference.getFunctionPointer(ACTION), "q@:@"});
        if (added == 0) throw new IllegalStateException("Cannot register media command handler");
        OBJC.getFunction("objc_registerClassPair").invokeVoid(new Object[]{type});
        return type;
    }

    private void initialize() {
        center = object(type("MPNowPlayingInfoCenter"), "defaultCenter");
        commandCenter = object(type("MPRemoteCommandCenter"), "sharedCommandCenter");
        if (center == null || commandCenter == null) throw new IllegalStateException("MediaPlayer is unavailable");
        target = object(TARGET_CLASS, "new");
        TARGETS.put(Pointer.nativeValue(target), this);
        for (String name : COMMANDS) {
            Pointer command = object(commandCenter, name);
            send(command, "setEnabled:", (byte) 0);
            send(command, "addTarget:action:", target, selector("handleCommand:"));
        }
    }

    private long command(Pointer event) {
        PlaybackState snapshot = state;
        if (snapshot.song() == null) return 110;
        Pointer received = object(event, "command");
        MediaCommand command;
        if (received.equals(object(commandCenter, "changePlaybackPositionCommand"))) {
            if (!snapshot.canPlay() || snapshot.durationMillis() <= 0) return 200;
            double seconds = number(event, "positionTime");
            if (!Double.isFinite(seconds) || seconds < 0) return 200;
            command = MediaCommand.seek((long) (seconds * 1000), snapshot.song().getId());
        } else if (received.equals(object(commandCenter, "playCommand"))) {
            if (!snapshot.canPlay()) return 200;
            command = MediaCommand.button(PLAY);
        } else if (received.equals(object(commandCenter, "pauseCommand"))) {
            if (!snapshot.canPause()) return 200;
            command = MediaCommand.button(PAUSE);
        } else if (received.equals(object(commandCenter, "previousTrackCommand"))) {
            if (!snapshot.canPrevious()) return 200;
            command = MediaCommand.button(PREVIOUS);
        } else if (received.equals(object(commandCenter, "nextTrackCommand"))) {
            if (!snapshot.canNext()) return 200;
            command = MediaCommand.button(NEXT);
        } else if (received.equals(object(commandCenter, "togglePlayPauseCommand"))) {
            if (!snapshot.canPlay() && !snapshot.canPause()) return 200;
            command = MediaCommand.button(PLAY_PAUSE);
        } else return 200;
        commands.accept(command);
        return 0;
    }

    @Override public void metadata(MediaSession.Metadata value) {
        onMain(() -> {
            if (closed) return;
            metadata = value;
            release(info);
            info = object(type("NSMutableDictionary"), "new");
            put("MPMediaItemPropertyTitle", string(value.title()));
            put("MPMediaItemPropertyArtist", string(value.artist()));
            put("MPMediaItemPropertyAlbumTitle", string(value.album()));
            put("MPNowPlayingInfoPropertyMediaType", object(type("NSNumber"), "numberWithLong:", 1L)); // Audio
            publish(); // Immediately drop the previous cover, even when this download fails.
        });
        if (!closed && !value.artworkUrl().isBlank()) artworkWorker.execute(() -> loadArtwork(value));
    }

    @Override public void playback(PlaybackState value) {
        onMain(() -> {
            if (closed) return;
            state = value;
            enable("playCommand", value.canPlay());
            enable("pauseCommand", value.canPause());
            enable("togglePlayPauseCommand", value.canPlay() || value.canPause());
            enable("previousTrackCommand", value.canPrevious());
            enable("nextTrackCommand", value.canNext());
            enable("changePlaybackPositionCommand", value.canPlay() && value.durationMillis() > 0);
            putNumber("MPNowPlayingInfoPropertyPlaybackRate", value.status() == PlaybackState.Status.PLAYING ? 1 : 0);
            putNumber("MPNowPlayingInfoPropertyDefaultPlaybackRate", 1);
            publish();
            send(center, "setPlaybackState:", switch (value.status()) {
                case PLAYING -> 1L;
                case PAUSED -> 2L;
                case STOPPED -> 3L;
                case OPENING -> 4L;
                case CLOSED -> 0L;
            });
        });
    }

    @Override public void timeline(long positionMillis, long durationMillis) {
        onMain(() -> {
            if (closed) return;
            putNumber("MPNowPlayingInfoPropertyElapsedPlaybackTime", positionMillis / 1000.0);
            putNumber("MPMediaItemPropertyPlaybackDuration", durationMillis / 1000.0);
            publish();
        });
    }

    private void put(String name, Pointer value) { if (info != null) send(info, "setObject:forKey:", value, key(name)); }
    private void putNumber(String name, double value) { put(name, object(type("NSNumber"), "numberWithDouble:", value)); }
    private void publish() { send(center, "setNowPlayingInfo:", info); }
    private void enable(String name, boolean enabled) { send(object(commandCenter, name), "setEnabled:", (byte) (enabled ? 1 : 0)); }

    private void loadArtwork(MediaSession.Metadata requested) {
        if (closed || metadata != requested) return;
        try {
            byte[] bytes = downloadArtwork(requested.artworkUrl());
            if (bytes == null || closed || metadata != requested) return;
            onMain(() -> {
                if (closed || metadata != requested) return;
                Pointer artwork = MacArtwork.create(bytes);
                if (artwork == null) return;
                try { put("MPMediaItemPropertyArtwork", artwork); publish(); }
                finally { release(artwork); }
            });
        } catch (IOException | RuntimeException | LinkageError failure) {
            if (!closed) System.err.println("[System media] Cover unavailable: " + failure.getMessage());
        }
    }

    static byte[] downloadArtwork(String url) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return null;
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try (var input = connection.getInputStream()) {
            byte[] data = input.readNBytes(8 * 1024 * 1024 + 1);
            return data.length == 0 || data.length > 8 * 1024 * 1024 ? null : data;
        } finally { connection.disconnect(); }
    }

    private void clearOnMain() {
        metadata = null;
        state = PlaybackState.empty();
        if (commandCenter != null) for (String name : COMMANDS) enable(name, false);
        if (center != null) {
            send(center, "setNowPlayingInfo:", (Pointer) null);
            send(center, "setPlaybackState:", 0L);
        }
        release(info);
        info = null;
    }

    @Override public void clear() { onMain(this::clearOnMain); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        artworkWorker.shutdownNow();
        onMain(() -> {
            clearOnMain();
            if (target != null) {
                TARGETS.remove(Pointer.nativeValue(target));
                for (String name : COMMANDS) send(object(commandCenter, name), "removeTarget:", target);
                release(target);
                target = null;
            }
        });
    }
}
