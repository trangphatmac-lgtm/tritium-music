package tritium.ncm.music.stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpAudioCacheTest {
    @TempDir Path directory;

    @Test void rangeSeekFetchesTailWithoutDownloadingMiddleAndCommitsExactBytes() throws Exception {
        byte[] audio = new byte[1024 * 1024 + 37];
        new Random(42).nextBytes(audio);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve(audio, true, false, requests);
        Path cache = directory.resolve("song.m4a");
        try (HttpAudioCache input = new HttpAudioCache(url(server), cache)) {
            input.open();
            input.mark(Integer.MAX_VALUE);
            assertArrayEquals(Arrays.copyOf(audio, 20), input.readNBytes(20));
            input.reset();
            assertEquals(audio.length - 10, input.skip(audio.length - 10));
            assertArrayEquals(Arrays.copyOfRange(audio, audio.length - 10, audio.length), input.readAllBytes());
            assertTrue(input.downloaded() < audio.length / 4, "Tail metadata must not require the entire song");
            assertFalse(Files.exists(cache));
            input.commit();
            assertArrayEquals(audio, Files.readAllBytes(cache));
            assertTrue(requests.get() < 5, "Consecutive blocks should reuse the HTTP connection");
        } finally { server.stop(0); }
        try (var files = Files.list(directory)) { assertEquals(List.of(cache), files.toList()); }
    }

    @Test void ignoredRangeStillSupportsCachedBackwardsReadsAndForwardSeeks() throws Exception {
        byte[] audio = new byte[300_000];
        new Random(7).nextBytes(audio);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve(audio, false, false, requests);
        Path cache = directory.resolve("song.wav");
        try (HttpAudioCache input = new HttpAudioCache(url(server), cache)) {
            input.open(); input.mark(0);
            input.skip(250_000);
            assertArrayEquals(Arrays.copyOfRange(audio, 250_000, 250_100), input.readNBytes(100));
            input.reset();
            assertArrayEquals(Arrays.copyOf(audio, 50), input.readNBytes(50));
            input.commit();
            assertArrayEquals(audio, Files.readAllBytes(cache));
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    @Test void chunkedResponseDoesNotRequireContentLength() throws Exception {
        byte[] audio = new byte[70_000];
        new Random(3).nextBytes(audio);
        HttpServer server = serve(audio, false, true, new AtomicInteger());
        Path cache = directory.resolve("song.mp3");
        try (HttpAudioCache input = new HttpAudioCache(url(server), cache)) {
            input.open();
            assertFalse(input.seekable());
            assertArrayEquals(audio, input.readAllBytes());
            input.commit();
            assertArrayEquals(audio, Files.readAllBytes(cache));
        } finally { server.stop(0); }
    }

    @Test void truncatedOrCancelledDownloadsNeverPublishCache() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            exchange.sendResponseHeaders(200, 200_000);
            try (var out = exchange.getResponseBody()) { out.write(new byte[100]); }
            catch (IOException ignored) { }
        });
        server.start();
        Path cache = directory.resolve("song.flac");
        try (HttpAudioCache input = new HttpAudioCache(url(server), cache)) {
            input.open();
            assertThrows(IOException.class, input::readAllBytes);
            input.cancel();
            assertThrows(InterruptedIOException.class, input::commit);
            assertFalse(Files.exists(cache));
        } finally { server.stop(0); }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/audio"; }

    static HttpServer serve(byte[] bytes, boolean ranges, boolean chunked, AtomicInteger requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/audio", exchange -> {
            requests.incrementAndGet();
            int start = 0;
            if (ranges && exchange.getRequestHeaders().containsKey("Range")) {
                String range = exchange.getRequestHeaders().getFirst("Range");
                start = Integer.parseInt(range.substring(6, range.length() - 1));
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (bytes.length - 1) + "/" + bytes.length);
            }
            exchange.sendResponseHeaders(ranges ? 206 : 200, chunked ? 0 : bytes.length - start);
            try (var output = exchange.getResponseBody()) { output.write(bytes, start, bytes.length - start); }
            catch (IOException ignored) { }
        });
        server.start();
        return server;
    }
}
