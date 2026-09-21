package repackage.com.jsyn.util.soundfile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mp4parser.muxer.FileRandomAccessSourceImpl;
import org.mp4parser.muxer.container.mp4.MovieCreator;
import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.util.SampleLoader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FragmentedMp4Test {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"tone-fragmented.m4a", "tone-fragmented-base-offset.m4a"})
    void fragmentedDownloadsDecodeAllFramesWithoutChangingOriginal(String name) throws Exception {
        Path plain = copyFixture("tone.m4a");
        Path file = copyFixture(name);
        byte[] original = Files.readAllBytes(file);
        assertFalse(FragmentedMp4.isFragmented(plain.toFile()));
        assertTrue(FragmentedMp4.isFragmented(file.toFile()));
        Path normalized = Files.createFile(tempDir.resolve("normalized.m4a"));
        FragmentedMp4.remux(file.toFile(), normalized);
        assertFalse(FragmentedMp4.isFragmented(normalized.toFile()));
        assertCompressedSamplesEqual(plain, normalized);
        FloatSample expected = SampleLoader.loadStreamedFloatSample(plain.toFile());
        FloatSample actual = SampleLoader.loadStreamedFloatSample(file.toFile());
        try {
            assertEquals(48000, actual.getFrameRate());
            assertEquals(2, actual.getChannelsPerFrame());
            assertEquals(0.25, actual.getNumFrames() / actual.getFrameRate(), 0.05);
            assertEquals(expected.getNumFrames(), actual.getNumFrames(), "Every fragment must be decoded");
            assertArrayEquals(original, Files.readAllBytes(file), "The downloaded cache must stay unchanged");
            double energy = 0;
            for (float value : actual.buffer) energy += value * value;
            assertTrue(energy > 1, "Decoded audio must not be silent");
            float[] middle = new float[128];
            actual.read(actual.getNumFrames() / 2, middle, 0, 64);
            assertEquals(actual.readDouble(actual.getNumFrames() / 2 * 2), middle[0]);
        } finally {
            expected.cleanUp();
            actual.cleanUp();
        }
        Files.delete(file); // Also catches leaked source handles on Windows.
    }

    @Test
    void malformedBoxLengthsFailInsteadOfSeekingOutsideFileOrLooping() throws Exception {
        Path file = tempDir.resolve("broken.m4a");
        for (byte[] bytes : new byte[][] {
                new byte[7],
                ByteBuffer.allocate(8).putInt(4).putInt(0x66747970).array(),
                ByteBuffer.allocate(8).putInt(32).putInt(0x6d6f6f66).array(),
                ByteBuffer.allocate(8).putInt(1).putInt(0x66747970).array(),
                ByteBuffer.allocate(16).putInt(1).putInt(0x66747970).putLong(-1).array()
        }) {
            Files.write(file, bytes);
            assertThrows(IOException.class, () -> FragmentedMp4.isFragmented(file.toFile()));
        }
    }

    @Test
    void boxPayloadIsNotMistakenForFragmentHeader() throws Exception {
        Path file = tempDir.resolve("plain.m4a");
        Files.write(file, ByteBuffer.allocate(20).putInt(1).putInt(0x6d646174)
                .putLong(20).putInt(0x6d6f6f66).array());
        assertFalse(FragmentedMp4.isFragmented(file.toFile()));
        Files.write(file, ByteBuffer.allocate(12).putInt(0).putInt(0x6d646174).putInt(0x6d6f6f66).array());
        assertFalse(FragmentedMp4.isFragmented(file.toFile()));
    }

    private Path copyFixture(String name) throws IOException {
        Path target = tempDir.resolve(name);
        try (var in = getClass().getResourceAsStream("/audio/" + name)) {
            assertNotNull(in);
            Files.copy(in, target);
        }
        return target;
    }

    private void assertCompressedSamplesEqual(Path expected, Path actual) throws IOException {
        try (var expectedBoxes = Files.newByteChannel(expected);
             var actualBoxes = Files.newByteChannel(actual);
             var expectedData = new RandomAccessFile(expected.toFile(), "r");
             var actualData = new RandomAccessFile(actual.toFile(), "r")) {
            var original = MovieCreator.build(expectedBoxes, new FileRandomAccessSourceImpl(expectedData), "original");
            var remuxed = MovieCreator.build(actualBoxes, new FileRandomAccessSourceImpl(actualData), "remuxed");
            var originalSamples = original.getTracks().getFirst().getSamples();
            var remuxedSamples = remuxed.getTracks().getFirst().getSamples();
            assertEquals(originalSamples.size(), remuxedSamples.size());
            for (int i = 0; i < originalSamples.size(); i++) {
                assertEquals(originalSamples.get(i).asByteBuffer(), remuxedSamples.get(i).asByteBuffer(),
                        "Compressed sample " + i + " must survive unchanged");
            }
        }
    }
}
