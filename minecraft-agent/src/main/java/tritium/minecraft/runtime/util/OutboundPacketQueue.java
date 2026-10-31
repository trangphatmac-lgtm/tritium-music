package tritium.minecraft.runtime.util;

import java.util.*;

/** Session-owned ordered PacketQueue. Every entry keeps the manager that accepted it. */
public final class OutboundPacketQueue {
    public static final class Entry {
        public final Object manager, packet;
        public final long due;

        public Entry(Object manager, Object packet, long due) {
            this.manager = manager;
            this.packet = packet;
            this.due = due;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<Entry>();

    public void add(Object manager, Object packet, long due) {
        entries.addLast(new Entry(manager, packet, due));
    }

    public List<Entry> drain(long now, boolean all) {
        List<Entry> result = new ArrayList<Entry>();
        while (!entries.isEmpty() && (all || entries.peekFirst().due <= now))
            result.add(entries.removeFirst());
        return result;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
