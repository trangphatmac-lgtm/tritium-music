package tritium.minecraft.runtime.util.render;
import org.lwjgl.opengl.*;
import tritium.minecraft.runtime.lyric.NativeFontCache;
/** Restores GL state and Minecraft's Java cache, including exceptional shader paths. */
public final class RenderState implements AutoCloseable {
 private final int matrix,active,program,drawFramebuffer,readFramebuffer,framebufferApi;
 private final NativeFontCache.Snapshot cache;
 public RenderState(NativeFontCache nativeCache) {
  try { cache=nativeCache.save(); }catch(IllegalAccessException e){throw new IllegalStateException(e);}
  ContextCapabilities caps=GLContext.getCapabilities();
  matrix=GL11.glGetInteger(GL11.GL_MATRIX_MODE);active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
  program=caps.OpenGL20?GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM):0;
  framebufferApi=caps.OpenGL30?3:caps.GL_ARB_framebuffer_object?2:caps.GL_EXT_framebuffer_object?1:0;
  drawFramebuffer=framebufferApi==0?0:GL11.glGetInteger(0x8CA6);
  readFramebuffer=framebufferApi>=2?GL11.glGetInteger(0x8CAA):drawFramebuffer;
  GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);GL11.glPushClientAttrib(-1);
  GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();
 }
 public void close(){
  GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(matrix);
  GL11.glPopClientAttrib();GL11.glPopAttrib();if(GLContext.getCapabilities().OpenGL20)GL20.glUseProgram(program);
  if(framebufferApi==3){GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFramebuffer);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFramebuffer);}
  else if(framebufferApi==2){ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_DRAW_FRAMEBUFFER,drawFramebuffer);ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_READ_FRAMEBUFFER,readFramebuffer);}
  else if(framebufferApi==1)EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,drawFramebuffer);
  GL13.glActiveTexture(active);
  try {cache.close();}catch(IllegalAccessException e){throw new IllegalStateException(e);}
 }
}
