package tritium.ncm.music.stream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.BitSet;
import java.util.regex.Pattern;

/** Seekable compressed input for the decoder. Only complete responses become reusable caches.
 * A persistent HTTP response serves consecutive blocks; Range is used only after a seek. */
final class HttpAudioCache extends InputStream {
    private static final int BLOCK = 64 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final String url;
    private final Path destination, temporary;
    private final RandomAccessFile file;
    private final BitSet blocks = new BitSet();
    private volatile HttpURLConnection connection;
    private InputStream response;
    private long responsePosition, responseEnd, position, mark;
    private volatile long length = -1, downloaded;
    private volatile boolean cancelled, committed;
    private boolean ranges;
    private String entityTag;
    private IOException readFailure;
    private final long started = System.nanoTime();

    HttpAudioCache(String url, Path destination) throws IOException {
        this.url = url;
        this.destination = destination;
        Files.createDirectories(destination.toAbsolutePath().getParent());
        temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), "stream-", ".part");
        file = new RandomAccessFile(temporary.toFile(), "rw");
    }

    void open() throws IOException { connect(0); }
    boolean seekable() { return length >= 0; }
    long downloaded() { return downloaded; }
    boolean committed() { return committed; }
    double progress() { return committed ? 1 : length > 0 ? Math.min(1, (double) downloaded / length) : 0; }
    double bytesPerSecond() { return downloaded / Math.max(.001, (System.nanoTime() - started) / 1e9); }

    private void connect(long offset) throws IOException {
        checkCancelled();
        disconnect();
        HttpURLConnection next = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection = next;
        checkCancelled();
        next.setConnectTimeout(5_000);
        next.setReadTimeout(10_000);
        next.setRequestProperty("Accept-Encoding", "identity");
        next.setRequestProperty("Range", "bytes=" + offset + "-");
        if (entityTag != null) next.setRequestProperty("If-Range", entityTag);
        int status = next.getResponseCode();
        if (status == 206) {
            var matcher = RANGE.matcher(String.valueOf(next.getHeaderField("Content-Range")));
            if (!matcher.matches() || Long.parseLong(matcher.group(1)) != offset)
                throw new IOException("Invalid audio Content-Range");
            long total = Long.parseLong(matcher.group(3));
            responseEnd = Long.parseLong(matcher.group(2)) + 1;
            if (responseEnd <= offset || responseEnd > total || (length >= 0 && total != length))
                throw new IOException("Audio length changed during download");
            length = total;
            ranges = true;
        } else if (status == 200 && offset == 0) {
            length = next.getContentLengthLong();
            responseEnd = length;
            ranges = false;
        } else throw new IOException("Audio HTTP status " + status);
        if (length == 0 || length > (long) BLOCK * Integer.MAX_VALUE)
            throw new IOException("Invalid audio length");
        response = next.getInputStream();
        if (entityTag == null) entityTag = next.getHeaderField("ETag");
        responsePosition = offset;
        checkCancelled();
    }

    @Override public int read(byte[] bytes, int offset, int count) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, count, bytes.length);
        if (count == 0) return 0;
        if (readFailure != null) throw readFailure;
        try { return readAudio(bytes, offset, count); }
        catch (IOException e) { readFailure = e; throw e; }
    }

    private int readAudio(byte[] bytes, int offset, int count) throws IOException {
        checkCancelled();
        if (length < 0) {
            int n = response.read(bytes, offset, count);
            if (n < 0) { length = position; return -1; }
            file.seek(position);
            file.write(bytes, offset, n);
            position += n;
            downloaded = position;
            responsePosition = position;
            return n;
        }
        if (position >= length) return -1;
        int block = (int) (position / BLOCK);
        fill(block);
        int n = (int) Math.min(count, Math.min(length - position, BLOCK - position % BLOCK));
        file.seek(position);
        file.readFully(bytes, offset, n);
        position += n;
        return n;
    }

    private void fill(int block) throws IOException {
        if (blocks.get(block)) return;
        long start = (long) block * BLOCK;
        if (responsePosition != start) {
            if (ranges) connect(start);
            else {
                // Servers ignoring Range still stream from the beginning; a forward seek waits
                // only on this decoder worker, never on the UI or synthesizer.
                while (responsePosition < start) fill((int) (responsePosition / BLOCK));
            }
        }
        checkCancelled();
        int size = (int) Math.min(BLOCK, length - start);
        byte[] data = new byte[size];
        int read = 0;
        while (read < size) {
            checkCancelled();
            if (ranges && responsePosition == responseEnd && responseEnd < length) connect(responsePosition);
            int n = response.read(data, read, (int) Math.min(size - read, responseEnd - responsePosition));
            if (n < 0) throw new EOFException("Truncated audio response");
            if (n == 0) throw new EOFException("Empty audio response");
            read += n;
            responsePosition += n;
        }
        file.seek(start);
        file.write(data);
        blocks.set(block);
        responsePosition = start + size;
        downloaded += size;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }
    @Override public boolean markSupported() { return true; }
    @Override public void mark(int limit) { mark = position; }
    @Override public void reset() throws IOException { checkCancelled(); position = mark; }
    @Override public long skip(long count) throws IOException {
        checkCancelled();
        long skipped = length < 0 ? 0 : Math.max(0, Math.min(count, length - position));
        position += skipped;
        return skipped;
    }

    /** Called after the decoder releases the input. Fetch any unreferenced tags/padding as well. */
    void commit() throws IOException {
        checkCancelled();
        if (readFailure != null) throw readFailure;
        if (length <= 0) throw new IOException("Empty audio download");
        // Unknown-length streams were saved contiguously until EOF.
        if (downloaded != length)
            for (int block = 0; (long) block * BLOCK < length; block++) fill(block);
        checkCancelled();
        disconnect();
        file.close();
        if (Files.isRegularFile(destination) && Files.size(destination) > 0) {
            // AutoMix may have completed the same quality while this decoder was running.
            Files.deleteIfExists(temporary);
        } else {
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        committed = true;
    }

    /** May be called from any thread. Socket teardown must stay off the real-time audio thread. */
    void cancel() {
        cancelled = true;
        HttpURLConnection active = connection;
        if (active != null) Thread.startVirtualThread(active::disconnect);
    }
    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audio download cancelled");
    }
    private void disconnect() throws IOException {
        if (response != null) { response.close(); response = null; }
        if (connection != null) { connection.disconnect(); connection = null; }
    }
    @Override public void close() throws IOException {
        try { disconnect(); }
        finally { try { file.close(); } finally { Files.deleteIfExists(temporary); } }
    }
}
