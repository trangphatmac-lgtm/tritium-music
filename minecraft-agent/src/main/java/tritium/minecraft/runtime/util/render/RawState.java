package tritium.minecraft.runtime.util.render;
import org.lwjgl.opengl.*;
/** Raw calls paired with RenderState: do not corrupt the game's state cache. */
public final class RawState {
 public static void tryBlendFuncSeparate(int a,int b,int c,int d){GL14.glBlendFuncSeparate(a,b,c,d);}
 public static void color(float r,float g,float b,float a){GL11.glColor4f(r,g,b,a);}public static void color(float r,float g,float b){GL11.glColor3f(r,g,b);}
 public static void enableBlend(){GL11.glEnable(GL11.GL_BLEND);}public static void disableBlend(){GL11.glDisable(GL11.GL_BLEND);}public static void blendFunc(int a,int b){GL11.glBlendFunc(a,b);}
 public static void enableAlpha(){GL11.glEnable(GL11.GL_ALPHA_TEST);}public static void disableAlpha(){GL11.glDisable(GL11.GL_ALPHA_TEST);}public static void alphaFunc(int a,float b){GL11.glAlphaFunc(a,b);}
 public static void bindTexture(int id){GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);}public static void setActiveTexture(int unit){GL13.glActiveTexture(unit);}public static void clearColor(float a,float b,float c,float d){GL11.glClearColor(a,b,c,d);}
}
