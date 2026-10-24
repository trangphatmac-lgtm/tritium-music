// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.*;
import java.util.*;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Screentone compositions, continuous flow handoffs, staggered type and adaptive difference ink. */
final class TemperaScene implements LyricScene {
    private final JsonObject profiles=data("tempera-profiles.json").getAsJsonObject();
    private final List<String> kinds=profiles.entrySet().stream().map(Map.Entry::getKey).toList();
    private final Map<String,VectorComposition> compositions=new LinkedHashMap<>();
    private final ContrastInk ink=new ContrastInk();
    @Override public void render(LyricCanvas g,LyricFrame f){
        g.rect(0,0,f.width(),f.height(),0xff17131e);
        int current=Math.max(0,f.index());var line=f.current();double age=line==null?f.time():f.time()-line.start();
        double handoff=out(age/.8);
        if(current>0&&handoff<1)drawShot(g,f,current-1,1,-handoff*f.width()*1.1);
        drawShot(g,f,current,1,(1-handoff)*f.width()*1.1);
        double margin=28;int color=0x60e9e4ee;
        for(int i=0;i<4;i++){
            double x=i%2==0?margin:f.width()-margin,y=i<2?margin:f.contentHeight()-margin,dx=i%2==0?14:-14,dy=i<2?14:-14;
            g.line(x,y,x+dx,y,1,color);g.line(x,y,x,y+dy,1,color);
        }
        g.text(String.format(java.util.Locale.ROOT,"%03d",current+1),f.width()-68,40,12,0x88ffffff,false);
    }
    private void drawShot(LyricCanvas g,LyricFrame f,int index,double alpha,double slide){
        String kind=kinds.get(variant(f.seed(),index,kinds.size()));JsonObject p=profiles.getAsJsonObject(kind),camera=object(p,"camera");
        var line=f.timeline().line(index);double duration=line==null?6:Math.max(.2,line.end()-line.start()),local=line==null?f.time():f.time()-line.start();
        double progress=clamp(local/duration),zoom=mix(n(camera,"zoomStart",1.05),n(camera,"zoomEnd",1.12),smooth(progress));
        VectorComposition composition=compositions.computeIfAbsent(kind,k->new VectorComposition(data("tempera-geometry.json").getAsJsonObject().getAsJsonArray(k)));
        if(compositions.size()>4){String oldest=compositions.keySet().iterator().next();if(!oldest.equals(kind))compositions.remove(oldest);}
        g.push(f.width()/2+slide,f.contentHeight()/2,zoom,0);g.push(-f.width()/2,-f.contentHeight()/2,1,0);
        composition.draw(g,f,local,duration,alpha);
        if(line!=null)ink.draw(()->type(g,f,line,p,local,alpha));
        g.pop();g.pop();
    }
    private void type(LyricCanvas g,LyricFrame f,LyricTimeline.Line line,JsonObject p,double local,double opacity){
        JsonObject r=object(p,"region"),enter=object(p,"enter");double width=f.width()*n(r,"w",.8),height=f.contentHeight()*n(r,"h",.5);
        double size=Math.min(92,f.contentHeight()*.12)*n(r,"fontScale",1);
        var rows=g.wrapGlyphs(line,size,width,true);
        for(int attempt=0;attempt<24&&rows.size()*size*1.2>height;attempt++){
            size*=.88;rows=g.wrapGlyphs(line,size,width,true);
        }
        double y=-rows.size()*size*.6;
        g.push(n(r,"cx",.5)*f.width(),n(r,"cy",.5)*f.contentHeight(),1,Math.toDegrees(n(r,"rotation",0)));
        int glyphIndex=0;
        for(var row:rows){double rowWidth=0;for(var ch:row)rowWidth+=g.width(ch.text(),size,true);double x=s(r,"align","center").equals("left")?-width/2:s(r,"align","center").equals("right")?width/2-rowWidth:-rowWidth/2;
            for(var glyph:row){
                String ch=glyph.text();glyphIndex++;
                double reveal=out((f.time()-glyph.start()+.12)/Math.max(.35,Math.min(1.4,(line.end()-line.start())*.24)));
                double dx=n(enter,"x",0)*size*(1-reveal),dy=n(enter,"y",1)*size*(1-reveal);
                double advance=g.width(ch,size,true);
                g.push(x+dx,y+dy,1,Math.sin(glyphIndex)*5*(1-reveal));
                g.text(ch,0,0,size,alpha(0xffffffff,(.14+.86*reveal)*opacity),true);g.pop();x+=advance;
            }y+=size*1.2;
        }
        g.pop();
    }
    @Override public void close(){ink.close();compositions.clear();}
}
