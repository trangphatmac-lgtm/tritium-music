package tritium.ncm.music.effects;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class NcaeDecoderTest {
    @Test void independentlyRecoveredRealContainersMatchBundledJsonExactly() throws IOException {
        for (String name : new String[]{"smart-presets", "master-base", "master-diy"}) {
            byte[] container = resource(name + ".ncae");
            assertEquals(new JsonParser().parse(new String(resource(name + ".json"), StandardCharsets.UTF_8)),
                    NcaeDecoder.decodeJson(container), name);
        }
    }

    @Test void rejectsBadHeaderVersionsSizesAndKeyRegions() throws IOException {
        byte[] valid = resource("smart-presets.ncae");
        assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decode(null));
        assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decode(new byte[0]));
        for (int size : new int[]{1, 16, 17, 21, valid.length - 1}) {
            assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decode(Arrays.copyOf(valid, size)));
        }
        for (int offset : new int[]{0, 4, 12, 14, 16}) {
            byte[] bad = valid.clone();
            bad[offset] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decode(bad));
        }
        for (int keyLength : new int[]{0, 1, 4, 6}) {
            byte[] bad = valid.clone();
            bad[16] = (byte) keyLength;
            assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decode(bad));
        }
        assertThrows(IllegalArgumentException.class,
                () -> NcaeDecoder.decode(new byte[NcaeDecoder.MAX_INPUT_BYTES + 1]));
    }

    @Test void rejectsInvalidOrTruncatedDeflateAndExcessTrailingBytes() {
        assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decode(container(new byte[]{(byte) 255}, 1)));
        byte[] compressed = deflate("{\"test\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> NcaeDecoder.decode(container(Arrays.copyOf(compressed, compressed.length - 1), 1)));
        assertThrows(IllegalArgumentException.class,
                () -> NcaeDecoder.decode(container(Arrays.copyOf(compressed, compressed.length + 1), 1)));
    }

    @Test void outputLimitStopsHighlyCompressiblePayloads() {
        byte[] overLimit = new byte[NcaeDecoder.MAX_OUTPUT_BYTES + 1];
        Arrays.fill(overLimit, (byte) ' ');
        byte[] compressed = deflate(overLimit);
        assertTrue(compressed.length < 100_000);
        assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decode(container(compressed, 1)));
    }

    @Test void jsonReaderRejectsBinaryInvalidUtf8BadSyntaxAndDeepNesting() {
        assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decodeJson(encoded("{}", 2)));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), NcaeDecoder.decode(encoded("{}", 2)));
        assertThrows(IllegalArgumentException.class,
                () -> NcaeDecoder.decodeJson(container(deflate(new byte[]{(byte) 0xc3, 0x28}), 1)));
        for (String bad : new String[]{"[]", "{", "{}{}", "{bad:1}", "{\"a\":NaN}", "{/*x*/\"a\":1}"}) {
            assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decodeJson(encoded(bad, 1)), bad);
        }
        String deep = "{\"a\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}";
        assertThrows(IllegalArgumentException.class, () -> NcaeDecoder.decodeJson(encoded(deep, 1)));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = NcaeDecoderTest.class.getResourceAsStream("/tritium/audioeffects/" + name)) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        }
    }

    private static byte[] encoded(String value, int format) {
        return container(deflate(value.getBytes(StandardCharsets.UTF_8)), format);
    }

    private static byte[] deflate(byte[] value) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(value);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Only a fixture writer; production never needs to produce NCAE packages. */
    private static byte[] container(byte[] compressed, int format) {
        byte[] encodedKey = {(byte) 0x95, 0x21, 0x63, 0x42, 0x37};
        int[] state = new int[256];
        for (int i = 0; i < 256; i++) state[i] = i;
        for (int i = 0, j = 0; i < 256; i++) {
            j = (j + state[i] + ((encodedKey[i % 4] ^ encodedKey[4]) & 255)) & 255;
            int previous = state[i];
            state[i] = state[j];
            state[j] = previous;
        }
        ByteBuffer result = ByteBuffer.allocate(17 + encodedKey.length + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        result.put(new byte[]{'N', 'C', 'A', 'E'}).putInt(compressed.length).putInt(0)
                .putShort((short) 1).putShort((short) format).put((byte) encodedKey.length).put(encodedKey);
        for (int position = 0; position < compressed.length; position++) {
            int i = (position + 1) & 255;
            int stream = state[(state[(state[i] + i) & 255] + state[i]) & 255];
            result.put((byte) (compressed[position] ^ stream));
        }
        return result.array();
    }
}
