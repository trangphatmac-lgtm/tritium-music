package tritium.rendering;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import tritium.interfaces.SharedConstants;

public class StencilClipManager implements SharedConstants {

    private static final BooleanState stencilState = new BooleanState(GL11.GL_STENCIL_TEST);

    public static class StencilState {
        int stencilValue;
        boolean colorMask;
        boolean depthMask;

        StencilState(int value, boolean color, boolean depth) {
            this.stencilValue = value;
            this.colorMask = color;
            this.depthMask = depth;
        }
    }

    public static boolean stencilClipping() {
        return !Framebuffer.currentlyBinding.stencilStack.isEmpty();
    }

    public static void disableStencilTest() {
        stencilState.setDisabled();
    }

    public static void enableStencilTest() {
        stencilState.setEnabled();
    }

    public static boolean isStencilEnabled() {
        return stencilState.currentState;
    }


    public static void initialize() {
        GL11.glClearStencil(0);
        api.getGLStateManager().enableDepth();
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        enableStencilTest();
    }

    static boolean depthMask = false;

    public static void beginClip() {
        if (Framebuffer.currentlyBinding.currentStencilValue == 0) {
            initialize();
        }

        boolean colorMask = GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        Framebuffer.currentlyBinding.stencilStack.push(new StencilState(Framebuffer.currentlyBinding.currentStencilValue, colorMask, depthMask));

        api.getGLStateManager().colorMask(false, false, false, false);
        api.getGLStateManager().depthMask(false);

        if (Framebuffer.currentlyBinding.currentStencilValue == 0) {
            GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        } else {
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
        }

        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INCR);
    }

    public static void updateClip() {
        Framebuffer.currentlyBinding.currentStencilValue++;

        api.getGLStateManager().colorMask(true, true, true, true);
        api.getGLStateManager().depthMask(depthMask);

        GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    public static void beginClip(Runnable drawClipShape) {
        beginClip();

        drawClipShape.run();

        updateClip();
    }

    public static void endClip() {
        if (Framebuffer.currentlyBinding.stencilStack.isEmpty()) {
            System.err.println("Stencil stack underflow");
            return;
        }

        StencilState state = Framebuffer.currentlyBinding.stencilStack.pop();
        // Return this child's pixels to the parent before reusing the depth for
        // a sibling clip. Merely restoring the test leaves the old shape active.
        if (state.stencilValue > 0) restoreParentMask();
        Framebuffer.currentlyBinding.currentStencilValue = state.stencilValue;

        api.getGLStateManager().colorMask(state.colorMask, state.colorMask, state.colorMask, state.colorMask);
        api.getGLStateManager().depthMask(state.depthMask);

        if (Framebuffer.currentlyBinding.currentStencilValue > 0) {
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        } else {
            disable();
        }
    }

    private static void restoreParentMask() {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        try {
            // Cover the viewport independently of the current UI transform. Only
            // stencil pixels belonging to the ending clip are decremented.
            GL20.glUseProgram(0);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            GL11.glStencilMask(0xFF);
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_DECR);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1, -1); GL11.glVertex2f(1, -1);
            GL11.glVertex2f(1, 1); GL11.glVertex2f(-1, 1);
            GL11.glEnd();
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
            GL20.glUseProgram(program);
            GL11.glPopAttrib();
        }
    }

    public static void disable() {
        disableStencilTest();
        Framebuffer.currentlyBinding.currentStencilValue = 0;
    }

    public static void clear() {
        Framebuffer.currentlyBinding.currentStencilValue = 0;
        Framebuffer.currentlyBinding.stencilStack.clear();
    }
}
