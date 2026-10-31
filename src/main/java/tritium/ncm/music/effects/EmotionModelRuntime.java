package tritium.ncm.music.effects;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * CPU evaluator for the bundled emofxnet MNN graph, with no native/runtime dependencies.
 *
 * <p>The SHA-256 pins the graph this evaluator specializes: four 1x1 convolutions
 * (dense layers), two layer normalizations, and the graph's input/output arithmetic.
 * Batch-one reshape and NCHW/NC4HW4 layout operations are folded into logical vectors.
 * All learned weights, normalization constants and lookup tables are read from the
 * original MNN FlatBuffer; no exported or replacement weights are used.</p>
 *
 * <p>Instances support concurrent predictions. Each call has independent activations,
 * leaves caller inputs untouched, and returns a fresh 32-element array. Close prevents
 * subsequent calls; an already running prediction may finish. See tools/emotion-model
 * for the official MNN parity fixtures and the exact specialization contract.</p>
 */
public final class EmotionModelRuntime implements AutoCloseable {
    public static final String RESOURCE = "/tritium/audioeffects/emotion/emofxnet.mnn";
    public static final String MODEL_SHA256 = "a5537bea1958bd27202cba2fdf3754858cb788e0bc18366c6ecc9894b05236d1";
    private static final int MAX_MODEL_BYTES = 2 * 1024 * 1024;

    private final float[] defaults, residualScales, minimum, outputRange, baseInverseRange;
    private final float[] residualInverseRange, emotionEye, genreEye, featureMean, featureInverseStd;
    private final float[] norm1Weight, norm1Bias, norm2Weight, norm2Bias;
    private final Dense[] layers;
    private final float epsilon;
    private volatile boolean closed;

    public EmotionModelRuntime() throws IOException {
        this(readResource());
    }

    /** Loads the same pinned model from a file, primarily for offline verification. */
    public EmotionModelRuntime(Path model) throws IOException {
        this(readFile(model));
    }

    private EmotionModelRuntime(byte[] bytes) throws IOException {
        if (!MODEL_SHA256.equals(sha256(bytes))) {
            throw new IOException("Unsupported or damaged emotion model (SHA-256 mismatch)");
        }
        try {
            Model model = new Model(bytes);
            defaults = model.constant("/Flatten_1_output_0", 64 * 32);
            residualScales = model.constant("/Flatten_output_0", 64 * 32);
            minimum = model.constant("aev2_min", 32);
            outputRange = model.constant("onnx::Mul_131", 32);
            baseInverseRange = model.constant("Const32", 32);
            residualInverseRange = model.constant("Const105", 32);
            emotionEye = model.constant("emo_eye", 16 * 16);
            genreEye = model.constant("gen_eye", 4 * 4);
            featureMean = model.constant("feature_mean", 64);
            featureInverseStd = model.constant("Const49", 64);
            norm1Weight = model.constant("mlp.1.weight", 384);
            norm1Bias = model.constant("mlp.1.bias", 384);
            norm2Weight = model.constant("mlp.5.weight", 192);
            norm2Bias = model.constant("mlp.5.bias", 192);
            epsilon = model.constant("/mlp/mlp.1/Constant_output_0", 1)[0];
            layers = new Dense[] {
                    model.dense("/mlp/mlp.0/Gemm_output_0__matmul_converted", 84, 384, false),
                    model.dense("/mlp/mlp.4/Gemm_output_0__matmul_converted", 384, 192, false),
                    model.dense("/mlp/mlp.8/Gemm_output_0__matmul_converted", 192, 96, true),
                    model.dense("/mlp/mlp.11/Gemm_output_0__matmul_converted", 96, 32, false)
            };
        } catch (RuntimeException e) {
            throw new IOException("Invalid emotion model graph", e);
        }
    }

    /**
     * Executes the original batch-one graph. Indices are emotion 0..15 and genre 0..3.
     * Features must have 64 finite values; base must have 32 finite values. A zero
     * base selects the model's own emotion/genre fallback, exactly as in the graph.
     */
    public float[] predict(int emotion, int genre, float[] features, float[] base) {
        if (closed) throw new IllegalStateException("Emotion model is closed");
        if (emotion < 0 || emotion >= 16 || genre < 0 || genre >= 4) {
            throw new IllegalArgumentException("Emotion/genre index outside model vocabulary");
        }
        validateVector(features, 64, "features");
        validateVector(base, 32, "base");

        float[] input = new float[84];
        System.arraycopy(emotionEye, emotion * 16, input, 0, 16);
        System.arraycopy(genreEye, genre * 4, input, 16, 4);
        for (int i = 0; i < 64; i++) {
            input[20 + i] = (features[i] - featureMean[i]) * featureInverseStd[i];
        }
        float[] hidden = layers[0].apply(input);
        normalizeRelu(hidden, norm1Weight, norm1Bias);
        hidden = layers[1].apply(hidden);
        normalizeRelu(hidden, norm2Weight, norm2Bias);
        hidden = layers[2].apply(hidden);
        float[] logits = layers[3].apply(hidden);

        float absoluteSum = 0f;
        for (float value : base) absoluteSum += Math.abs(value);
        boolean useDefault = absoluteSum < epsilon;
        int offset = (emotion * 4 + genre) * 32;
        float[] output = new float[32];
        for (int i = 0; i < 32; i++) {
            float selectedBase = useDefault ? defaults[offset + i] : base[i];
            float normalizedBase = clip((selectedBase - minimum[i]) * baseInverseRange[i]);
            float scale = residualScales[offset + i] * residualInverseRange[i];
            float residual = (float) Math.tanh(logits[i]) * scale;
            // The graph explicitly masks learned residuals when emotion_idx == 0.
            residual *= emotion == 0 ? 0f : 1f;
            output[i] = clip(normalizedBase + residual) * outputRange[i] + minimum[i];
            if (!Float.isFinite(output[i])) {
                throw new IllegalArgumentException("Emotion model produced a non-finite value");
            }
        }
        return output;
    }

