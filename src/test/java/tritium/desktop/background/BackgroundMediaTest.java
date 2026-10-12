package tritium.desktop.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundMediaTest {
    @TempDir Path temp;

    @Test
    void filtersAndSortsFolderFilesAndPreservesRgbaOrientation() throws Exception {
        Files.createDirectory(temp.resolve("nested.png"));
        Files.writeString(temp.resolve("notes.txt"), "not an image");
        writeImage(temp.resolve("B.PNG"), 0xFF00FF00);
        Path first = temp.resolve("a.png");
        writeImage(first, 0x80FF0000);
        assertEquals(List.of(first, temp.resolve("B.PNG")), BackgroundMedia.listPictures(temp));
        BackgroundMedia.Pixels pixels = BackgroundMedia.readPicture(first);
        assertEquals(2, pixels.width());
        assertEquals(2, pixels.height());
        assertArrayEquals(new byte[]{(byte) 255, 0, 0, (byte) 128}, java.util.Arrays.copyOf(pixels.rgba(), 4));
        assertEquals((byte) 255, pixels.rgba()[10], "Bottom-left is blue");
    }

    @Test
    void slideshowSkipsCorruptFilesPausesAndCancels() throws Exception {
        Files.writeString(temp.resolve("0-corrupt.jpg"), "bad image");
        writeImage(temp.resolve("1.png"), 0xFFFF0000);
        writeImage(temp.resolve("2.png"), 0xFF00FF00);
        BackgroundMedia media = new BackgroundMedia(new BackgroundMedia.Source("picture", temp.toString(), true, false));
        media.interval = .2;
        Thread worker = new Thread(media);
        try {
            worker.start();
            await(() -> media.pending.get() != null);
            assertEquals((byte) 255, media.pending.get().rgba()[0]);
            media.paused = true;
            media.pending.set(null);
            Thread.sleep(300);
            assertNull(media.pending.get());
            media.paused = false;
            await(() -> media.pending.get() != null);
            assertEquals((byte) 255, media.pending.get().rgba()[1]);
            assertTrue(media.status.contains("已跳过 1"));
        } finally {
            media.cancel();
            worker.interrupt();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertNull(media.pending.get());
    }

    @Test
    void emptyFolderAndMissingMediaFailWithoutCrashingTheRenderThread() throws Exception {
        for (var source : List.of(new BackgroundMedia.Source("picture", temp.toString(), true, false),
                new BackgroundMedia.Source("video", temp.resolve("missing.mp4").toString(), false, false))) {
            BackgroundMedia media = new BackgroundMedia(source);
            media.run();
            assertTrue(media.failed);
            assertTrue(media.status.contains("已恢复纯色"));
            assertNull(media.pending.get());
        }
    }

    @Test
    void bundledVideoDecoderLoopsPausesAndReleasesTheFile() throws Exception {
        Path video = temp.resolve("视频 sample.mp4");
        try (var input = getClass().getResourceAsStream("/backgrounds/loop.mp4")) {
            Files.copy(input, video);
        }
        BackgroundMedia media = new BackgroundMedia(new BackgroundMedia.Source("video", video.toString(), false, false));
        Thread worker = new Thread(media);
        try {
            worker.start();
            await(() -> media.pending.get() != null || media.failed);
            assertFalse(media.failed, media.status);
            assertEquals(96, media.pending.get().width());
            Thread.sleep(1100); // More than two complete loops.
            media.pending.set(null);
            await(() -> media.pending.get() != null);
            media.paused = true;
            Thread.sleep(100);
            media.pending.set(null);
            Thread.sleep(200);
            assertNull(media.pending.get());
            media.paused = false;
            await(() -> media.pending.get() != null);
        } finally {
            media.cancel();
            worker.interrupt();
            worker.join(3000);
        }
        assertFalse(worker.isAlive());
        assertNull(media.pending.get());
        Files.delete(video);
    }

    @Test
    void fitsPortraitAndLandscapeWithoutStretchingUnlessRequested() {
        assertEquals(new MainBackgroundRenderer.Bounds(-50, 0, 200, 100), MainBackgroundRenderer.fit(100, 100, 200, 100, "cover"));
        assertEquals(new MainBackgroundRenderer.Bounds(0, 25, 100, 50), MainBackgroundRenderer.fit(100, 100, 200, 100, "contain"));
        assertEquals(new MainBackgroundRenderer.Bounds(25, 0, 50, 100), MainBackgroundRenderer.fit(100, 100, 100, 200, "contain"));
        assertEquals(new MainBackgroundRenderer.Bounds(0, 0, 100, 100), MainBackgroundRenderer.fit(100, 100, 200, 100, "stretch"));
    }

    @Test
    void singleFrameVideoHoldsTheFrameInsteadOfSpinningAtEndOfFile() throws Exception {
        Path video = Path.of(getClass().getResource("/backgrounds/single-frame.mp4").toURI());
        BackgroundMedia media = new BackgroundMedia(new BackgroundMedia.Source("video", video.toString(), false, false));
        Thread worker = new Thread(media);
        try {
            worker.start();
            await(() -> media.pending.get() != null || media.failed);
            assertFalse(media.failed, media.status);
            media.pending.set(null);
            Thread.sleep(250);
            assertNull(media.pending.get(), "The one-second frame should still be displayed");
            await(() -> media.pending.get() != null);
        } finally {
            media.cancel();
            worker.interrupt();
            worker.join(3000);
        }
        assertFalse(worker.isAlive());
    }

    private static void writeImage(Path file, int color) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        image.setRGB(0, 1, 0xFF0000FF);
        ImageIO.write(image, "png", file.toFile());
    }

    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(10);
        });
    }
}
