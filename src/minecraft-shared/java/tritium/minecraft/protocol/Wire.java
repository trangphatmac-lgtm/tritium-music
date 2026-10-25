package tritium.minecraft.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded, length-prefixed frames. A single reader and a synchronized writer per socket. */
public final class Wire {
    public static final int VERSION = 1, MAX_FRAME = 8 * 1024 * 1024;
    public static final int HELLO = 1, PROGRESS = 2, CONFIG = 3, TRACK = 4, STATE = 5,
            ARTWORK = 6, READY = 7, HEARTBEAT = 8, EDIT = 9, PREVIEW = 10,
            EVENT = 11, UNINJECT = 12, STOPPED = 13, ERROR = 14, TOAST = 15;
    private final DataInputStream input;
    private final DataOutputStream output;

    public Wire(InputStream input, OutputStream output) {
        this.input = new DataInputStream(new BufferedInputStream(input));
        this.output = new DataOutputStream(new BufferedOutputStream(output));
    }

    public synchronized void send(int type, String value) throws IOException {
        send(type, value.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void send(int type, byte[] value) throws IOException {
        if (value.length > MAX_FRAME) throw new IOException("Frame exceeds limit");
        output.writeInt(value.length + 1);
        output.writeByte(type);
        output.write(value);
        output.flush();
    }

    public Frame read() throws IOException {
        int size = input.readInt();
        if (size < 1 || size > MAX_FRAME + 1) throw new IOException("Invalid frame length: " + size);
        int type = input.readUnsignedByte();
        byte[] body = new byte[size - 1];
        input.readFully(body);
        return new Frame(type, body);
    }

    public static final class Frame {
        public final int type;
        public final byte[] body;
        public Frame(int type, byte[] body) { this.type = type; this.body = body; }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    public static final class Hello {
        public int version = VERSION;
        public String token, client, javaVersion;
        public long pid;
    }

    private Wire() { input = null; output = null; }
}
