package tritium.desktop.download;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.ncm.music.Quality;
import tritium.ncm.music.dto.Music;
import tritium.utils.Tuple;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SongDownloadsTest {
    @TempDir Path directory;
    private HttpServer server;
    private String base;
    private final byte[] audio = "fLaC-test-audio".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/flac");
            exchange.sendResponseHeaders(200, audio.length);
            exchange.getResponseBody().write(audio);
            exchange.close();
        });
        server.createContext("/truncated", exchange -> {
            exchange.sendResponseHeaders(200, audio.length + 20);
            exchange.getResponseBody().write(audio);
            exchange.close();
        });
        server.createContext("/error", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        server.createContext("/empty", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/html", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, audio.length);
            exchange.getResponseBody().write(audio);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); }

    private Music song(String name) {
        return new Gson().fromJson("{\"id\":42,\"name\":" + new Gson().toJson(name)
                + ",\"ar\":[{\"name\":\"歌手\"}]}", Music.class);
    }

    @Test void capturesQualityAndDirectoryDeduplicatesActiveJobsAndDoesNotOverwrite() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        var manager = new SongDownloads(tasks::add, (song, quality) -> {
            assertEquals(Quality.HIRES, quality);
            return Tuple.of(base + "/audio", "FLAC");
        });
        Music song = song("测试歌曲");
        Path folder = directory.resolve("新建目录");
        var first = manager.start(song, folder.toString(), Quality.HIRES);
        assertSame(first, manager.start(song, directory.resolve("elsewhere").toString(), Quality.STANDARD));
        assertEquals(1, tasks.size());
        assertTrue(manager.status(42).busy());
        tasks.removeFirst().run();
        var done = first.join();
        assertEquals(SongDownloads.State.COMPLETE, done.state());
        assertEquals(folder, done.file().getParent());
        assertTrue(done.file().toString().endsWith("[42_HiRes].flac"));
        assertArrayEquals(audio, Files.readAllBytes(done.file()));
        var again = manager.start(song, folder.toString(), Quality.HIRES);
        tasks.removeFirst().run();
        assertEquals(SongDownloads.State.EXISTS, again.join().state());
        assertArrayEquals(audio, Files.readAllBytes(done.file()));
        try (var files = Files.list(folder)) { assertEquals(1, files.count()); }
    }

    @Test void removesPartialFilesAndAllowsRetryAfterNetworkFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var manager = new SongDownloads(Runnable::run, (song, quality) ->
                Tuple.of(base + (calls.getAndIncrement() == 0 ? "/truncated" : "/audio"), "flac"));
        Music song = song("重试");
        assertEquals(SongDownloads.State.FAILED, manager.start(song, directory.toString(), Quality.LOSSLESS).join().state());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        assertEquals(SongDownloads.State.COMPLETE, manager.start(song, directory.toString(), Quality.LOSSLESS).join().state());
    }

    @Test void rejectsHttpErrorsEmptyAndNonAudioResponsesWithoutLeavingFiles() throws Exception {
        for (String endpoint : List.of("/error", "/empty", "/html")) {
            assertThrows(IOException.class, () -> SongDownloads.save(base + endpoint, directory.resolve("song.flac")));
            try (var files = Files.list(directory)) { assertEquals(0, files.count(), endpoint); }
        }
    }

    @Test void handlesUnavailableAudioInvalidPathsAndWorkerRejection() {
        var unavailable = new SongDownloads(Runnable::run, (song, quality) -> null);
        var result = unavailable.start(song("VIP"), directory.toString(), Quality.STANDARD).join();
        assertEquals("download.unavailable", result.messageKey());
        assertFalse(result.busy());
        assertEquals(SongDownloads.State.FAILED, unavailable.start(song("路径"), "\0", Quality.STANDARD).join().state());
        var rejected = new SongDownloads(task -> { throw new java.util.concurrent.RejectedExecutionException(); }, (song, quality) -> null);
        assertEquals(SongDownloads.State.FAILED, rejected.start(song("队列"), directory.toString(), Quality.STANDARD).join().state());
    }

    @Test void keepsUntrustedLongMetadataInsideDestinationAndSeparatesQualities() {
        Music song = song("../\\:*?\"<>|\n" + "音乐🎵".repeat(60));
        String name = SongDownloads.fileName(song, Quality.HIRES, "../../flac");
        assertEquals(directory, directory.resolve(name).normalize().getParent());
        assertFalse(name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"));
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length < 255);
        assertTrue(name.endsWith(".mp3"));
        assertNotEquals(name, SongDownloads.fileName(song, Quality.STANDARD, "../../flac"));
    }
}
