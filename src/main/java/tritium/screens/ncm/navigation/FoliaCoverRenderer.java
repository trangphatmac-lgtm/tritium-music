// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Folia Grid3DSlider, wall.css and bravaisTileKinds.css; see THIRD_PARTY_NOTICES.md.
package tritium.screens.ncm.navigation;

import org.lwjgl.opengl.GL11;
import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.rendering.Rect;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.rendersystem.RenderSystem;
import java.awt.Color;

/** Artwork, paper, captions and hover lighting; no page chrome is created here. */
final class FoliaCoverRenderer implements SharedConstants, SharedRenderingConstants {
    private final CoverArtwork artwork = new CoverArtwork();
    void draw(NavigationContent.Entry entry,FoliaGeometry.Box b,float alpha,double hover,boolean keyboard,boolean wall,boolean library,boolean expanded,double wallGap) {
        if(alpha<=.001f) return;
        var prefs=DesktopAppState.preferences().navigation();
        boolean image=prefs.cardStyle().getValue().equals("image");
        boolean bleed=wall||image||(!library&&prefs.fullBleed().getValue());
        boolean seeThrough=wall&&prefs.wallLook().getValue().equals("clear");
        double reveal=expanded||keyboard?1:hover;
        boolean clear=seeThrough&&reveal<.001;
        float artAlpha=alpha*(float)(seeThrough?reveal:1);
        double radius=wall?0:image?8:6, pad=bleed?0:Math.max(7,b.width()*.055);
        if(wall&&entry.collection()&&prefs.stackEdges().getValue()) {
            double pixelScale=RenderSystem.getFramebufferWidth()/RenderSystem.getWidth();
            int ink=AppTheme.color(0xFF000000,0xFFFFFFFF);
            int edge=reAlpha(ink,alpha*(float)(.32*(1-hover)));
            // The lifted face grows over its paper stack; the stack itself stays in
            // its original gutter instead of chasing the face into the next cover.
            double lift=wallGap*hover;
            var footprint=new FoliaGeometry.Box(b.index(),b.x()+lift,b.y()+lift,b.width()-lift*2,b.height()-lift*2);
            for(var line:FoliaWallSeams.paperEdges(footprint,wallGap,pixelScale,hover))
                Rect.draw(line.x(),line.y(),line.width(),line.height(),edge);
        } else if(!wall) {
            for(int layer=5;layer>0;layer--)
                roundedRect(b.x()-layer,b.y()+layer*2,b.width()+layer*2,b.height()+layer, radius+layer,
                        reAlpha(0xFF000000,alpha*(float)(.018+hover*.012)));
        }
        if(!clear) roundedRect(b.x(),b.y(),b.width(),b.height(),radius,
                reAlpha(bleed?AppTheme.surface():AppTheme.color(0xFF343436,0xFFF3F0E9),artAlpha));
        double artW=b.width()-pad*2, artH=bleed?b.height():Math.min(artW,b.height()-pad*2-37);
        if(!clear) art(entry,new FoliaGeometry.Box(b.index(),b.x()+pad,b.y()+pad,artW,artH),radius==0?0:Math.max(3,radius-pad*.3),artAlpha);
        if(!wall) roundedOutline(b.x(),b.y(),b.width(),b.height(),radius,.6,
                new Color(reAlpha(AppTheme.foreground(),alpha*(float)(.12+hover*.2)),true));
        if(hover>.001&&!clear) gradient(b.x(),b.y(),b.width(),b.height(),radius,
                color(0xFFFFFF,alpha*hover*.10),color(0xFFFFFF,0));
        if(!image||wall) {
            if(bleed) {
                // The continuous poster scrim is deliberately not a caption-sized black box.
                int shade=wall?(AppTheme.color(0xFF000000,0xFFFFFFFF)&0xFFFFFF):0;
                gradient(b.x(),b.y()+b.height()*.34,b.width(),b.height()*.22,0,
                        color(shade,alpha*.03),color(shade,alpha*.24));
                gradient(b.x(),b.y()+b.height()*.56,b.width(),b.height()*.44,0,
                        color(shade,alpha*.24),color(shade,alpha*.92));
            }
            if(wall) {
                int posterInk=AppTheme.foreground();
                double inset=Math.max(6,Math.min(9,b.width()*.06));
                CFontRenderer title=expanded?FontManager.pf40bold:b.width()>180?FontManager.pf34bold:FontManager.pf28bold;
                drawTitle(title,entry.title(),b.x()+inset,b.y()+b.height()-inset-11,b.width()-inset*2,
                        Math.max(1,Math.min(3,(int)((b.height()-30)/title.getHeight()))),reAlpha(posterInk,alpha));
                FontManager.pf12bold.drawString(FontManager.pf12bold.trim(entry.subtitle(),b.width()-inset*2),b.x()+inset,b.y()+b.height()-inset-7,reAlpha(posterInk,alpha*.78f));
                String badge=entry.collection()?entry.badge():String.format("%02d",b.index()+1);
                if(!badge.isBlank()&&b.height()>50) {
                    var font=FontManager.pf12bold;
                    badge=font.trim(badge,Math.max(1,b.width()-inset*2-8));
                    Rect.draw(b.x()+inset,b.y()+inset,font.getStringWidthD(badge)+8,font.getHeight()+5,reAlpha(AppTheme.color(0xFF171614,0xFFE8E5E0),alpha*.62f));
                    font.drawString(badge,b.x()+inset+4,b.y()+inset+2,reAlpha(posterInk,alpha*.88f));
                }
                if(!clear&&!expanded) roundedRect(b.x(),b.y(),b.width(),b.height(),0,
                        reAlpha(AppTheme.color(0xFF211E19,0xFFE9E6DF),artAlpha*(float)(.46*(1-hover))));
            } else {
                var title=library?FontManager.pf20bold:FontManager.pf18bold;
                double ty=bleed?b.y()+b.height()-32:b.y()+pad+artH+7;
                int ink=bleed?0xFFFFFFFF:AppTheme.foreground();
                title.drawString(title.trim(entry.title(),b.width()-Math.max(pad,8)*2),b.x()+Math.max(pad,8),ty,reAlpha(ink,alpha));
                FontManager.pf14.drawString(FontManager.pf14.trim(entry.subtitle(),b.width()-Math.max(pad,8)*2),b.x()+Math.max(pad,8),ty+title.getHeight()+4,reAlpha(ink,alpha*.55f));
            }
        }
        if(keyboard) roundedOutline(b.x()-1,b.y()-1,b.width()+2,b.height()+2,radius,1.5,new Color(reAlpha(AppTheme.foreground(),alpha),true));
    }
    private void drawTitle(CFontRenderer font,String text,double x,double bottom,double width,int maxLines,int color) {
        String[] lines=font.fitWidth(text,width);
        int count=Math.min(maxLines,lines.length); double lineHeight=font.getHeight()*.98;
        for(int i=0;i<count;i++) {
            String line=lines[i];
            if(i==count-1&&lines.length>count) line=font.trim(line+"…",width);
            font.drawString(line,x,bottom-(count-i)*lineHeight,color);
        }
    }
    private void art(NavigationContent.Entry entry,FoliaGeometry.Box b,double radius,float alpha) {
        var texture=artwork.texture(entry);
        if(texture!=null) {
            texture.linearFilter(); api.getGLStateManager().bindTexture(texture.getGlTextureId());
            double u=Math.min(1,b.width()/b.height()),v=Math.min(1,b.height()/b.width());
            roundedRectTextured(b.x(),b.y(),b.width(),b.height(),(1-u)/2,(1-v)/2,u,v,radius,0,alpha);
        } else {
            // Broken and absent covers remain a designed, readable poster rather than a black hole.
            float hue=Math.floorMod(entry.key().hashCode(),360)/360f;
            Color light=Color.getHSBColor(hue,.27f,.68f),dark=Color.getHSBColor((hue+.14f)%1,.38f,.30f);
            gradient(b.x(),b.y(),b.width(),b.height(),radius,
                    new Color(reAlpha(light.getRGB(),alpha),true),new Color(reAlpha(dark.getRGB(),alpha),true));
            var font=FontManager.music40;
            double scale=Math.min(b.width(),b.height())*.64/Math.max(font.getHeight(),font.getStringWidthD("D"));
            font.drawString("D",b.centerX()-font.getStringWidthD("D")*scale/2,b.centerY()-font.getHeight()*scale/2,scale,reAlpha(0xFFFFFFFF,alpha*.42f));
        }
    }
    // Per-vertex alpha avoids the legacy RQG shader's opaque gradients. A rounded fan
    // also avoids nested stencil masks, which would punch holes in the outer field mask.
    private void gradient(double x,double y,double w,double h,double radius,Color top,Color bottom) {
        var gl=api.getGLStateManager();
        gl.enableBlend(); gl.blendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
        gl.disableTexture2D(); gl.disableAlpha(); GL11.glShadeModel(GL11.GL_SMOOTH);
        double r=Math.min(radius,Math.min(w,h)/2);
        if(r<=0) {
            GL11.glBegin(GL11.GL_QUADS);
            gradientVertex(x,y,y,h,top,bottom); gradientVertex(x+w,y,y,h,top,bottom);
            gradientVertex(x+w,y+h,y,h,top,bottom); gradientVertex(x,y+h,y,h,top,bottom);
        } else {
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            gradientVertex(x+w/2,y+h/2,y,h,top,bottom);
            for(int corner=0;corner<4;corner++) for(int step=0;step<=8;step++) {
                double angle=Math.toRadians(-90+corner*90+step*90.0/8);
                double cx=corner<2?x+w-r:x+r,cy=corner==0||corner==3?y+r:y+h-r;
                gradientVertex(cx+Math.cos(angle)*r,cy+Math.sin(angle)*r,y,h,top,bottom);
            }
            gradientVertex(x+w-r,y,y,h,top,bottom);
        }
        GL11.glEnd(); GL11.glShadeModel(GL11.GL_FLAT); gl.enableTexture2D(); gl.color(1,1,1,1);
    }
    private static void gradientVertex(double x,double y,double topY,double height,Color top,Color bottom) {
        double t=Math.clamp((y-topY)/height,0,1);
        GL11.glColor4f((float)FoliaMotion.lerp(top.getRed()/255f,bottom.getRed()/255f,t),
                (float)FoliaMotion.lerp(top.getGreen()/255f,bottom.getGreen()/255f,t),
                (float)FoliaMotion.lerp(top.getBlue()/255f,bottom.getBlue()/255f,t),
                (float)FoliaMotion.lerp(top.getAlpha()/255f,bottom.getAlpha()/255f,t));
        GL11.glVertex2d(x,y);
    }
    private static Color color(int rgb,double alpha) { return new Color((rgb&0xFFFFFF)|((int)(Math.clamp(alpha,0,1)*255)<<24),true); }
}
