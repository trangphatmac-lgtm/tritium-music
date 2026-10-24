// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import tritium.desktop.BackgroundPreferences;
import tritium.desktop.background.latent.LatentRenderer;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Native Folia classic: spring words, timed double halos, slow rotation and breathing float. */
final class ClassicScene implements LyricScene {
    private final ClassicInk ink=new ClassicInk();
    private final Map<Integer,ClassicLayout.Layout> layouts=new HashMap<>();
    private Set<String> chorus=Set.of();
    private LyricTimeline timeline;
    private double width,height;
    private LatentRenderer background;
    private ExecutorService worker;

    @Override public void render(LyricCanvas c,LyricFrame f) {
        if(background==null)try {
            worker=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"lyric-classic-palette");thread.setDaemon(true);return thread;});
            background=new LatentRenderer(new BackgroundPreferences(),worker);
        }catch(IOException e){throw new IllegalStateException("Cannot initialize classic background",e);}
        background.render(f.width(),f.height(),f.paused()?0:f.dt());
        c.rect(0,0,f.width(),f.height(),0x4509090b);
        if(timeline!=f.timeline()||width!=f.width()||height!=f.contentHeight()) {
            timeline=f.timeline();width=f.width();height=f.contentHeight();layouts.clear();chorus=chorusLines(timeline);
        }
        int current=f.index();if(current<0)return;
        var glyphs=new ArrayList<ClassicInk.Glyph>();
        int first=current;
        while(first>0&&f.time()<exitAt(first-1)+ClassicMotion.Profile.of(timeline.line(first-1)).exit)first--;
        layouts.keySet().removeIf(i->i<current-8||i>current);
        for(int index=first;index<=current;index++)paintLine(c,f,index,glyphs);
        ink.draw(c,glyphs);
    }
    private double exitAt(int index) {
        var next=timeline.line(index+1);
        return Math.min(ClassicMotion.renderEnd(timeline.line(index)),next==null?Double.POSITIVE_INFINITY:next.start());
    }
    private void paintLine(LyricCanvas c,LyricFrame f,int index,List<ClassicInk.Glyph> glyphs) {
        var line=timeline.line(index);var profile=ClassicMotion.Profile.of(line);
        var motion=ClassicMotion.line(line,f.time(),exitAt(index));if(motion.opacity()<.002)return;
        var layout=layouts.computeIfAbsent(index,i->ClassicLayout.create(line,width,Math.max(80,height-230),text->c.width(text,1,true)));
        double scale=layout.fit()*motion.scale()*ClassicMotion.breathing(f.time(),true);
        double centerY=(height-115)*.5+ClassicMotion.breathing(f.time(),false);
        int active=0xfff4f4f5; // Folia's default classic theme uses a white highlight and white primary ink.
        double renderEnd=ClassicMotion.renderEnd(line);
        for(var word:layout.words()) {
            var pose=ClassicMotion.word(word,profile,f.time(),renderEnd);
            double opacity=pose.opacity()*motion.opacity();if(opacity<.002)continue;
            double cx=width/2+(word.x()-layout.width()/2+pose.x())*scale;
            double cy=centerY+(word.y()-layout.height()/2+pose.y())*scale;
            double wordScale=pose.scale()*scale,angle=radians(pose.angle());
            if(chorus.contains(line.text().trim())) {
                double phase=(f.time()-word.segment().start()+profile.lookahead)/.5;
                if(phase>=0&&phase<1&&f.time()<=ClassicMotion.activeEnd(word.segment(),profile,renderEnd)) {
                    double radius=layout.size()*.75*wordScale*mix(.2,1.5+random(word.segment().start()+8)*2,out(phase));
                    c.ring(cx,cy,radius,2,0,Math.PI*2,alpha(active,opacity*.2*(1-out(phase))));
                    c.ring(cx,cy,radius+.5,1,0,Math.PI*2,alpha(active,opacity*.6*(1-out(phase))));
                }
            }
            double cursor=-word.width()/2;
            int body=alpha(color(0xfff4f4f5,active,pose.highlight()),opacity);
            for(var glyph:word.segment().glyphs()) {
                double glyphWidth=c.width(glyph.text(),layout.size(),true),offset=cursor+glyphWidth/2;
                if(!glyph.text().isBlank())glyphs.add(new ClassicInk.Glyph(glyph.text(),layout.size(),
                        cx+Math.cos(angle)*offset*wordScale,cy+Math.sin(angle)*offset*wordScale,
                        wordScale,pose.angle(),body,alpha(active,opacity),
                        ClassicMotion.glow(word.segment(),glyph,profile,f.time(),renderEnd),
                        Math.hypot(pose.blur(),motion.blur()/Math.max(.1,pose.scale())),profile.inner,profile.outer));
                cursor+=glyphWidth;
            }
        }
    }
    /** Folia chorusDetector's fallback: the most repeated exact, trimmed lyric text. */
    static Set<String> chorusLines(LyricTimeline timeline) {
        var counts=new HashMap<String,Integer>();
        for(var line:timeline.lines())if(line.text().trim().length()>=2)counts.merge(line.text().trim(),1,Integer::sum);
        int max=counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        var result=new HashSet<String>();if(max>1)counts.forEach((text,count)->{if(count==max)result.add(text);});
        return Set.copyOf(result);
    }
    @Override public void reset(){layouts.clear();timeline=null;}
    @Override public void close(){ink.close();if(background!=null)background.close();background=null;if(worker!=null)worker.shutdownNow();worker=null;reset();}
}
