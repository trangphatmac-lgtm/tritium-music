package tritium.ncm.music.lyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Keeps imported content in application data so moving the original file is harmless. */
public final class LocalLyricStore {
    public static final List<String> EXTENSIONS = List.of("lrc", "yrc", "qrc", "krc", "ttml", "vtt");
    private final Path directory;

    public LocalLyricStore(Path directory) {
        this.directory = directory;
    }

    public LyricDocument importFile(LyricSong song, Path file) throws IOException {
        String name = file.getFileName().toString();
        String format = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(format)) throw new IOException("Unsupported lyric format");
        byte[] bytes = Files.readAllBytes(file);
        String text = format.equals("krc") ? LyricCompression.kugou(bytes) : decode(bytes);
        LyricDocument document = parse(format, text);
        if (!document.usable()) throw new IOException("No timed lyrics found");

        JsonObject saved = new JsonObject();
        saved.addProperty("format", format);
        saved.addProperty("text", text);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "import-", ".tmp");
        try {
            Files.writeString(temporary, saved.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path(song), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path(song), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return document;
    }

    public LyricDocument load(LyricSong song) {
        Path file = path(song);
        if (!Files.isRegularFile(file)) return LyricDocument.EMPTY;
        try {
            JsonObject saved = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return parse(saved.get("format").getAsString(), saved.get("text").getAsString());
        } catch (IOException | RuntimeException e) {
            System.err.println("[LocalLyrics] Unable to read " + file + ": " + e.getMessage());
            return LyricDocument.EMPTY;
        }
    }

    private Path path(LyricSong song) {
        String key = HexFormat.of().formatHex(song.id().getBytes(StandardCharsets.UTF_8));
        return directory.resolve(key + ".json");
    }

    private static LyricDocument parse(String format, String text) throws IOException {
        try {
            if (format.equals("qrc") && text.strip().matches("[0-9a-fA-F]+")) text = QrcDecoder.decode(text.strip());
            return OnlineLyricParser.parse(format, text, "", "");
        } catch (Exception e) {
            throw new IOException("Unable to parse lyric file", e);
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
                || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) {
            return StandardCharsets.UTF_16.decode(ByteBuffer.wrap(bytes)).toString();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF", "");
        } catch (CharacterCodingException e) {
            return Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
}
