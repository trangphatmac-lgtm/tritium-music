package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import tritium.desktop.DesktopNativeLoader;
import java.awt.image.BufferedImage;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="tritium.testRendering",matches="true")
class GameGlStateRenderingTest {
    @Test void restoresGameOpenGlStateAfterUploadsAndDrawing() throws Exception {
        DesktopNativeLoader.configureLibraryPath(); Display.setDisplayMode(new DisplayMode(640,480)); Display.create();
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of("minecraft-agent/target/minecraft-hud-agent.jar").toUri().toURL()},getClass().getClassLoader())) {
            Class<?> type=Class.forName("tritium.minecraft.runtime.GlHud",true,loader); var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object gl=ctor.newInstance();
            int texture=GL11.glGenTextures(),buffer=GL15.glGenBuffers();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,buffer); GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER,128,GL15.GL_STREAM_DRAW);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glBlendFunc(GL11.GL_SRC_COLOR,GL11.GL_ONE);GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,8);GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,7);
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glTranslated(2,3,4);
            float[] before=matrix(GL11.GL_PROJECTION_MATRIX);
            invoke(type,gl,"begin",new Class[]{int.class,int.class,int.class,int.class},640,480,320,240);
            invoke(type,gl,"upload",new Class[]{String.class,BufferedImage.class},"test",new BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB));
            invoke(type,gl,"draw",new Class[]{String.class,double[].class},"test",new double[]{10,10,20,20});
            invoke(type,gl,"end",new Class[]{});
            assertEquals(GL13.GL_TEXTURE1,GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
            assertEquals(texture,GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)); assertTrue(GL11.glIsEnabled(GL11.GL_TEXTURE_2D));
            assertEquals(buffer,GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING));
            assertEquals(8,GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT));assertEquals(7,GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH));
            assertEquals(GL11.GL_PROJECTION,GL11.glGetInteger(GL11.GL_MATRIX_MODE));assertArrayEquals(before,matrix(GL11.GL_PROJECTION_MATRIX));
            assertTrue(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));assertTrue(GL11.glIsEnabled(GL11.GL_STENCIL_TEST));assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
            assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT,GL11.glGetInteger(GL14.GL_BLEND_EQUATION));
            assertEquals(GL11.GL_SRC_COLOR,GL11.glGetInteger(GL11.GL_BLEND_SRC));
            invoke(type,gl,"close",new Class[]{});GL11.glDeleteTextures(texture);GL15.glDeleteBuffers(buffer);
            assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
        }finally{Display.destroy();}
    }
    private static float[] matrix(int name){FloatBuffer b=BufferUtils.createFloatBuffer(16);GL11.glGetFloat(name,b);float[] v=new float[16];b.get(v);return v;}
    private static void invoke(Class<?> t,Object o,String name,Class<?>[] args,Object... values)throws Exception{var m=t.getDeclaredMethod(name,args);m.setAccessible(true);m.invoke(o,values);}
}
