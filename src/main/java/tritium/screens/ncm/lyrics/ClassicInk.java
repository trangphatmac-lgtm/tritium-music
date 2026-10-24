// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import tritium.desktop.background.BackgroundShader;
import tritium.interfaces.SharedConstants;
import tritium.rendering.Framebuffer;
import tritium.rendering.StencilClipManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Batched, separable Gaussian glyph blur. Two reusable atlases; no per-frame textures or font strikes. */
final class ClassicInk implements AutoCloseable, SharedConstants {
    record Glyph(String text, double size, double x, double y, double scale, double angle,
                 int body, int halo, double glow, double blur, double inner, double outer) { }
    private record Tile(Glyph glyph, double inkWidth, int x, int y, int width, int height) { }
    private static final int PAD=64;
    private Framebuffer mask, horizontal;
    private BackgroundShader shader;
    private int modeUniform, texelUniform, sigmaUniform, boundsUniform, colorUniform;
    private static final String VERTEX="""
            #version 120
            varying vec2 uv;
            void main(){uv=gl_MultiTexCoord0.xy;gl_Position=ftransform();}
            """;
    private static final String FRAGMENT="""
            #version 120
            uniform sampler2D ink;
            uniform int mode;
            uniform vec2 texel;
            uniform vec3 sigma;
            uniform vec4 bounds;
            uniform vec4 tint;
            varying vec2 uv;
            vec4 sampleInk(vec2 p){return texture2D(ink,clamp(p,bounds.xy,bounds.zw));}
            void main(){
                vec3 sum=vec3(0.0);float total=0.0;
                for(int i=-24;i<=24;i++){
                    float d=float(i)*0.125;
                    float weight=exp(-0.5*d*d);total+=weight;
                    if(mode==0){
                        sum.r+=sampleInk(uv+vec2(texel.x*sigma.r*d,0.0)).a*weight;
                        sum.g+=sampleInk(uv+vec2(texel.x*sigma.g*d,0.0)).a*weight;
                        sum.b+=sampleInk(uv+vec2(texel.x*sigma.b*d,0.0)).a*weight;
                    }else if(mode==1){
                        sum.g+=sampleInk(uv+vec2(0.0,texel.y*sigma.g*d)).g*weight;
                        sum.b+=sampleInk(uv+vec2(0.0,texel.y*sigma.b*d)).b*weight;
                    }else{
                        sum.r+=sampleInk(uv+vec2(0.0,texel.y*sigma.r*d)).r*weight;
                    }
                }
                sum/=total;
                if(mode==0)gl_FragColor=vec4(sum,1.0);
                else if(mode==1)gl_FragColor=vec4(tint.rgb,1.0-(1.0-sum.g*tint.a)*(1.0-sum.b*tint.a));
                else gl_FragColor=vec4(tint.rgb,sum.r*tint.a);
            }
            """;

