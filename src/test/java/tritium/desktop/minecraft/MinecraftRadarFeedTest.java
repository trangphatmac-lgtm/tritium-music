package tritium.desktop.minecraft;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.RadarFrame;
import tritium.minecraft.protocol.Wire;
import java.io.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftRadarFeedTest {
    @Test void completeWireFramesReplacePreviousStateAndExpireOrClearWithoutTrails() throws Exception {
        Gson json = new Gson(); RadarFrame source = frame(); source.tracers = true; source.points = new double[]{43.5, 90.25};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new Wire(new ByteArrayInputStream(new byte[0]), bytes).send(Wire.RADAR, json.toJson(source));
        Wire.Frame wire = new Wire(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream()).read();
        assertEquals(Wire.RADAR, wire.type);
        MinecraftRadarFeed feed = new MinecraftRadarFeed(); long now = 100;
        RadarFrame received = json.fromJson(wire.text(), RadarFrame.class); feed.accept(received, now);
        assertArrayEquals(source.points, feed.current(now).points); assertTrue(feed.current(now).tracers);
        assertEquals(source.background, feed.current(now).background);
        RadarFrame empty = frame(); feed.accept(empty, now + 1); assertEquals(0, feed.current(now + 1).points.length);
        assertNull(feed.current(now + TimeUnit.SECONDS.toNanos(3)));
        feed.accept(source, now); feed.accept(new RadarFrame(), now); assertNull(feed.current(now));
        assertNull(new MinecraftRadarFeed().current(now)); // A new connection cannot reuse the old session's frame.
    }
    @Test void resizePreservesShapesAndTheGameViewportPosition() {
        RadarFrame frame = frame();
        RadarFrame.Placement same = frame.fit(1280, 720); assertEquals(2, same.scale); assertEquals(0, same.x); assertEquals(0, same.y);
        RadarFrame.Placement wide = frame.fit(1600, 720); assertEquals(2, wide.scale); assertEquals(160, wide.x); assertEquals(0, wide.y);
        assertEquals(170, wide.x + frame.x * wide.scale);
        frame.viewportWidth = 320; frame.viewportHeight = 180;
        assertEquals(4, frame.fit(1280, 720).scale); // A GUI scale change is reflected in the very next frame.
    }
    @Test void malformedFramesNeverReachOpenGl() {
        MinecraftRadarFeed feed = new MinecraftRadarFeed(); RadarFrame frame = frame();
        frame.points = new double[]{Double.NaN, 20}; feed.accept(frame, 0); assertNull(feed.current(0));
        frame.points = new double[]{1}; assertFalse(frame.valid());
        frame.points = new double[0]; frame.viewportWidth = 0; assertFalse(frame.valid());
        frame.viewportWidth = Double.POSITIVE_INFINITY; assertFalse(frame.valid());
        frame.viewportWidth = 640; frame.points = new double[RadarFrame.MAX_POINTS * 2 + 2]; assertFalse(frame.valid());
    }
    static RadarFrame frame() { RadarFrame frame = new RadarFrame(); frame.visible = true; frame.viewportWidth = 640; frame.viewportHeight = 360; return frame; }
}
