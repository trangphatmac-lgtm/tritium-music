// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import java.util.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** A single typeset article: the read head travels over it while each lyric prints into the paper. */
final class FumeScene implements LyricScene {
    private record Letter(LyricTimeline.Glyph glyph,double x,double y,double advance) { }
    private record Block(int index,double x,double y,double width,double height,double size,boolean hero,List<Letter> letters) { }
    private final List<Block> blocks=new ArrayList<>();
    private final Spring cameraX=new Spring(100,20),cameraY=new Spring(100,20),cameraZoom=new Spring(100,20);
    private LyricTimeline timeline;
    private double viewportWidth,paperWidth,paperHeight;
    private void prepare(LyricCanvas g,LyricFrame f){
        if(timeline==f.timeline()&&viewportWidth==f.width())return;
        timeline=f.timeline();viewportWidth=f.width();blocks.clear();
        int count=timeline.lines().size(),columns=Math.max(2,Math.min(5,(int)Math.ceil(Math.sqrt(count/3.0))));
        paperWidth=Math.max(900,f.width()*1.7);double gap=42,columnWidth=(paperWidth-gap*(columns-1))/columns;
        double[] bottoms=new double[columns];var order=new ArrayList<Integer>();for(int i=0;i<count;i++)order.add(i);
        order.sort(Comparator.comparingDouble(i->random(f.seed()+i*13+timeline.line(i).text().hashCode())));
        for(int step=0;step<count;step++){
            int index=order.get(step);var line=timeline.line(index);boolean hero=(step+1)%6==0||(count<6&&step==count/2);
            int span=hero?Math.min(2,columns):1,col=0;double top=Double.MAX_VALUE;
            for(int c=0;c<=columns-span;c++){double b=0;for(int j=0;j<span;j++)b=Math.max(b,bottoms[c+j]);if(b<top){top=b;col=c;}}
            double width=columnWidth*span+gap*(span-1),density=line.glyphs().size()+LyricWords.split(line).size()*1.4;
            double size=Math.max(hero?24:14,Math.min(hero?54:28,width/Math.max(Math.sqrt(density)*(hero?1.5:2.25),hero?4.5:7)));
            var letters=new ArrayList<Letter>();double x=0,y=0,lineHeight=size*1.38;
            var wrapped=g.wrapGlyphs(line,size,width,hero);
            for(var row:wrapped){
                x=0;
                for(var glyph:row){
                    double advance=g.width(glyph.text(),size,hero);
                    letters.add(new Letter(glyph,x,y,advance));x+=advance;
                }
                y+=lineHeight;
            }
            y=Math.max(0,y-lineHeight);
            double height=y+lineHeight;
            blocks.add(new Block(index,col*(columnWidth+gap),top,width,height,size,hero,List.copyOf(letters)));
            for(int c=col;c<col+span;c++)bottoms[c]=top+height+gap*(hero?1.5:1);
        }
        paperHeight=Arrays.stream(bottoms).max().orElse(720);blocks.sort(Comparator.comparingInt(Block::index));
    }
    @Override public void render(LyricCanvas g,LyricFrame f){
        prepare(g,f);SceneBackdrop.ambient(g,f,0xff111419);if(blocks.isEmpty())return;
        int active=Math.max(0,Math.min(blocks.size()-1,f.index()));Block focus=blocks.get(active);
        int readIndex=0;
        while(readIndex+1<focus.letters.size()&&focus.letters.get(readIndex+1).glyph.start()<=f.time())readIndex++;
        Letter letter=focus.letters.get(readIndex);double offset=letter.glyph.progress(f.time());
        double targetX=focus.x+letter.x+letter.advance*offset,targetY=focus.y+letter.y+focus.size*.65;
        double zoom=Math.max(.88,Math.min(2.2,Math.max(64,Math.min(124,Math.min(f.width(),f.contentHeight())*.115))/(focus.size*1.38)));
        boolean overview=f.time()>timeline.lines().getLast().end()+2;
        if(overview){targetX=paperWidth/2;targetY=paperHeight/2;zoom=Math.max(.22,Math.min(f.width()*.86/paperWidth,f.contentHeight()*.8/paperHeight));}
        if(f.discontinuity()){cameraX.snap(targetX);cameraY.snap(targetY);cameraZoom.snap(zoom);}
        double x=cameraX.step(targetX,f.dt()),y=cameraY.step(targetY,f.dt()),scale=cameraZoom.step(zoom,f.dt());
        g.push(f.width()/2,f.contentHeight()*.46,scale,Math.sin(f.time()*.12)*.2);g.push(-x,-y,1,0);
        for(Block block:blocks){
            double sx=(block.x-x)*scale+f.width()/2,sy=(block.y-y)*scale+f.contentHeight()*.46;
            if(sx+block.width*scale<0||sx>f.width()||sy+block.height*scale<0||sy>f.contentHeight())continue;
            boolean live=block.index==f.index();
            for(Letter glyph:block.letters){
                double progress=glyph.glyph.progress(f.time()),age=f.time()-glyph.glyph.end();
                double opacity=f.time()<glyph.glyph.start()?(block.hero?.06:.035):live?(block.hero?.985:.92):(block.hero?.74:.58);
                double trail=1-smooth(Math.max(0,age)/1.45),reveal=smooth((f.time()-glyph.glyph.start())/.13);
                int ink=color(0xffe4e7eb,f.accent(),trail*.8);
                g.glowText(glyph.glyph.text(),block.x+glyph.x,block.y+glyph.y-(1-reveal)*block.size*.04,block.size,
                        alpha(ink,opacity),live?Math.sin(progress*Math.PI)*block.size*.42:trail*2,block.hero);
            }
            if(live){double markerX=focus.x+letter.x+letter.advance*offset;
                g.line(markerX,focus.y+letter.y+focus.size*1.07,markerX+Math.max(3,focus.size*.18),focus.y+letter.y+focus.size*1.07,1,alpha(f.accent(),.6));}
        }
        g.pop();g.pop();
    }
    @Override public void reset(){timeline=null;blocks.clear();}
}
