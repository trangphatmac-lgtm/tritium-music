package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import tritium.desktop.DesktopNativeLoader;
import java.awt.image.BufferedImage;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="tritium.testRendering",matches="true")
class GameGlStateRenderingTest {
    @Test void restoresGameOpenGlStateAfterUploadsAndDrawing() throws Exception {
        DesktopNativeLoader.configureLibraryPath(); Display.setDisplayMode(new DisplayMode(640,480)); Display.create();
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of("minecraft-agent/target/minecraft-hud-agent.jar").toUri().toURL()},getClass().getClassLoader())) {
            Class<?> type=Class.forName("tritium.minecraft.runtime.lyric.GlHud",true,loader); var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object gl=ctor.newInstance();
            GL11.glClearColor(.8f,.6f,.4f,1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            int texture=GL11.glGenTextures(),buffer=GL15.glGenBuffers();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,buffer); GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER,128,GL15.GL_STREAM_DRAW);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glBlendFunc(GL11.GL_SRC_COLOR,GL11.GL_ONE);GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,8);GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,7);
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glTranslated(2,3,4);
            float[] before=matrix(GL11.GL_PROJECTION_MATRIX);
            GL11.glViewport(7,11,620,450);
            invoke(type,gl,"begin",new Class[]{int.class,int.class},320,240);
            assertArrayEquals(new int[]{7,11,620,450},viewport());
            invoke(type,gl,"editorBackdrop",new Class[]{});
            invoke(type,gl,"upload",new Class[]{String.class,BufferedImage.class},"test",new BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB));
            invoke(type,gl,"draw",new Class[]{String.class,double[].class},"test",new double[]{10,10,20,20});
            invoke(type,gl,"end",new Class[]{});
            ByteBuffer backdrop=BufferUtils.createByteBuffer(4);
            GL11.glReadPixels(300,200,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,backdrop);
            assertEquals(122,backdrop.get(0)&255,2,"Editor must darken the world without hiding it");
            assertEquals(92,backdrop.get(1)&255,2); assertEquals(61,backdrop.get(2)&255,2);
            assertArrayEquals(new int[]{7,11,620,450},viewport());
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

    @Test void fractionalAndHiDpiViewportsKeepOneTexturePixelPerScreenPixel() throws Exception {
        DesktopNativeLoader.configureLibraryPath(); Display.setDisplayMode(new DisplayMode(640,480)); Display.create();
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of("minecraft-agent/target/minecraft-hud-agent.jar").toUri().toURL()},getClass().getClassLoader())) {
            Class<?> type=Class.forName("tritium.minecraft.runtime.lyric.GlHud",true,loader); var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object gl=ctor.newInstance();
            int framebuffer=GL30.glGenFramebuffers(),texture=GL11.glGenTextures();
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1400,900,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
                assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE,GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                for(int[] size:new int[][]{{640,360,320,180},{1280,720,320,180},{997,541,399,217},{997,541,499,271},{640,360,320,180}}) {
                    int pw=size[0],ph=size[1],gw=size[2],gh=size[3]; double dx=pw/(double)gw,dy=ph/(double)gh;
                    GL11.glViewport(7,11,pw,ph); GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    invoke(type,gl,"begin",new Class[]{int.class,int.class},gw,gh);
                    assertTrue((Boolean)invoke(type,gl,"resized",new Class[]{}),"Viewport or GUI changes must invalidate cached textures");
                    double[] bounds={10.17,8.19,12.3,7.4};
                    int w=(Integer)invoke(type,gl,"textureWidth",new Class[]{double.class},bounds[2]);
                    int h=(Integer)invoke(type,gl,"textureHeight",new Class[]{double.class},bounds[3]);
                    assertEquals((int)Math.ceil(bounds[2]*dx),w); assertEquals((int)Math.ceil(bounds[3]*dy),h);
                    BufferedImage source=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++)source.setRGB(x,y,(x+y)%2==0?0xffffffff:0xff000000);
                    invoke(type,gl,"upload",new Class[]{String.class,BufferedImage.class},"pixels",source);
                    invoke(type,gl,"draw",new Class[]{String.class,double[].class},"pixels",bounds);
                    invoke(type,gl,"end",new Class[]{});
                    assertArrayEquals(new int[]{7,11,pw,ph},viewport());
                    ByteBuffer pixels=BufferUtils.createByteBuffer(w*h*4);
                    GL11.glReadPixels(7+(int)Math.round(bounds[0]*dx),11+ph-(int)Math.round(bounds[1]*dy)-h,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                        int expected=source.getRGB(x,y)&255,offset=((h-y-1)*w+x)*4;
                        for(int channel=0;channel<3;channel++)assertEquals(expected,pixels.get(offset+channel)&255,1,
                                "Native texels must not blend with their neighbors at " + x + "," + y);
                    }
                    invoke(type,gl,"begin",new Class[]{int.class,int.class},gw,gh);
                    assertFalse((Boolean)invoke(type,gl,"resized",new Class[]{}));
                    invoke(type,gl,"end",new Class[]{});
                }
                assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
            } finally {
                invoke(type,gl,"close",new Class[]{}); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);
                GL30.glDeleteFramebuffers(framebuffer);GL11.glDeleteTextures(texture);
            }
        } finally { Display.destroy(); }
    }
    private static int[] viewport(){IntBuffer b=BufferUtils.createIntBuffer(16);GL11.glGetInteger(GL11.GL_VIEWPORT,b);int[] v=new int[4];b.get(v);return v;}
    private static float[] matrix(int name){FloatBuffer b=BufferUtils.createFloatBuffer(16);GL11.glGetFloat(name,b);float[] v=new float[16];b.get(v);return v;}
    private static Object invoke(Class<?> t,Object o,String name,Class<?>[] args,Object... values)throws Exception{var m=t.getDeclaredMethod(name,args);m.setAccessible(true);return m.invoke(o,values);}
}
