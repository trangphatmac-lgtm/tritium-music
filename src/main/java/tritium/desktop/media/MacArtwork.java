package tritium.desktop.media;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import static tritium.desktop.media.MacObjectiveC.*;

/** Apple Blocks ABI: the copied request block owns its NSImage, independently of Java GC. */
final class MacArtwork {
    @Structure.FieldOrder({"width", "height"})
    public static class Size extends Structure implements Structure.ByValue {
        public double width, height;
        public Size() { }
        Size(double width, double height) { this.width = width; this.height = height; }
    }

    public interface ImageRequest extends Callback { Pointer invoke(Pointer block, Size size); }
    public interface Copy extends Callback { void invoke(Pointer destination, Pointer source); }
    public interface Dispose extends Callback { void invoke(Pointer block); }

    // Function pointers and the descriptor must outlive every native copy of the block.
    private static final ImageRequest REQUEST = (block, size) -> block.getPointer(32);
    private static final Copy COPY = (destination, source) -> object(source.getPointer(32), "retain");
    private static final Dispose DISPOSE = block -> release(block.getPointer(32));
    private static final Memory SIGNATURE = utf8("@24@?0{CGSize=dd}8");
    private static final Memory DESCRIPTOR = descriptor();

    private static Memory descriptor() {
        Memory result = new Memory(40);
        result.clear();
        result.setLong(8, 40); // sizeof the block literal, including its captured NSImage
        result.setPointer(16, CallbackReference.getFunctionPointer(COPY));
        result.setPointer(24, CallbackReference.getFunctionPointer(DISPOSE));
        result.setPointer(32, SIGNATURE);
        return result;
    }

    /** Returns an owned MPMediaItemArtwork; call only on the Cocoa main thread. */
    static Pointer create(byte[] bytes) {
        try (NativeBuffer data = new NativeBuffer(bytes.length); NativeBuffer block = new NativeBuffer(40)) {
            data.write(0, bytes, 0, bytes.length);
            Pointer nsData = object(type("NSData"), "dataWithBytes:length:", data, (long) bytes.length);
            Pointer image = object(object(type("NSImage"), "alloc"), "initWithData:", nsData);
            if (image == null) return null;
            try {
                block.clear();
                block.setPointer(0, SYSTEM.getGlobalVariableAddress("_NSConcreteStackBlock"));
                block.setInt(8, (1 << 25) | (1 << 30)); // BLOCK_HAS_COPY_DISPOSE | BLOCK_HAS_SIGNATURE
                block.setPointer(16, CallbackReference.getFunctionPointer(REQUEST));
                block.setPointer(24, DESCRIPTOR);
                block.setPointer(32, image);
                return object(object(type("MPMediaItemArtwork"), "alloc"),
                        "initWithBoundsSize:requestHandler:", new Size(512, 512), block);
            } finally { release(image); }
        }
    }
}
