package tritium.desktop;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

public class DesktopGLStateManager {
    public int activeTextureUnit;
    public final TextureState[] textureState = new TextureState[32];

    public DesktopGLStateManager() {
        for (int i = 0; i < textureState.length; i++) {
            textureState[i] = new TextureState();
        }
    }

    public int generateTexture() {
        return GL11.glGenTextures();
    }

    public void deleteTexture(int texture) {
        GL11.glDeleteTextures(texture);
    }

    public void bindTexture(int texture) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        textureState[Math.max(0, Math.min(activeTextureUnit, textureState.length - 1))].textureName = texture;
    }

    public boolean isTexture2DEnabled() {
        return GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
    }

    public void enableTexture2D() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    public void disableTexture2D() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    public void enableBlend() {
        GL11.glEnable(GL11.GL_BLEND);
    }

    public void disableBlend() {
        GL11.glDisable(GL11.GL_BLEND);
    }

    public void blendFunc(int srcFactor, int dstFactor) {
        GL11.glBlendFunc(srcFactor, dstFactor);
    }

    public void tryBlendFuncSeparate(int srcFactor, int dstFactor, int srcFactorAlpha, int dstFactorAlpha) {
        GL14.glBlendFuncSeparate(srcFactor, dstFactor, srcFactorAlpha, dstFactorAlpha);
    }

    public void enableAlpha() {
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    public void disableAlpha() {
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }

    public void alphaFunc(int func, float ref) {
        GL11.glAlphaFunc(func, ref);
    }

    public void enableDepth() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    public void disableDepth() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    public void depthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    public void disableLighting() {
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    public void enableColorMaterial() {
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
    }

    public void disableCull() {
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    public void color(float red, float green, float blue, float alpha) {
        GL11.glColor4f(red, green, blue, alpha);
    }

    public void resetColor() {
        color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GL11.glColorMask(red, green, blue, alpha);
    }

    public void clearColor(float red, float green, float blue, float alpha) {
        GL11.glClearColor(red, green, blue, alpha);
    }

    public void clearDepth(double depth) {
        GL11.glClearDepth(depth);
    }

    public void clear(int mask) {
        GL11.glClear(mask);
    }

    public void viewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
    }

    public void matrixMode(int mode) {
        GL11.glMatrixMode(mode);
    }

    public void loadIdentity() {
        GL11.glLoadIdentity();
    }

    public void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        GL11.glOrtho(left, right, bottom, top, zNear, zFar);
    }

    public void pushMatrix() {
        GL11.glPushMatrix();
    }

    public void popMatrix() {
        GL11.glPopMatrix();
    }

    public void pushAttrib() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
    }

    public void popAttrib() {
        GL11.glPopAttrib();
    }

    public void translate(double x, double y, double z) {
        GL11.glTranslated(x, y, z);
    }

    public void translate(float x, float y, float z) {
        GL11.glTranslatef(x, y, z);
    }

    public void scale(double x, double y, double z) {
        GL11.glScaled(x, y, z);
    }

    public void scale(float x, float y, float z) {
        GL11.glScalef(x, y, z);
    }

    public void rotate(float angle, float x, float y, float z) {
        GL11.glRotatef(angle, x, y, z);
    }

    public void shadeModel(int mode) {
        GL11.glShadeModel(mode);
    }

    public void callList(int list) {
        GL11.glCallList(list);
    }

    public void setActiveTexture(int texture) {
        GL13.glActiveTexture(texture);
        activeTextureUnit = Math.max(0, texture - GL13.GL_TEXTURE0);
    }

    public static class TextureState {
        public int textureName = -1;
    }
}
