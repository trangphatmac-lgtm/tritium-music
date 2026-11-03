package tritium.desktop.download;

import tritium.i18n.I18n;
import tritium.ncm.music.Quality;
import tritium.ncm.music.dto.Music;
import tritium.utils.Tuple;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** User downloads are independent of playback and its disposable cache. */
public final class SongDownloads {
    public enum State { DOWNLOADING, COMPLETE, EXISTS, FAILED }

    public record Status(State state, String song, Path file, String messageKey) {
        public boolean busy() { return state == State.DOWNLOADING; }
        public String text() { return I18n.text(messageKey, song, file == null ? "" : file.toString()); }
    }

    @FunctionalInterface
    interface SourceResolver { Tuple<String, String> resolve(Music song, Quality quality) throws Exception; }

    private final Executor executor;
    private final SourceResolver resolver;
    private final Map<Long, CompletableFuture<Status>> active = new ConcurrentHashMap<>();
    private final Map<Long, Status> statuses = new ConcurrentHashMap<>();
    private volatile Status latest;

    public SongDownloads() {
        this(Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "song-download");
            thread.setDaemon(true);
            return thread;
        }), Music::getDownloadUrl);
    }

    SongDownloads(Executor executor, SourceResolver resolver) {
        this.executor = executor;
        this.resolver = resolver;
    }

    public Status status(long songId) { return statuses.get(songId); }

    public String statusText() {
        Status status = latest;
        return status == null ? I18n.text("download.idle") : status.text();
    }

    /** Capture the destination and quality when clicked; repeated clicks share the active job. */
    public synchronized CompletableFuture<Status> start(Music song, String directory, Quality quality) {
        CompletableFuture<Status> pending = active.get(song.getId());
        if (pending != null) return pending;
        CompletableFuture<Status> result = new CompletableFuture<>();
        active.put(song.getId(), result);
        publish(song, new Status(State.DOWNLOADING, song.getName(), null, "download.downloading"));
        try {
            executor.execute(() -> {
                Status status;
                try {
                    Path folder = Path.of(directory).toAbsolutePath().normalize();
                    Tuple<String, String> source = resolver.resolve(song, quality);
                    if (source == null) {
                        status = new Status(State.FAILED, song.getName(), null, "download.unavailable");
                    } else {
                        Path destination = folder.resolve(fileName(song, quality, source.getB()));
                        try {
                            save(source.getA(), destination);
                            status = new Status(State.COMPLETE, song.getName(), destination, "download.complete");
                        } catch (FileAlreadyExistsException e) {
                            status = new Status(State.EXISTS, song.getName(), destination, "download.exists");
                        }
                    }
                } catch (Exception e) {
                    status = new Status(State.FAILED, song.getName(), null, "download.failed");
                }
                finish(song, result, status);
            });
        } catch (RuntimeException e) {
            finish(song, result, new Status(State.FAILED, song.getName(), null, "download.failed"));
        }
        return result;
    }

    private void finish(Music song, CompletableFuture<Status> result, Status status) {
        publish(song, status);
        active.remove(song.getId(), result);
        result.complete(status);
    }

    private void publish(Music song, Status status) {
        statuses.put(song.getId(), status);
        latest = status;
    }

    static String fileName(Music song, Quality quality, String type) {
        String extension = type == null ? "mp3" : type.toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,8}")) extension = "mp3";
        String title = safePart(song.getName());
        String artist = safePart(song.getArtistsName());
        return title + " - " + artist + " [" + song.getId() + "_" + quality.getQuality() + "]." + extension;
    }

    private static String safePart(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
        // Bound UTF-8 filename size even for CJK and supplementary characters.
        int end = clean.offsetByCodePoints(0, Math.min(24, clean.codePointCount(0, clean.length())));
        clean = clean.substring(0, end).replaceAll("[. ]+$", "");
        return clean.isEmpty() ? "Unknown" : clean;
    }

    static void save(String url, Path destination) throws IOException {
        if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
        Files.createDirectories(destination.getParent());
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            throw new IOException("Unsupported audio URL");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        Path temporary = null;
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Audio request failed");
            String contentType = connection.getContentType();
            if (contentType != null && (contentType.toLowerCase(Locale.ROOT).contains("text/")
                    || contentType.toLowerCase(Locale.ROOT).contains("json"))) throw new IOException("Invalid audio response");
            temporary = Files.createTempFile(destination.getParent(), ".tritium-download-", ".part");
            long bytes;
            try (var input = connection.getInputStream(); var output = Files.newOutputStream(temporary)) {
                bytes = input.transferTo(output);
            }
            long length = connection.getContentLengthLong();
            if (bytes == 0 || (length >= 0 && bytes != length)) throw new IOException("Incomplete audio download");
            Files.move(temporary, destination);
        } finally {
            connection.disconnect();
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }
}
