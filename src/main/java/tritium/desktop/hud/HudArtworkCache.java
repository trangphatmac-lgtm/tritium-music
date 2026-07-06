package tritium.desktop.hud;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HudArtworkCache {
    private static final Map<Key, BufferedImage> IMAGES = new ConcurrentHashMap<>();

    private HudArtworkCache() {
    }

    public static void put(long musicId, ArtworkType type, BufferedImage image) {
        if (image == null) {
            return;
        }

        IMAGES.put(new Key(musicId, type), copy(image));
    }

    public static BufferedImage get(long musicId, ArtworkType type) {
        return IMAGES.get(new Key(musicId, type));
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    public enum ArtworkType {
        COVER,
        SMALL,
        BLURRED
    }

    private record Key(long musicId, ArtworkType type) {
    }
}
