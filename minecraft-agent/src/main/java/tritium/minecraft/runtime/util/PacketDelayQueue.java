package tritium.minecraft.runtime.util;

import java.util.*;
import java.util.function.BiConsumer;

/** All access is serialized; replay reentrancy bypasses only this queue, preserving other agents. */
public final class PacketDelayQueue {
    private final BiConsumer<Object,Object> sender;
    private final List<Object[]> held=new ArrayList<Object[]>();
    private boolean active;
    private final ThreadLocal<Boolean> replay=new ThreadLocal<Boolean>();
    public PacketDelayQueue(BiConsumer<Object,Object> sender) { this.sender=sender; }
    public synchronized boolean active() { return active; }
    public boolean replaying() { return Boolean.TRUE.equals(replay.get()); }
    public synchronized void open() { active=true; }
    public synchronized boolean hold(Object manager,Object packet) {
        if (!active || replaying()) return false;
        if (held.size()>=4096) { flush(); return false; }
        held.add(new Object[]{manager,packet}); return true;
    }
    public synchronized void flush() {
        active=false;
        List<Object[]> pending=new ArrayList<Object[]>(held); held.clear();
        replay.set(true);
        try { for (Object[] item:pending) sender.accept(item[0],item[1]); }
        finally { replay.remove(); }
    }
    public synchronized void discard() { active=false; held.clear(); }
}
