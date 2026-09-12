package tritium.rendering.font;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import tritium.interfaces.SharedConstants;
import tritium.rendering.rendersystem.RenderSystem;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** A font's append-only texture pages. Upload and destruction run on the render thread. */
public class TextureAtlas {
    private static final int ATLAS_SIZE = 2048;
    private static final int PADDING = 2;

    private final int pageSize;
    private final TextureBackend backend;
    private final List<Integer> textureIds = new ArrayList<>();
    private int currentX = PADDING;
    private int currentY = PADDING;
    private int currentRowHeight;
    private boolean destroyed;

    public TextureAtlas() {
        this(ATLAS_SIZE, new OpenGLBackend());
    }

    // Injectable texture storage lets capacity/lifetime regressions run without a GL context.
    TextureAtlas(int pageSize, TextureBackend backend) {
        this.pageSize = pageSize;
        this.backend = backend;
    }

    public AtlasRegion upload(BufferedImage image) {
        // A rasterization queued before a font reset still holds the old atlas.
        if (destroyed) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        if (width + PADDING * 2 > pageSize || height + PADDING * 2 > pageSize) {
            throw new IllegalArgumentException("Glyph exceeds font atlas page size: " + width + "x" + height);
        }

        if (currentX + width + PADDING > pageSize) {
            currentX = PADDING;
            currentY += currentRowHeight + PADDING;
            currentRowHeight = 0;
        }
        if (textureIds.isEmpty() || currentY + height + PADDING > pageSize) {
            textureIds.add(backend.create(pageSize));
            currentX = PADDING;
            currentY = PADDING;
            currentRowHeight = 0;
        }

        int textureId = textureIds.getLast();
        backend.upload(textureId, currentX, currentY, image);
        AtlasRegion region = new AtlasRegion(textureId,
                (float) currentX / pageSize, (float) currentY / pageSize,
                (float) (currentX + width) / pageSize, (float) (currentY + height) / pageSize,
                width, height);
        currentX += width + PADDING;
        currentRowHeight = Math.max(currentRowHeight, height);
        return region;
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        textureIds.forEach(backend::delete);
        textureIds.clear();
    }

    interface TextureBackend {
        int create(int size);
        void upload(int textureId, int x, int y, BufferedImage image);
        void delete(int textureId);
    }

    private static class OpenGLBackend implements TextureBackend, SharedConstants {
        public int create(int size) {
            int textureId = api.getGLStateManager().generateTexture();
            api.getGLStateManager().bindTexture(textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0);
            RenderSystem.linearFilter();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_ALPHA, size, size, 0,
                    GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            api.getGLStateManager().bindTexture(0);
            return textureId;
        }

        public void upload(int textureId, int x, int y, BufferedImage image) {
            int width = image.getWidth(), height = image.getHeight();
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height);
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    buffer.put((byte) (image.getRGB(col, row) >>> 24));
                }
            }
            buffer.flip();
            api.getGLStateManager().bindTexture(textureId);
            int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            try {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                        GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, buffer);
            } finally {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
                api.getGLStateManager().bindTexture(0);
            }
        }

        public void delete(int textureId) {
            api.getGLStateManager().deleteTexture(textureId);
        }
    }

    public static class AtlasRegion {
        public final int textureId;
        public final float u0, v0, u1, v1;
        public final int width, height;

        public AtlasRegion(int textureId, float u0, float v0, float u1, float v1, int width, int height) {
            this.textureId = textureId;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.width = width;
            this.height = height;
        }
    }
}
