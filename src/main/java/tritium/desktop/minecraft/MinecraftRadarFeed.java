package tritium.desktop.minecraft;

import tritium.minecraft.protocol.RadarFrame;
import java.util.concurrent.TimeUnit;

/** Latest frame only, owned by one authenticated connection. A stopped renderer cannot leave a stale radar. */
final class MinecraftRadarFeed {
    private record Sample(RadarFrame frame, long receivedAt) { }
    private volatile Sample latest;

    void accept(RadarFrame frame, long now) {
        latest = frame != null && frame.visible && frame.valid() ? new Sample(frame, now) : null;
    }
    RadarFrame current(long now) {
        Sample sample = latest;
        return sample != null && now - sample.receivedAt <= TimeUnit.SECONDS.toNanos(2) ? sample.frame : null;
    }
}
