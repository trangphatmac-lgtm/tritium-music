// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.*;
import java.util.*;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Sonnet's seven typography archetypes and original 100 animated motion-graphics designs. */
final class SonnetScene implements LyricScene {
    private record Shot(int first,int last,double start,double end,int kind) { }
    private record Word(LyricWords.Segment segment,double x,double y,double size,double angle,boolean vertical) { }
    private record Layout(List<Word> words,double fit) { }
    private LyricTimeline timeline;
    private final List<Shot> shots=new ArrayList<>();
    private final Map<Integer,VectorComposition[]> graphics=new HashMap<>();
    private final Map<Integer,Layout> layouts=new HashMap<>();
    private double layoutWidth;
    private void prepare(LyricFrame f){
        if(timeline!=f.timeline()){
            timeline=f.timeline();shots.clear();layouts.clear();int first=0,previous=-1;
            while(first<timeline.lines().size()){
                int last=first;var start=timeline.line(first);
                while(last+1<timeline.lines().size()&&last-first<3&&timeline.line(last+1).end()-start.start()<=6
                        &&timeline.line(last+1).start()-timeline.line(last).end()<1.5)last++;
                int kind=variant(f.seed(),shots.size(),7);if(kind==previous)kind=(kind+1)%7;previous=kind;
                shots.add(new Shot(first,last,start.start(),timeline.line(last).end(),kind));first=last+1;
            }
        }
        if(layoutWidth!=f.width()){layoutWidth=f.width();layouts.clear();}
    }
    @Override public void render(LyricCanvas g,LyricFrame f){
        prepare(f);SceneBackdrop.ambient(g,f,0xff14121b);if(shots.isEmpty())return;
        int index=0;while(index+1<shots.size()&&shots.get(index+1).start<=f.time())index++;
        Shot shot=shots.get(index);double local=f.time()-shot.start,duration=Math.max(1,shot.end-shot.start),progress=clamp(local/duration);
        double enter=out(local/.7),exit=1-smooth((f.time()-shot.end-.4)/1.3);
        int variant=variant(f.seed(),index,100);
        VectorComposition[] art=graphics.computeIfAbsent(variant,v->{JsonObject item=data("sonnet-geometry.json").getAsJsonArray().get(v).getAsJsonObject();
            return new VectorComposition[]{new VectorComposition(item.getAsJsonObject("geo")),new VectorComposition(item.getAsJsonObject("hud"))};});
        if(graphics.size()>3)graphics.keySet().removeIf(k->k!=variant);
        double cx=f.width()/2,cy=f.contentHeight()/2;
        g.push(cx,cy,Math.min(f.width()/1280,f.contentHeight()/720),0);
        art[1].drawArt(g,progress,exit*.72,f.accent());
        g.push(0,0,1+progress*.06,Math.sin(progress*Math.PI)*3*(index%2==0?1:-1));
        art[0].drawArt(g,clamp(local/(duration*.95)),exit*.52,f.accent());g.pop();g.pop();
        // Large, low-contrast print underneath the ordinary word layer is part of Sonnet's PV language.
        var hero=timeline.line(shot.first).text();
        g.push(cx,cy,1,-8);g.centered(hero,cx*.03,0,Math.min(230,f.width()/Math.max(1,g.width(hero,1,true))*1.2),alpha(f.accent(),.07*enter*exit),true);g.pop();
        final int shotIndex=index;
        Layout layout=layouts.computeIfAbsent(index,i->layout(g,f,shots.get(shotIndex)));
        double cameraZoom=(shot.kind==5?1.02:shot.kind==6?1.05:1.1)+progress*.08;
        g.push(cx+(1-enter)*f.width()*.12,cy,layout.fit*cameraZoom,Math.sin(progress*Math.PI)*1.5*(index%2==0?1:-1));
        for(Word word:layout.words){
            var seg=word.segment;double size=word.size,total=word.vertical?size*.9*seg.glyphs().size():g.width(seg.text(),size,true);
            double cursor=-total/2;
            g.push(word.x,word.y,1,word.angle);
            for(int i=0;i<seg.glyphs().size();i++){
                var glyph=seg.glyphs().get(i);double advance=word.vertical?size*.9:g.width(glyph.text(),size,true);
                double motionDuration=Math.min(Math.min(1.8,Math.max(.65,duration*.42)),duration*.72);
                double reveal=out((f.time()-glyph.start())/motionDuration),sing=Math.sin(glyph.progress(f.time())*Math.PI);
                double x=word.vertical?-advance*.5:cursor,y=word.vertical?cursor:-size*.55;
                double dx=(1-reveal)*(shot.kind==4?(i%2==0?-1:1)*size*.8:((i%2==0?-1:1)*size*.28));
                double dy=(1-reveal)*size*(shot.kind==2?1.2:.45);
                g.push(x+dx,y+dy,1,(1-reveal)*(i%2==0?-3:3));
                int ink=color(0xfff5f1e9,f.accent(),sing*.8);
                g.glowText(glyph.text(),0,0,size,alpha(ink,(.09+.91*reveal)*exit),sing*size*.12,true);g.pop();cursor+=advance;
            }
            if(shot.kind==5)g.frame(-total/2-14,-size*.68,total+28,size*1.4,1,alpha(0xffece5e0,enter*.24*exit));
            g.pop();
        }
        g.pop();
        double margin=Math.min(42,f.width()*.04);g.frame(margin,margin,f.width()-margin*2,f.contentHeight()-margin*2,1,0x44eee7e2);
        g.text("SONNET  /  "+String.format(Locale.ROOT,"%02d",index+1),margin+12,margin+10,11,0x88eee7e2,false);
        for(int i=0;i<4;i++){double x=i%2==0?margin:f.width()-margin,y=i<2?margin:f.contentHeight()-margin;g.line(x-7,y,x+7,y,1,0x99ffffff);g.line(x,y-7,x,y+7,1,0x99ffffff);}
    }
    private Layout layout(LyricCanvas g,LyricFrame f,Shot shot){
        var segments=new ArrayList<LyricWords.Segment>();
        for(int i=shot.first;i<=shot.last;i++)for(var seg:LyricWords.split(timeline.line(i)))if(!seg.text().isBlank())segments.add(seg);
        int hero=0;double score=-1;
        for(int i=0;i<segments.size();i++){var s=segments.get(i);double v=Math.min(s.glyphs().size(),8)*14+Math.min(2.5,s.end()-s.start())*18;if(v>score){score=v;hero=i;}}
        var words=new ArrayList<Word>();double cursor=0,maxX=1,maxY=1;
        for(int i=0;i<segments.size();i++){
            var seg=segments.get(i);boolean big=i==hero,vertical=false;double size=big?92:34,x=0,y=0,angle=0;
            double width=g.width(seg.text(),size,true),phase=2*Math.PI*i/Math.max(1,segments.size());
            switch(shot.kind){
                case 0->{x=big?-95:145;y=big?0:(i-segments.size()/2.0)*56;vertical=big&&seg.glyphs().size()<7;}
                case 1->{x=big?0:(i%2==0?-1:1)*(170+width/3);y=big?0:(i-segments.size()/2.0)*50;size=big?118:30;}
                case 2->{x=Math.cos(phase)*(big?95:250);y=Math.sin(phase)*145;angle=(i%3-1)*18;vertical=!big&&i%4==0;}
                case 3->{x=cursor+width/2;y=Math.sin(i*.7)*65;cursor+=width+24;angle=Math.cos(i*.7)*7;}
                case 4->{x=(i-segments.size()/2.0)*85;y=(i%2==0?-1:1)*45;vertical=big;}
                case 5->{x=(i%3-1)*250;y=(i/3-(segments.size()+2)/3.0*.5)*135;size=big?80:32;}
                default->{x=big?-100:140;y=big?0:(i-segments.size()/2.0)*45;vertical=big&&seg.glyphs().size()<6;size=big?74:28;}
            }
            words.add(new Word(seg,x,y,size,angle,vertical));
        }
        if(shot.kind==3){double total=cursor;words.replaceAll(word->new Word(word.segment,word.x-total/2,word.y,word.size,word.angle,word.vertical));}
        for(Word word:words){double width=word.vertical?word.size:g.width(word.segment.text(),word.size,true),height=word.vertical?word.size*.9*word.segment.glyphs().size():word.size*1.2;
            maxX=Math.max(maxX,Math.abs(word.x)+width*.55);maxY=Math.max(maxY,Math.abs(word.y)+height*.55);}
        return new Layout(words,Math.min(1,Math.min(f.width()*.38/maxX,f.contentHeight()*.32/maxY)));
    }
    @Override public void reset(){timeline=null;layouts.clear();graphics.clear();}
}
