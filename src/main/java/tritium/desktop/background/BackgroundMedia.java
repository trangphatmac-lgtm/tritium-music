package tritium.desktop.background;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;

/** A cancellable decoder with a one-frame mailbox. No OpenGL or preference access on this thread. */
final class BackgroundMedia implements Runnable {
    static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");
    record Source(String mode, String path, boolean folder, boolean shuffle) { }
    record Pixels(int width, int height, byte[] rgba) { }

    final AtomicReference<Pixels> pending = new AtomicReference<>();
    private final Source source;
    volatile boolean paused;
    volatile double interval = 30;
    volatile String status = "正在加载背景…";
    volatile boolean failed;
    private volatile boolean cancelled;

    BackgroundMedia(Source source) { this.source = source; }

    void cancel() {
        cancelled = true;
        pending.set(null);
    }

    @Override
    public void run() {
        try {
            if (source.path().isBlank()) throw new IOException("请先选择背景文件或文件夹");
            Path path = Path.of(source.path());
            if (source.mode().equals("video")) playVideo(path);
            else showPictures(path);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError e) {
            failed = true;
            status = "背景不可用，已恢复纯色 · " + readableError(e);
            System.err.println("Background media: " + e);
        } finally {
            if (cancelled) pending.set(null);
        }
    }

    private static String readableError(Throwable error) {
        if (error instanceof java.nio.file.NoSuchFileException) return "文件或文件夹已移动";
        if (error instanceof LinkageError) return "视频解码器无法加载";
        if (error instanceof org.bytedeco.javacv.FrameGrabber.Exception) return "视频无法解码，请选择其他文件";
        return error.getMessage() == null ? "无法读取文件" : error.getMessage();
    }

