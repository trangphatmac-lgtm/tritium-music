package repackage.com.jsyn.util.soundfile;

import org.mp4parser.muxer.FileRandomAccessSourceImpl;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.builder.DefaultMp4Builder;
import org.mp4parser.muxer.container.mp4.MovieCreator;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Adapts fragmented MP4 downloads to the sample tables understood by the audio decoders. */
final class FragmentedMp4 {
    private FragmentedMp4() {
    }

    static boolean isFragmented(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            while (input.getFilePointer() < length) {
                long start = input.getFilePointer();
                long remaining = length - start;
                if (remaining < 8) throw new IOException("Truncated MP4 box header");
                long size = Integer.toUnsignedLong(input.readInt());
                int type = input.readInt();
                int headerSize = 8;
                if (size == 1) {
                    if (remaining < 16) throw new IOException("Truncated MP4 extended box header");
                    size = input.readLong();
                    headerSize = 16;
                } else if (size == 0) {
                    size = remaining;
                }
                if (size < headerSize || size > remaining) throw new IOException("Invalid MP4 box size");
                if (type == 0x6d6f6f66) return true; // moof
                input.seek(start + size);
            }
            return false;
        }
    }

    static void remux(File source, Path destination) throws IOException {
        // Keep ownership of both input handles, including when parsing or writing fails.
        try (var boxes = Files.newByteChannel(source.toPath());
             var samples = new RandomAccessFile(source, "r");
             var output = FileChannel.open(destination, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            Movie movie = MovieCreator.build(boxes, new FileRandomAccessSourceImpl(samples), source.getName());
            if (movie.getTracks().isEmpty()) throw new IOException("MP4 contains no tracks");
            new DefaultMp4Builder().build(movie).writeContainer(output);
        } catch (RuntimeException e) {
            throw new IOException("Cannot read fragmented M4A audio: " + source.getName(), e);
        }
    }
}
