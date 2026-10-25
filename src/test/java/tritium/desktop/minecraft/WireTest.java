package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.Wire;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class WireTest {
    @Test void preservesUnicodeAndBinaryAcrossFragmentedReads() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Wire out = new Wire(new ByteArrayInputStream(new byte[0]), bytes);
        out.send(Wire.TRACK, "歌词・ローマ字・🙂"); out.send(Wire.ARTWORK, new byte[]{0, -1, 43});
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(1, len)); }
        };
        Wire in = new Wire(fragmented, new ByteArrayOutputStream());
        assertEquals("歌词・ローマ字・🙂", in.read().text());
        assertArrayEquals(new byte[]{0,-1,43}, in.read().body);
        assertThrows(EOFException.class, in::read);
    }
    @Test void rejectsUnboundedAndTruncatedMessages() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); new DataOutputStream(bytes).writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> new Wire(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream()).read());
        assertThrows(IOException.class, () -> new Wire(new ByteArrayInputStream(new byte[]{0,0,0,8,1}), new ByteArrayOutputStream()).read());
    }
    @Test void discoversClientsWithoutPickingLaunchersOrTritium() {
        assertEquals("Minecraft / Forge", MinecraftConnectionManager.clientName("net.minecraft.launchwrapper.Launch --version 1.8.9"));
        assertEquals("Lunar 1.8.9", MinecraftConnectionManager.clientName("com.moonsworth.lunar.genesis.Genesis"));
        assertNull(MinecraftConnectionManager.clientName("tritium.desktop.TritiumMusicDesktopApp"));
        assertNull(MinecraftConnectionManager.clientName("org.jetbrains.idea.maven.server.RemoteMavenServer36"));
    }
    @Test void respectsLastJvmFlagAndDoesNotParseUnrelatedArguments() {
        assertFalse(MinecraftConnectionManager.flagEnabled(new String[]{"-XX:+DisableAttachMechanism", "-XX:-DisableAttachMechanism"}, "DisableAttachMechanism", false));
        assertTrue(MinecraftConnectionManager.flagEnabled(new String[]{"-XX:+DisableAttachMechanism"}, "DisableAttachMechanism", false));
        assertTrue(MinecraftConnectionManager.flagEnabled(new String[]{"--version", "1.8.9"}, "EnableDynamicAgentLoading", true));
    }
}
