// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import org.lwjgl.opengl.*;
import tritium.desktop.background.BackgroundShader;
import tritium.rendering.rendersystem.RenderSystem;
import java.io.IOException;

/** Samples the already drawn composition so a glyph crossing light and dark panels changes ink. */
final class ContrastInk implements AutoCloseable {
    private BackgroundShader shader;
    private int texture;
    private int width,height;
    void draw(Runnable text){
        if(shader==null)try{
            shader=new BackgroundShader("#version 120\nvarying vec2 uv;varying vec4 color;void main(){uv=gl_MultiTexCoord0.xy;color=gl_Color;gl_Position=ftransform();}",
                    "#version 120\nuniform sampler2D glyph;uniform sampler2D scene;uniform vec2 resolution;varying vec2 uv;varying vec4 color;void main(){vec4 mask=texture2D(glyph,uv);vec3 bg=texture2D(scene,gl_FragCoord.xy/resolution).rgb;float l=dot(bg,vec3(.2126,.7152,.0722));vec3 ink=mix(vec3(.96,.95,.94),vec3(.055,.045,.07),smoothstep(.38,.61,l));gl_FragColor=vec4(ink,mask.a*color.a);}");
        }catch(IOException e){throw new IllegalStateException("Cannot compile native lyric contrast",e);}
        int oldUnit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);int oldTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if(texture==0){texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        }else GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        int w=RenderSystem.getFramebufferWidth(),h=RenderSystem.getFramebufferHeight();
        if(w!=width||h!=height){GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGB,0,0,w,h,0);width=w;height=h;}
        else GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,w,h);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        try{shader.withProgram(()->{GL20.glUniform1i(shader.uniform("glyph"),0);GL20.glUniform1i(shader.uniform("scene"),1);GL20.glUniform2f(shader.uniform("resolution"),w,h);text.run();});}
        finally{GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTexture);GL13.glActiveTexture(oldUnit);}
    }
    @Override public void close(){if(shader!=null)shader.close();shader=null;if(texture!=0)GL11.glDeleteTextures(texture);texture=0;width=height=0;}
}
