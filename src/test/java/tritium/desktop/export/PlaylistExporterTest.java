package tritium.desktop.export;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.ncm.music.dto.Music;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.desktop.export.PlaylistExporter.*;

class PlaylistExporterTest {
    @TempDir Path directory;

    private Music song(String name, long id, long duration) {
        return new Gson().fromJson("{\"name\":" + new Gson().toJson(name) + ",\"id\":" + id
                + ",\"dt\":" + duration + ",\"ar\":[{\"name\":\"歌手甲\"},null,{\"name\":\"歌手乙\"}]}", Music.class);
    }

    @Test void jsonPreservesOrderDuplicatesAndOnlySelectedFields() {
        Music song = song("中文 🎵\n\"歌曲\"", 12345678901234567L, 3661000);
        String output = serialize(List.of(new Playlist("A", List.of(song, song)), new Playlist("空", List.of())),
                Format.JSON, EnumSet.of(Field.NAME, Field.ID, Field.DURATION));
        var playlists = new JsonParser().parse(output).getAsJsonArray();
        assertEquals(2, playlists.size());
        var songs = playlists.get(0).getAsJsonObject().getAsJsonArray("songs");
        assertEquals(2, songs.size());
        var entry = songs.get(0).getAsJsonObject();
        assertEquals(3, entry.entrySet().size());
        assertEquals(song.getName(), entry.get("name").getAsString());
        assertEquals("12345678901234567", entry.get("id").getAsString());
        assertEquals("1:01:01", entry.get("duration").getAsString());
        assertEquals(0, playlists.get(1).getAsJsonObject().getAsJsonArray("songs").size());
    }

    @Test void htmlEscapesUntrustedTitlesAndMakesOnlySelectedUrlsClickable() {
        var playlists = List.of(new Playlist("<script>alert('x')</script>", List.of(song("<img src=x> & \"", 42, 0))));
        String output = serialize(playlists, Format.HTML, EnumSet.of(Field.NAME, Field.URL));
        assertTrue(output.contains("charset=\"UTF-8\""));
        assertFalse(output.contains("<script>"));
        assertFalse(output.contains("<img"));
        assertTrue(output.contains("&lt;img src=x&gt; &amp; &quot;"));
        assertTrue(output.contains("href=\"https://music.163.com/song?id=42\""));
        assertFalse(serialize(playlists, Format.HTML, EnumSet.of(Field.NAME)).contains("music.163.com"));
    }

    @Test void markdownEscapesTableSeparatorsMarkupAndNewlines() {
        String output = serialize(List.of(new Playlist("# [title]", List.of(song("a|b\n<script>*x*\\", 1, 0)))),
                Format.MARKDOWN, EnumSet.of(Field.NAME));
        assertTrue(output.contains("## \\# \\[title\\]"));
        assertTrue(output.contains("a\\|b<br>&lt;script&gt;\\*x\\*\\\\"));
        assertTrue(output.contains("| --- |"));
    }

    @Test void csvQuotesCommasQuotesNewlinesAndProtectsFormulaCells() {
        String output = serialize(List.of(new Playlist("=evil", List.of(song("中文,\"歌\"\n曲", 3, 65000)))),
                Format.CSV, EnumSet.of(Field.NAME, Field.ARTIST, Field.DURATION));
        assertEquals("\uFEFF\"歌单\",\"歌名\",\"歌手\",\"时长\"\r\n"
                + "\"'=evil\",\"中文,\"\"歌\"\"\n曲\",\"歌手甲 / 歌手乙\",\"1:05\"\r\n", output);
        for (String dangerous : List.of("+SUM(1)", "-1+2", "@SUM(1)", "  =SUM(1)", "\tformula")) {
            assertTrue(serialize(List.of(new Playlist("安全", List.of(song(dangerous, 1, 0)))),
                    Format.CSV, EnumSet.of(Field.NAME)).contains("\"'" + dangerous + "\""));
        }
    }

    @Test void textNormalizesSeparatorsAndHandlesMissingMetadata() {
        Music missing = new Gson().fromJson("{\"name\":\"a\\tb\\nc\",\"dt\":-1}", Music.class);
        String output = serialize(List.of(new Playlist("空\n标题", List.of(missing))), Format.TEXT, EnumSet.allOf(Field.class));
        assertTrue(output.contains("歌单：空 标题（1 首歌曲）"));
        assertTrue(output.contains("a b c\t\t\t\t0:00"));
    }

    @Test void allFormatsRetainEmptyPlaylistsAndRejectEmptySelections() {
        for (Format format : Format.values()) {
            assertTrue(serialize(List.of(new Playlist("空歌单", List.of())), format, EnumSet.of(Field.NAME)).contains("空歌单"));
            assertThrows(IllegalArgumentException.class, () -> serialize(List.of(), format, EnumSet.of(Field.NAME)));
            assertThrows(IllegalArgumentException.class, () -> serialize(List.of(new Playlist("A", List.of())), format, EnumSet.noneOf(Field.class)));
        }
    }

    @Test void savingUsesUtf8AndNeverOverwritesWithoutConfirmation() throws Exception {
        Path target = directory.resolve("歌曲.txt");
        write(target, "中文 🎵", false);
        assertEquals("中文 🎵", Files.readString(target, StandardCharsets.UTF_8));
        assertThrows(FileAlreadyExistsException.class, () -> write(target, "unexpected", false));
        assertEquals("中文 🎵", Files.readString(target));
        write(target, "new", true);
        assertEquals("new", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void saveExtensionMatchesChosenFormat() {
        assertEquals(Path.of("songs.HTML"), PlaylistExportJob.withExtension(Path.of("songs.HTML"), Format.HTML));
        assertEquals(Path.of("songs.csv"), PlaylistExportJob.withExtension(Path.of("songs"), Format.CSV));
        assertEquals(Path.of("songs.txt.json"), PlaylistExportJob.withExtension(Path.of("songs.txt"), Format.JSON));
    }
}
