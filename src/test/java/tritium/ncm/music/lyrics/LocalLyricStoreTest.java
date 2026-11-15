package tritium.ncm.music.lyrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalLyricStoreTest {
    @TempDir Path directory;

    @Test void importedLyricsSurviveOriginalRemovalAndStoreRecreation() throws Exception {
        Path file = directory.resolve("song.LRC");
        Files.writeString(file, "\uFEFF[offset:100]\n[00:01.00]你好\n[00:02.00]世界");
        Path saved = directory.resolve("saved");
        var imported = new LocalLyricStore(saved).importFile(BestLyricServiceTest.SONG, file);
        Files.delete(file);
        assertEquals(imported, new LocalLyricStore(saved).load(BestLyricServiceTest.SONG));
        assertEquals(1100, imported.lines().getFirst().start());
        assertEquals("你好", imported.lines().getFirst().text());
        var other = new LyricSong("2", "Song", "Artist", "Album", 200000, "", "");
        assertFalse(new LocalLyricStore(saved).load(other).usable());
    }

    @Test void supportedFormatsRetainTheirTimelines() throws Exception {
        String[] contents = {
                "[00:01]你好",
                "[1000,1000](1000,500,0)你(1500,500,0)好",
                "[1000,1000]你(1000,500)好(1500,500)",
                "[1000,1000]<0,500,0>你<500,500,0>好",
                "<tt xmlns='http://www.w3.org/ns/ttml'><body><p begin='1s' end='2s'>你好</p></body></tt>",
                "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n你好"
        };
        var store = new LocalLyricStore(directory.resolve("saved"));
        for (int i = 0; i < contents.length; i++) {
            Path file = directory.resolve("song." + LocalLyricStore.EXTENSIONS.get(i));
            Files.writeString(file, contents[i]);
            var document = store.importFile(BestLyricServiceTest.SONG, file);
            assertEquals("你好", document.lines().getFirst().text());
            assertEquals(1000, document.lines().getFirst().start());
            assertEquals(document, store.load(BestLyricServiceTest.SONG));
            if (i >= 1 && i <= 3) {
                assertTrue(document.wordTimed());
                assertEquals(1500, document.lines().getFirst().words().get(1).start());
            }
        }
    }

    @Test void invalidImportPreservesSavedLyrics() throws Exception {
        var store = new LocalLyricStore(directory.resolve("saved"));
        Path file = directory.resolve("song.lrc");
        Files.writeString(file, "[00:01]Original");
        var original = store.importFile(BestLyricServiceTest.SONG, file);
        Files.writeString(file, "invalid lyric text");
        assertThrows(IOException.class, () -> store.importFile(BestLyricServiceTest.SONG, file));
        assertEquals(original, store.load(BestLyricServiceTest.SONG));
        assertThrows(IOException.class, () -> store.importFile(BestLyricServiceTest.SONG, directory.resolve("song.mp3")));
    }

    @Test void chineseAndUtf16EncodingsAreDecoded() throws Exception {
        var store = new LocalLyricStore(directory.resolve("saved"));
        Path file = directory.resolve("song.lrc");
        for (var charset : new Charset[]{Charset.forName("GB18030"), StandardCharsets.UTF_16}) {
            Files.write(file, "[00:01]你好世界".getBytes(charset));
            assertEquals("你好世界", store.importFile(BestLyricServiceTest.SONG, file).lines().getFirst().text());
        }
    }

    @Test void encryptedQrcFilesUseTheExistingDecoder() throws Exception {
        Path file = directory.resolve("song.qrc");
        try (var input = getClass().getResourceAsStream("/lyrics/qq-reference.hex")) {
            Files.write(file, input.readAllBytes());
        }
        var store = new LocalLyricStore(directory.resolve("saved"));
        var document = store.importFile(BestLyricServiceTest.SONG, file);
        assertEquals("Hello 世界", document.lines().getFirst().text());
        assertEquals(1800, document.lines().getFirst().words().get(1).start());
        assertEquals(document, store.load(BestLyricServiceTest.SONG));
    }

    @Test void savedLyricsReplaceQueuedOnlineResultsAndSkipProviders() throws Exception {
        Path file = directory.resolve("song.yrc");
        Files.writeString(file, "[1000,1000](1000,1000,0)Imported");
        var store = new LocalLyricStore(directory.resolve("saved"));
        var imported = store.importFile(BestLyricServiceTest.SONG, file);
        var providers = new BestLyricServiceTest.Providers();
        var queue = new LinkedBlockingQueue<Runnable>();
        var accepted = new ArrayList<LyricDocument>();
        try (var loader = new LyricLoader(new BestLyricService(providers), queue::add)) {
            loader.load(BestLyricServiceTest.SONG, false, LyricSource.NETEASE, () -> true,
                    result -> accepted.add(result.document()));
            Runnable old = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(old);
            loader.loadSaved(BestLyricServiceTest.SONG, store, () -> true, accepted::add,
                    () -> fail("Saved lyrics should precede online lyrics"));
            Runnable saved = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(saved);
            old.run();
            assertTrue(accepted.isEmpty());
            saved.run();
            assertEquals(java.util.List.of(imported), accepted);
            assertTrue(providers.calls.isEmpty());
            loader.loadSaved(BestLyricServiceTest.SONG, store, () -> false, accepted::add,
                    () -> fail("Stale song must not load online lyrics"));
            Runnable stale = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(stale);
            stale.run();
            assertEquals(1, accepted.size());
        }
    }

    @Test void missingAndCorruptSavedLyricsAllowOnlineFallback() throws Exception {
        Path saved = directory.resolve("saved");
        Files.createDirectories(saved);
        var store = new LocalLyricStore(saved);
        var queue = new LinkedBlockingQueue<Runnable>();
        try (var loader = new LyricLoader(new BestLyricService(new BestLyricServiceTest.Providers()), queue::add)) {
            var fallbacks = new ArrayList<Boolean>();
            for (int i = 0; i < 2; i++) {
                loader.loadSaved(BestLyricServiceTest.SONG, store, () -> true,
                        document -> fail("Missing/corrupt saved lyrics must fall back"), () -> fallbacks.add(true));
                Runnable result = queue.poll(2, TimeUnit.SECONDS);
                assertNotNull(result);
                result.run();
                Files.writeString(saved.resolve("31.json"), "broken json");
            }
            assertEquals(2, fallbacks.size());
        }
    }
}
