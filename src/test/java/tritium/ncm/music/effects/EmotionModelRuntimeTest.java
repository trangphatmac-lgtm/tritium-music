package tritium.ncm.music.effects;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class EmotionModelRuntimeTest {
    private static final Gson GSON = new Gson();
    // Observed maximum against official MNN 3.6.2 CPU is 3.624e-5. A small
    // allowance covers differing reduction/FMA orders without hiding wrong weights.
    private static final float PARITY_TOLERANCE = 0.0001f;

    @Test void matchesOfficialMnnForRealVocabularyRandomAndBoundaryInputs() throws Exception {
        JsonObject oracle = oracle();
        assertEquals(EmotionModelRuntime.MODEL_SHA256, oracle.get("modelSha256").getAsString());
        JsonArray cases = oracle.getAsJsonArray("cases");
        assertEquals(118, cases.size());
        try (EmotionModelRuntime runtime = new EmotionModelRuntime()) {
            for (var element : cases) {
                JsonObject sample = element.getAsJsonObject();
                float[] expected = vector(sample, "expected");
                float[] actual = predict(runtime, sample);
                assertEquals(32, actual.length);
                for (int i = 0; i < 32; i++) {
                    assertEquals(expected[i], actual[i], PARITY_TOLERANCE,
                            sample.get("name").getAsString() + " output[" + i + "]");
                }
            }
        }
    }

    @Test void predictionsDoNotMutateInputsOrShareResults() throws Exception {
        JsonObject sample = oracle().getAsJsonArray("cases").get(1).getAsJsonObject();
        float[] features = vector(sample, "features");
        float[] base = vector(sample, "base");
        float[] originalFeatures = features.clone(), originalBase = base.clone();
        try (EmotionModelRuntime runtime = new EmotionModelRuntime()) {
            int emotion = sample.get("emotion").getAsInt(), genre = sample.get("genre").getAsInt();
            float[] first = runtime.predict(emotion, genre, features, base);
            float[] second = runtime.predict(emotion, genre, features, base);
            assertArrayEquals(first, second);
            assertArrayEquals(originalFeatures, features);
            assertArrayEquals(originalBase, base);
            first[0] = 1234;
            assertArrayEquals(second, runtime.predict(emotion, genre, features, base));
            assertNotEquals(base[0], second[0], "The learned model must actually change this fixture");
        }
    }

    @Test void concurrentCallsHaveIndependentActivations() throws Exception {
        JsonArray cases = oracle().getAsJsonArray("cases");
        try (EmotionModelRuntime runtime = new EmotionModelRuntime();
             var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<float[]>> calls = new ArrayList<>();
            List<float[]> expected = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                JsonObject sample = cases.get(i).getAsJsonObject();
                expected.add(predict(runtime, sample));
                calls.add(() -> predict(runtime, sample));
            }
            var results = executor.invokeAll(calls);
            for (int i = 0; i < results.size(); i++) assertArrayEquals(expected.get(i), results.get(i).get());
        }
    }

    @Test void rejectsDamagedOrMissingModel(@TempDir Path directory) throws Exception {
        byte[] bytes;
        try (InputStream stream = EmotionModelRuntime.class.getResourceAsStream(EmotionModelRuntime.RESOURCE)) {
            assertNotNull(stream);
            bytes = stream.readAllBytes();
        }
        bytes[bytes.length / 2] ^= 1;
        Path corrupt = directory.resolve("corrupt.mnn");
        Files.write(corrupt, bytes);
        IOException failure = assertThrows(IOException.class, () -> new EmotionModelRuntime(corrupt));
        assertTrue(failure.getMessage().contains("SHA-256"));
        assertThrows(IOException.class, () -> new EmotionModelRuntime(directory.resolve("missing.mnn")));
    }

    @Test void rejectsInvalidInputsAndCallsAfterClose() throws Exception {
        EmotionModelRuntime runtime = new EmotionModelRuntime();
        try (runtime) {
            assertThrows(IllegalArgumentException.class, () -> runtime.predict(16, 0, new float[64], new float[32]));
            assertThrows(IllegalArgumentException.class, () -> runtime.predict(0, -1, new float[64], new float[32]));
            assertThrows(IllegalArgumentException.class, () -> runtime.predict(0, 0, new float[63], new float[32]));
            assertThrows(IllegalArgumentException.class, () -> runtime.predict(0, 0, new float[64], null));
            float[] bad = new float[64];
            bad[4] = Float.NaN;
            assertThrows(IllegalArgumentException.class, () -> runtime.predict(0, 0, bad, new float[32]));
            bad[4] = Float.POSITIVE_INFINITY;
            assertThrows(IllegalArgumentException.class, () -> runtime.predict(0, 0, bad, new float[32]));
        }
        runtime.close();
        assertThrows(IllegalStateException.class, () -> runtime.predict(0, 0, new float[64], new float[32]));
    }

    private static JsonObject oracle() throws IOException {
        try (InputStream stream = EmotionModelRuntimeTest.class.getResourceAsStream("/tritium/emotion-model-oracle.json")) {
            assertNotNull(stream);
            return new JsonParser().parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static float[] vector(JsonObject sample, String key) {
        return GSON.fromJson(sample.get(key), float[].class);
    }

    private static float[] predict(EmotionModelRuntime runtime, JsonObject sample) {
        return runtime.predict(sample.get("emotion").getAsInt(), sample.get("genre").getAsInt(),
                vector(sample, "features"), vector(sample, "base"));
    }
}
