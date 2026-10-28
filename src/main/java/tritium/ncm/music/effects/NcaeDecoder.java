package tritium.ncm.music.effects;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for NCAE v1 containers. Reconstructed from ncae_decrypt and RCN::rcn,
 * without loading the Android library. The key schedule resembles RC4, but its
 * immutable, position-indexed output transform is deliberately NOT RC4's PRGA.
 */
public final class NcaeDecoder {
    public static final int MAX_INPUT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 64;

    private NcaeDecoder() {}

    public static byte[] decode(byte[] data) {
        validateHeader(data);
        int keyRegionLength = data[16] & 255;
        int keyLength = keyRegionLength - 1;
        int mask = data[21] & 255;
        int[] key = new int[keyLength];
        for (int i = 0, out = 0; i < keyRegionLength; i++) {
            if (i != 4) key[out++] = (data[17 + i] & 255) ^ mask;
        }
        int[] state = new int[256];
        for (int i = 0; i < 256; i++) state[i] = i;
        for (int i = 0, j = 0; i < 256; i++) {
            j = (j + state[i] + key[i % keyLength]) & 255;
            int previous = state[i];
            state[i] = state[j];
            state[j] = previous;
        }
        int offset = 17 + keyRegionLength;
        byte[] compressed = new byte[data.length - offset];
        for (int position = 0; position < compressed.length; position++) {
            int i = (position + 1) & 255;
            int stream = state[(state[(state[i] + i) & 255] + state[i]) & 255];
            compressed[position] = (byte) (data[offset + position] ^ stream);
        }

        Inflater inflater = new Inflater(true); // Raw DEFLATE, no zlib wrapper.
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(compressed.length * 4, 65536));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read > 0) {
                    if (result.size() > MAX_OUTPUT_BYTES - read) {
                        throw invalid("Decoded payload exceeds size limit");
                    }
                    result.write(buffer, 0, read);
                } else if (!inflater.finished()) {
                    throw invalid(inflater.needsInput() ? "Truncated DEFLATE payload" : "Invalid DEFLATE payload");
                }
            }
            if (inflater.getRemaining() != 0) throw invalid("Trailing compressed payload");
            return result.toByteArray();
        } catch (DataFormatException exception) {
            throw new IllegalArgumentException("Invalid NCAE DEFLATE payload", exception);
        } finally {
            inflater.end();
        }
    }

    public static JsonObject decodeJson(byte[] data) {
        validateHeader(data);
        if (unsignedShort(data, 14) != 1) throw invalid("Payload is not JSON");
        byte[] decoded = decode(data);
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid NCAE UTF-8 payload", exception);
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement value = readJson(reader, 0);
            if (!value.isJsonObject()) throw invalid("JSON payload must be an object");
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("Trailing JSON data");
            return value.getAsJsonObject();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid NCAE JSON payload", exception);
        }
    }

    private static void validateHeader(byte[] data) {
        if (data == null || data.length < 22 || data.length > MAX_INPUT_BYTES
                || data[0] != 'N' || data[1] != 'C' || data[2] != 'A' || data[3] != 'E') {
            throw invalid("Invalid container header or size");
        }
        if (unsignedShort(data, 12) != 1) throw invalid("Unsupported container version");
        int format = unsignedShort(data, 14);
        if (format != 1 && format != 2) throw invalid("Unsupported payload format");
        int keyLength = data[16] & 255;
        if (keyLength < 5 || (keyLength - 1) % 4 != 0) throw invalid("Invalid key region length");
        long compressedLength = (data[4] & 255L) | ((data[5] & 255L) << 8)
                | ((data[6] & 255L) << 16) | ((data[7] & 255L) << 24);
        if (compressedLength == 0 || 17L + keyLength + compressedLength != data.length) {
            throw invalid("Inconsistent compressed payload length");
        }
    }

    private static int unsignedShort(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    /** Keep strict syntax and bounded recursion even with the legacy Gson version. */
    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH) throw invalid("JSON nesting exceeds limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) object.add(reader.nextName(), readJson(reader, depth + 1));
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 128) throw invalid("JSON number exceeds limit");
                BigDecimal value = new BigDecimal(number);
                if (!Double.isFinite(value.doubleValue())) throw invalid("Non-finite JSON number");
                yield new JsonPrimitive(value);
            }
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw invalid("Unexpected JSON token");
        };
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid NCAE: " + reason);
    }
}
