package tritium.minecraft.runtime.hook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeHooksAccessTest {
    @Test void bridgeQuantizationKeepsYawContinuousAndClampsPitch() {
        Client client = new Client(); client.thePlayer.rotationYaw = 179.9f;
        RuntimeHooksAccess hooks = new RuntimeHooksAccess(client);
        float[] rotation = hooks.bridgeRotation(new float[]{-179.9f, 91});
        assertEquals(180.05f, rotation[0], .0001f);
        assertEquals(90, rotation[1]);
        hooks.bridgeTickStart(true);
        assertEquals(client.thePlayer.rotationYaw, client.thePlayer.prevRotationYaw);
        hooks.bridgeRenderArms(true);
        assertEquals(client.thePlayer.rotationYaw, client.thePlayer.renderArmYaw);
        assertEquals(client.thePlayer.rotationYaw, client.thePlayer.prevRenderArmYaw);
    }

    @Test void nestedRaycastsRestoreBothCurrentAndInterpolatedAnglesEvenWhenViewChanges() {
        Client client = new Client(); Player original = client.view;
        RuntimeHooksAccess hooks = new RuntimeHooksAccess(client);
        hooks.beginMouseOver(new float[]{90, 80});
        assertArrayEquals(new float[]{90, 80, 90, 80}, angles(original));
        hooks.beginMouseOver(new float[]{100, 81});
        hooks.endMouseOver();
        assertArrayEquals(new float[]{90, 80, 90, 80}, angles(original));
        // A nested inactive invocation must not consume the outer swap's state.
        hooks.beginMouseOver(null); hooks.endMouseOver();
        assertEquals(90, original.rotationYaw);
        client.view = new Player(); hooks.clearMouseOver();
        assertArrayEquals(new float[]{12, 30, 11, 29}, angles(original));
        assertArrayEquals(new float[]{12, 30, 11, 29}, angles(client.view));
        hooks.endMouseOver(); // Exception/cleanup fallbacks may safely run again.
    }

    private static float[] angles(Player player) {
        return new float[]{player.rotationYaw, player.rotationPitch, player.prevRotationYaw, player.prevRotationPitch};
    }
    static class Client {
        public Player thePlayer = new Player(), view = thePlayer;
        public Settings gameSettings = new Settings();
        public Object getRenderViewEntity() { return view; }
    }
    static class Settings { public float mouseSensitivity = .5f; }
    static class Player {
        public float rotationYaw = 12, rotationPitch = 30, prevRotationYaw = 11, prevRotationPitch = 29;
        public float renderArmYaw, prevRenderArmYaw;
    }
}
