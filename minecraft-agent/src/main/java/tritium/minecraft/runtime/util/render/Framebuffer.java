package tritium.minecraft.runtime.util.render;
import tritium.minecraft.runtime.hook.RenderAccess;
public final class Framebuffer {
 private final RenderAccess g;public final Object handle;public final int framebufferWidth,framebufferHeight,framebufferTexture;
 public Framebuffer(RenderAccess g,int w,int h,boolean depth){this(g,g.make("client.shader.Framebuffer",Math.max(1,w),Math.max(1,h),depth));}
 public Framebuffer(RenderAccess g,Object handle){this.g=g;this.handle=handle;framebufferWidth=g.integerField(handle,"framebufferWidth");framebufferHeight=g.integerField(handle,"framebufferHeight");framebufferTexture=g.integerField(handle,"framebufferTexture");}
 public void framebufferClear(){g.invoke(handle,"framebufferClear");}public void bindFramebuffer(boolean viewport){g.invoke(handle,"bindFramebuffer",viewport);}public void unbindFramebuffer(){g.invoke(handle,"unbindFramebuffer");}public void deleteFramebuffer(){g.invoke(handle,"deleteFramebuffer");}public void setFramebufferFilter(int n){g.invoke(handle,"setFramebufferFilter",n);}
}