    static List<Path> listPictures(Path folder) throws IOException {
        try (var paths = Files.list(folder)) {
            return paths.filter(Files::isRegularFile).filter(BackgroundMedia::isPicture)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
    }

    static boolean isPicture(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1));
    }

    private void showPictures(Path path) throws Exception {
        List<Path> pictures = new ArrayList<>(source.folder() ? listPictures(path) : List.of(path));
        if (pictures.isEmpty()) throw new IOException("文件夹中没有 JPG / PNG / BMP / GIF 图片");
        if (source.shuffle()) Collections.shuffle(pictures);
        int index = 0;
        int skipped = 0;
        while (!cancelled) {
            waitUntilVisible();
            Path picture = pictures.get(index);
            try {
                Pixels pixels = readPicture(picture);
                if (cancelled) return;
                pending.set(pixels);
                status = (source.folder() ? "轮播 · " + pictures.size() + " 张 · " : "图片 · ") + picture.getFileName()
                        + (skipped > 0 ? " · 已跳过 " + skipped + " 个损坏文件" : "");
            } catch (IOException | RuntimeException e) {
                pictures.remove(index);
                skipped++;
                if (pictures.isEmpty()) throw new IOException("没有可读取的图片", e);
                index %= pictures.size();
                continue;
            }
            if (pictures.size() == 1) return;
            double elapsed = 0;
            while (!cancelled && elapsed < interval) {
                waitUntilVisible();
                long start = System.nanoTime();
                Thread.sleep(50);
                if (!paused) elapsed += (System.nanoTime() - start) / 1e9;
            }
            index++;
            if (index == pictures.size()) {
                index = 0;
                if (source.shuffle()) {
                    Path last = pictures.getLast();
                    Collections.shuffle(pictures);
                    if (pictures.getFirst().equals(last)) Collections.swap(pictures, 0, 1);
                }
            }
        }
    }

    static Pixels readPicture(Path path) throws IOException {
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IOException("无法读取图片");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("不支持的图片或文件已损坏");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, (int) Math.ceil(Math.max(reader.getWidth(0), reader.getHeight(0)) / 2560.0));
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, params);
                int width = image.getWidth(), height = image.getHeight();
                byte[] rgba = new byte[width * height * 4];
                int[] row = new int[width];
                for (int y = 0, offset = 0; y < height; y++) {
                    image.getRGB(0, y, width, 1, row, 0, width);
                    for (int argb : row) {
                        rgba[offset++] = (byte) (argb >> 16);
                        rgba[offset++] = (byte) (argb >> 8);
                        rgba[offset++] = (byte) argb;
                        rgba[offset++] = (byte) (argb >>> 24);
                    }
                }
                return new Pixels(width, height, rgba);
            } finally {
                reader.dispose();
            }
        }
    }

    private void playVideo(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IOException("视频文件不存在");
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path.toFile())) {
            grabber.setPixelFormat(AV_PIX_FMT_RGBA);
            grabber.setVideoOption("threads", "2");
            // Local files only: do not resolve remote playlists or external protocol references.
            grabber.setOption("protocol_whitelist", "file,pipe");
            grabber.start();
            int width = grabber.getImageWidth(), height = grabber.getImageHeight();
            if (width <= 0 || height <= 0) throw new IOException("文件中没有视频画面");
            double scale = Math.min(1, Math.min(1920.0 / width, 1080.0 / height));
            grabber.setImageWidth(Math.max(1, (int) Math.round(width * scale)));
            grabber.setImageHeight(Math.max(1, (int) Math.round(height * scale)));
            long origin = System.nanoTime();
            long firstTimestamp = Long.MIN_VALUE;
            long lastPublished = Long.MIN_VALUE;
            long lastTimestamp = 0;
            double rate = grabber.getFrameRate();
            long frameDuration = Math.round(1_000_000 / (Double.isFinite(rate) && rate > 0 ? rate : 30));
            boolean hadFrame = false;
            while (!cancelled) {
                origin += waitUntilVisible();
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (!hadFrame) throw new IOException("视频没有可解码的画面");
                    // Preserve the final frame's duration, including single-frame videos.
                    waitForPresentation(origin + (lastTimestamp + frameDuration) * 1000);
                    // Keep the last frame displayed across the loop boundary.
                    grabber.setVideoTimestamp(0);
                    origin = System.nanoTime();
                    firstTimestamp = Long.MIN_VALUE;
                    lastPublished = Long.MIN_VALUE;
                    hadFrame = false;
                    continue;
                }
                hadFrame = true;
                if (firstTimestamp == Long.MIN_VALUE) firstTimestamp = frame.timestamp;
                long timestamp = Math.max(0, frame.timestamp - firstTimestamp);
                lastTimestamp = timestamp;
                long due = origin + timestamp * 1000;
                long pause = waitForPresentation(due);
                origin += pause;
                due += pause;
                long lateness = System.nanoTime() - due;
                if (lateness > 250_000_000) {
                    // A slow decoder must not drop every subsequent frame until the next loop.
                    origin += lateness;
                    due += lateness;
                }
                if (cancelled) return;
                // Decode at source speed, but upload at most 30 frames/s and drop overdue frames.
                if (lastPublished != Long.MIN_VALUE && (timestamp - lastPublished < 32_000
                        || System.nanoTime() - due > 100_000_000)) continue;
                pending.set(copyFrame(frame));
                lastPublished = timestamp;
                status = "视频 · 静音循环 · " + path.getFileName();
            }
        }
    }

    private long waitForPresentation(long due) throws InterruptedException {
        long totalPause = 0;
        while (!cancelled) {
            long pause = waitUntilVisible();
            totalPause += pause;
            due += pause;
            long remaining = due - System.nanoTime();
            if (remaining <= 0) return totalPause;
            Thread.sleep(Math.min(20, Math.max(1, remaining / 1_000_000)));
        }
        throw new InterruptedException();
    }

    private static Pixels copyFrame(Frame frame) {
        int rowBytes = frame.imageWidth * 4;
        byte[] rgba = new byte[rowBytes * frame.imageHeight];
        ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
        for (int y = 0; y < frame.imageHeight; y++) {
            buffer.position(y * frame.imageStride);
            buffer.get(rgba, y * rowBytes, rowBytes);
        }
        return new Pixels(frame.imageWidth, frame.imageHeight, rgba);
    }

    private long waitUntilVisible() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!paused) return 0;
        long start = System.nanoTime();
        while (paused && !cancelled) Thread.sleep(50);
        if (cancelled) throw new InterruptedException();
        return System.nanoTime() - start;
    }
}