    private void normalizeRelu(float[] values, float[] weight, float[] bias) {
        float mean = 0f;
        for (float value : values) mean += value;
        mean /= values.length;
        float variance = 0f;
        for (float value : values) {
            float centered = value - mean;
            variance += centered * centered;
        }
        variance /= values.length;
        float inverseStd = (float) (1.0 / Math.sqrt(variance + epsilon));
        for (int i = 0; i < values.length; i++) {
            float centered = (values[i] - mean) * inverseStd;
            values[i] = Math.max(0f, centered * weight[i] + bias[i]);
        }
    }

    private static float clip(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static void validateVector(float[] values, int count, String name) {
        if (values == null || values.length != count) {
            throw new IllegalArgumentException(name + " must contain " + count + " values");
        }
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        }
    }

    @Override public void close() { closed = true; }

    private record Dense(int inputCount, float[] weights, float[] bias, boolean relu) {
        float[] apply(float[] input) {
            float[] output = new float[bias.length];
            for (int row = 0; row < output.length; row++) {
                float sum = 0f;
                int offset = row * inputCount;
                for (int col = 0; col < inputCount; col++) sum += weights[offset + col] * input[col];
                sum += bias[row];
                output[row] = relu ? Math.max(0f, sum) : sum;
            }
            return output;
        }
    }

    // Minimal read-only FlatBuffer reader. Field slots follow the official MNN schema;
    // union main occupies two slots (main_type, main). The model digest above deliberately
    // rejects alternate graphs rather than silently interpreting unsupported operators.
    private static final class Model {
        private final ByteBuffer buffer;
        private final Map<String, Integer> operators = new HashMap<>();

        Model(byte[] bytes) {
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int root = buffer.getInt(0);
            int ops = vector(root, 3);
            int count = buffer.getInt(ops);
            if (count != 150) throw new IllegalArgumentException("Unexpected operation count");
            for (int i = 0; i < count; i++) {
                int address = ops + 4 + i * 4;
                int op = address + buffer.getInt(address);
                String name = string(op, 3);
                if (operators.put(name, op) != null) throw new IllegalArgumentException("Duplicate operator");
            }
        }

        float[] constant(String name, int size) {
            int op = operator(name, 11); // OpType.Const
            int blob = indirect(field(op, 2));
            if (scalar(blob, 2, 1) != 1) throw new IllegalArgumentException("Expected float constant");
            return floats(blob, 7, size); // Blob.float32s
        }

        Dense dense(String name, int inputs, int outputs, boolean relu) {
            int op = operator(name, 12); // OpType.Convolution
            int conv = indirect(field(op, 2));
            int common = indirect(field(conv, 0));
            if (scalar(common, 10, 0) != outputs || scalar(common, 11, 0) != inputs
                    || scalar(common, 2, 1) != 1 || scalar(common, 3, 1) != 1
                    || scalar(common, 9, 1) != 1
                    || bool(common, 12) != relu || bool(common, 13)) {
                throw new IllegalArgumentException("Unsupported convolution shape");
            }
            return new Dense(inputs, floats(conv, 1, inputs * outputs), floats(conv, 2, outputs), relu);
        }

        private int operator(String name, int type) {
            Integer op = operators.get(name);
            if (op == null || scalar(op, 5, 0) != type) {
                throw new IllegalArgumentException("Missing or unexpected operator: " + name);
            }
            return op;
        }

        private int field(int table, int slot) {
            int vtable = table - buffer.getInt(table);
            int entry = 4 + slot * 2;
            if (entry >= Short.toUnsignedInt(buffer.getShort(vtable))) return 0;
            int offset = Short.toUnsignedInt(buffer.getShort(vtable + entry));
            return offset == 0 ? 0 : table + offset;
        }

        private int scalar(int table, int slot, int fallback) {
            int field = field(table, slot);
            return field == 0 ? fallback : buffer.getInt(field);
        }

        private boolean bool(int table, int slot) {
            int field = field(table, slot);
            return field != 0 && buffer.get(field) != 0;
        }

        private int indirect(int address) {
            if (address == 0) throw new IllegalArgumentException("Missing FlatBuffer field");
            return address + buffer.getInt(address);
        }

        private int vector(int table, int slot) { return indirect(field(table, slot)); }

        private String string(int table, int slot) {
            int start = vector(table, slot);
            int size = buffer.getInt(start);
            return new String(buffer.array(), start + 4, size, StandardCharsets.UTF_8);
        }

        private float[] floats(int table, int slot, int expected) {
            int start = vector(table, slot);
            if (buffer.getInt(start) != expected) throw new IllegalArgumentException("Unexpected weight shape");
            float[] values = new float[expected];
            for (int i = 0; i < expected; i++) {
                values[i] = buffer.getFloat(start + 4 + i * 4);
                if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Non-finite weight");
            }
            return values;
        }
    }

    private static byte[] readResource() throws IOException {
        try (InputStream stream = EmotionModelRuntime.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Bundled emotion model is missing: " + RESOURCE);
            return readBounded(stream);
        }
    }

    private static byte[] readFile(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) { return readBounded(stream); }
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_MODEL_BYTES + 1);
        if (bytes.length > MAX_MODEL_BYTES) throw new IOException("Emotion model is too large");
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError("SHA-256 unavailable", e); }
    }
}
