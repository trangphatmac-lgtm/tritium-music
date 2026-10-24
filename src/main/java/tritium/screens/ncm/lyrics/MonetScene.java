// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import tritium.desktop.BackgroundPreferences;
import tritium.desktop.background.cover.CoverRenderer;
import tritium.rendering.StencilClipManager;
import tritium.rendering.TextureManager;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Folia's cover-derived painted poster, portrait, spring-driven lyric rail and reactive equalizer. */
final class MonetScene implements LyricScene {
    private final Spring scroll=new Spring(142/.82,28/.82);
    private final List<Double> positions=new ArrayList<>();
    private final List<List<List<LyricTimeline.Glyph>>> rows=new ArrayList<>();
    private LyricTimeline timeline;
    private double width;
    private boolean translation, romanization;
    private CoverRenderer background;
    private ExecutorService worker;
    private double entry;
    @Override public boolean ownSubtitles(){return true;}
    private void prepare(LyricCanvas g,LyricFrame f,double railWidth){
        if(timeline==f.timeline()&&width==railWidth&&translation==f.translation()&&romanization==f.romanization())return;
        translation=f.translation();romanization=f.romanization();
        timeline=f.timeline();width=railWidth;positions.clear();rows.clear();double cursor=0;
        for(var line:timeline.lines()){
            var wrapped=g.wrapGlyphs(line,43.8,railWidth,true);rows.add(wrapped);positions.add(cursor);
            cursor+=Math.max(1,wrapped.size())*54+24
                    +(translation?g.wrap(line.translation(),19,railWidth,false).size()*24:0)
                    +(romanization?g.wrap(line.romanization(),16,railWidth,false).size()*20:0);
        }
    }
    @Override public void render(LyricCanvas g,LyricFrame f){
        if(background==null)try{
            var prefs=new BackgroundPreferences();prefs.coverMode().setValue("monet");prefs.monetLayout().setValue("half-pane-gradient");
            worker=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"lyric-monet-cover");thread.setDaemon(true);return thread;});
            background=new CoverRenderer(prefs,worker);
        }catch(IOException e){throw new IllegalStateException("Cannot initialize Monet background",e);}
        try{background.render(f.width(),f.height(),f.paused()?0:f.dt(),f.song()==null?null:f.song().getCoverLocation(),true);}
        catch(IOException e){SceneBackdrop.ambient(g,f,0xff18151e);}
        g.gradient(0,0,f.width(),f.height(),0x181d1524,0x6b17131e);
        entry=Math.min(2,entry+f.dt());double enter=out(entry/1.6);
        double w=f.width(),h=f.contentHeight(),left=Math.max(48,w*.075),railWidth=w>=850?w*.50:w-left*2;
        prepare(g,f,railWidth);
        double titleY=h*.15,titleSize=Math.min(48,w*.04);
        String title=f.song()==null?"Monet":f.song().getName(),artist=f.song()==null?"":f.song().getArtistsName();
        double fitted=Math.min(titleSize,railWidth/Math.max(1,g.width(title,1,true)));
        g.text(title,left,titleY+(1-enter)*16,fitted,alpha(0xfff9f3ed,enter),true);
        g.text(artist,left,titleY+fitted+14,16,alpha(0xffe1cfd7,enter*.8),false);
        double top=titleY+fitted+60,bottom=h-58,anchor=mix(top,bottom,.43);
        int active=Math.max(0,f.index());
        if(active<rows.size()){
            var current=timeline.line(active);
            double paragraphHeight=rows.get(active).size()*54
                    +(f.translation()?g.wrap(current.translation(),19,railWidth,false).size()*24:0)
                    +(f.romanization()?g.wrap(current.romanization(),16,railWidth,false).size()*20:0);
            anchor=Math.max(top+12,Math.min(anchor,bottom-paragraphHeight));
        }
        double target=positions.isEmpty()?0:positions.get(Math.min(active,positions.size()-1));
        if(f.discontinuity())scroll.snap(target);double position=scroll.step(target,f.dt());
        StencilClipManager.beginClip(()->g.rect(left-18,top-12,railWidth+36,Math.max(1,bottom-top+24),0xffffffff));
        try{
            for(int i=Math.max(0,active-4);i<Math.min(rows.size(),active+5);i++){
                var line=timeline.line(i);double y=anchor+positions.get(i)-position,offset=i-active,distance=Math.max(1,Math.abs(offset));
                boolean live=i==f.index(),waiting=i>f.index();double scale=live?1:Math.max(.68,.72*Math.pow(.9,distance-1));
                double opacity=live?1:waiting?Math.max(.36,.72-(distance-1)*.18):Math.max(.28,.52-(distance-1)*.12);
                opacity*=smooth((y-top+12)/42)*(1-smooth((y-bottom+28)/55));
                if(opacity<.002)continue;double size=43.8*scale;
                double drawY=y;
                for(var row:rows.get(i)){
                    double x=left;
                    for(var glyph:row){
                        String ch=glyph.text();double progress=glyph.progress(f.time());
                        double rise=Math.max(.001,glyph.end()-glyph.start())*1.18,peak=glyph.start()+rise,tail=Math.max(line.end(),glyph.end()+1.05);
                        double glow=f.time()<=glyph.start()?0:f.time()<=peak?smooth((f.time()-glyph.start())/rise):smooth(1-clamp((f.time()-peak)/Math.max(.18,tail-peak)));
                        int ink=live?color(0x57f8f2e9,0xfffcf7f0,progress):waiting?0x75f0e9e7:0x5ce5dce1;
                        g.glowText(ch,x,drawY,size,alpha(ink,opacity),live?glow*12:0,true);x+=g.width(ch,size,true);
                    }drawY+=size*1.23;
                }
                if(live){
                    if(f.translation()&&!line.translation().isBlank()){for(String row:g.wrap(line.translation(),19,railWidth,false)){g.text(row,left,drawY+8,19,alpha(0xffeadfe4,opacity*.88),false);drawY+=24;}}
                    if(f.romanization()&&!line.romanization().isBlank())for(String row:g.wrap(line.romanization(),16,railWidth,false)){g.text(row,left,drawY+8,16,alpha(0xffd3c9d7,opacity*.65),false);drawY+=20;}
                }
                g.hit(left,y,railWidth,Math.max(size,drawY-y),line.start());
            }
        }finally{StencilClipManager.endClip();}
        if(w>=850){
            double size=Math.min(w*.31,h*.64),cx=w*.79,cy=h*.51,px=cx-size/2+(1-enter)*40,py=cy-size/2;
            g.glow(cx,cy+size*.4,size*.7,size*.4,0x55000000);
            g.roundedRect(px-5,py-5,size+10,size+10,26,alpha(0x33ffffff,enter));
            if(f.song()!=null&&TextureManager.getInstance().getTexture(f.song().getCoverLocation())!=null){
                TextureManager.getInstance().bindTexture(f.song().getCoverLocation());g.roundedRectTextured(px,py,size,size,22,(float)(enter*g.opacity));
            }else g.roundedRect(px,py,size,size,22,alpha(color(0xff403548,f.accent(),.16),enter));
            // Folia's fine ornamental cross and staggered bars sit behind the portrait.
            g.line(px-14,py+size*.25,px+14,py+size*.25,1,0x99dfd3df);g.line(px,py+size*.25-14,px,py+size*.25+14,1,0x99dfd3df);
            for(int i=0;i<24;i++){
                double band=mix(f.energy().bass(),f.energy().treble(),i/23.0),height=2+band*(12+random(i+f.seed())*28);
                g.rect(cx-70+i*6,cy+size/2+35-height/2,3,height,alpha(0xffead8e7,.28+band*.55));
            }
        }
        g.roundedRect(left,h-34,130,28,14,0x252a1e36);g.circle(left+15,h-20,3,f.accent());g.text("MONET",left+28,h-29,13,0xcccfc1cf,false);
    }
    @Override public void reset(){timeline=null;entry=0;}
    @Override public void close(){if(background!=null)background.close();background=null;if(worker!=null)worker.shutdownNow();worker=null;}
}