    void draw(LyricCanvas c, List<Glyph> glyphs) {
        if(glyphs.isEmpty()) return;
        Framebuffer parent=Framebuffer.currentlyBinding;
        int matrixMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        var tiles=new ArrayList<Tile>();int x=0,y=0,rowHeight=0;
        int max=GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE),width=Math.min(2048,max);
        for(var glyph:glyphs) {
            double inkWidth=c.width(glyph.text(),glyph.size(),true);
            int w=(int)Math.ceil(inkWidth)+PAD*2,h=(int)Math.ceil(glyph.size()*1.3)+PAD*2;
            if(x+w>width){x=0;y+=rowHeight;rowHeight=0;}
            tiles.add(new Tile(glyph,inkWidth,x,y,w,h));x+=w;rowHeight=Math.max(rowHeight,h);
        }
        int height=Math.max(256,Integer.highestOneBit(Math.max(1,y+rowHeight-1))<<1);
        if(height>max) {
            // Extremely long unsynchronised lines are still readable on old GPUs.
            int midpoint=glyphs.size()/2;
            if(midpoint==0) {drawBody(c,glyphs.getFirst());return;}
            draw(c,glyphs.subList(0,midpoint));draw(c,glyphs.subList(midpoint,glyphs.size()));return;
        }
        GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();
        try {
            allocate(width,height);
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();
            GL11.glOrtho(0,mask.framebufferWidth,mask.framebufferHeight,0,-1,1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
            mask.bindFramebuffer(true);mask.framebufferClearNoBinding();StencilClipManager.disableStencilTest();
            api.getGLStateManager().disableDepth();api.getGLStateManager().disableAlpha();
            api.getGLStateManager().enableBlend();
            api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
            api.getGLStateManager().bindTexture(0);
            for(var tile:tiles)c.text(tile.glyph().text(),tile.x()+PAD,tile.y()+PAD,tile.glyph().size(),0xFFFFFFFF,true);
            horizontal.bindFramebuffer(true);horizontal.framebufferClearNoBinding();StencilClipManager.disableStencilTest();
            mask.bindFramebufferTexture();api.getGLStateManager().disableBlend();
            shader.withProgram(()->{
                GL20.glUniform2f(texelUniform,1f/mask.framebufferWidth,1f/mask.framebufferHeight);
                GL20.glUniform1i(modeUniform,0);
                for(var tile:tiles){uniforms(tile);quad(tile,tile.x(),tile.y());}
            });
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();
            parent.bindFramebuffer(true);GL11.glMatrixMode(matrixMode);
            api.getGLStateManager().enableBlend();
        }
        horizontal.bindFramebufferTexture();
        shader.withProgram(()->{
            GL20.glUniform1i(modeUniform,1);
            for(var tile:tiles)if(tile.glyph().glow()>.001)composite(c,tile,true);
            GL20.glUniform1i(modeUniform,2);
            for(var tile:tiles)if(tile.glyph().blur()>.15)composite(c,tile,false);
        });
        for(var glyph:glyphs)if(glyph.blur()<=.15)drawBody(c,glyph);
    }
    private static void drawBody(LyricCanvas c, Glyph g) {
        c.push(g.x(),g.y(),g.scale(),g.angle());
        c.text(g.text(),-c.width(g.text(),g.size(),true)/2,-g.size()*.55,g.size(),g.body(),true);c.pop();
    }
    private void composite(LyricCanvas c, Tile tile, boolean halo) {
        var g=tile.glyph();int color=halo?g.halo():g.body();
        GL20.glUniform4f(colorUniform,(color>>16&255)/255f,(color>>8&255)/255f,(color&255)/255f,
                (float)((color>>>24)/255.0*(halo?g.glow():1)));
        uniforms(tile);c.push(g.x(),g.y(),g.scale(),g.angle());
        quad(tile,-tile.inkWidth()/2-PAD,-g.size()*.55-PAD);c.pop();
    }
    private void uniforms(Tile tile) {
        var g=tile.glyph();
        GL20.glUniform3f(sigmaUniform,(float)Math.max(.001,g.blur()),
                (float)Math.max(.001,g.inner()*.5*g.glow()),(float)Math.max(.001,g.outer()*.5*g.glow()));
        GL20.glUniform4f(boundsUniform,(tile.x()+.5f)/mask.framebufferWidth,
                1-(tile.y()+tile.height()-.5f)/mask.framebufferHeight,
                (tile.x()+tile.width()-.5f)/mask.framebufferWidth,1-(tile.y()+.5f)/mask.framebufferHeight);
    }
    private void quad(Tile tile,double x,double y) {
        double u=(double)tile.x()/mask.framebufferWidth,v=1-(double)tile.y()/mask.framebufferHeight;
        double right=(double)(tile.x()+tile.width())/mask.framebufferWidth,bottom=1-(double)(tile.y()+tile.height())/mask.framebufferHeight;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d(u,v);GL11.glVertex2d(x,y);
        GL11.glTexCoord2d(right,v);GL11.glVertex2d(x+tile.width(),y);
        GL11.glTexCoord2d(right,bottom);GL11.glVertex2d(x+tile.width(),y+tile.height());
        GL11.glTexCoord2d(u,bottom);GL11.glVertex2d(x,y+tile.height());GL11.glEnd();
    }
    private void allocate(int width,int height) {
        if(shader==null)try {
            shader=new BackgroundShader(VERTEX,FRAGMENT);
            modeUniform=shader.uniform("mode");texelUniform=shader.uniform("texel");sigmaUniform=shader.uniform("sigma");
            boundsUniform=shader.uniform("bounds");colorUniform=shader.uniform("tint");
        }catch(IOException e){throw new IllegalStateException("Cannot compile classic lyric glow",e);}
        // Grow until a style switch, rather than reallocating between short and long lines.
        if(mask!=null&&mask.framebufferWidth==width&&mask.framebufferHeight>=height)return;
        releaseBuffers();
        mask=new Framebuffer(width,height,false);horizontal=new Framebuffer(width,height,false);
        mask.setFramebufferColor(0,0,0,0);horizontal.setFramebufferColor(0,0,0,0);
        mask.setFramebufferFilter(GL11.GL_LINEAR);horizontal.setFramebufferFilter(GL11.GL_LINEAR);
    }
    private void releaseBuffers() {
        Framebuffer parent=Framebuffer.currentlyBinding;
        try {if(mask!=null)mask.deleteFramebuffer();if(horizontal!=null)horizontal.deleteFramebuffer();}
        finally {if(parent!=null&&parent!=mask&&parent!=horizontal)parent.bindFramebuffer(true);mask=null;horizontal=null;}
    }
    @Override public void close(){releaseBuffers();if(shader!=null)shader.close();shader=null;}
}
