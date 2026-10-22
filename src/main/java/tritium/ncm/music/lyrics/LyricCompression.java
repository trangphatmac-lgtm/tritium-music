package tritium.ncm.music.lyrics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

/** Decodes Kugou KRC and bounds expansion of compressed lyric payloads. */
final class LyricCompression {
    private static final int[] KRC_KEY = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};
    private LyricCompression() {}
    static String inflate(byte[] bytes) throws IOException {
        try (var input = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            byte[] decoded = input.readNBytes(4 * 1024 * 1024 + 1);
            if (decoded.length > 4 * 1024 * 1024) throw new IOException("Lyric payload exceeds limit");
            return new String(decoded, StandardCharsets.UTF_8).replace("\uFEFF", "");
        }
    }
    static String kugou(byte[] bytes) throws IOException {
        if (bytes.length >= 4 && bytes[0] == 'k' && bytes[1] == 'r' && bytes[2] == 'c' && bytes[3] == '1') {
            byte[] content = Arrays.copyOfRange(bytes, 4, bytes.length);
            for (int i = 0; i < content.length; i++) content[i] ^= (byte) KRC_KEY[i % KRC_KEY.length];
            return inflate(content);
        }
        return new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
    }
}
