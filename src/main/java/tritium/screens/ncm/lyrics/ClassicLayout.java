// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Seeded classic word layout, with native wrapping that reserves the animated ink bounds. */
final class ClassicLayout {
    record Word(LyricWords.Segment segment, double width, double x, double y,
                double dx, double dy, double angle, double scale, double passedAngle) { }
    record Layout(List<Word> words, double size, double width, double height, double fit) { }
    private record Slot(LyricWords.Segment segment, double width, double dx, double dy,
                        double angle, double scale, double passedAngle, double boxWidth, double boxHeight) { }

    static Layout create(LyricTimeline.Line line, double viewportWidth, double availableHeight, ToDoubleFunction<String> emWidth) {
        var segments=LyricWords.split(line).stream().filter(w->!w.text().isBlank()).toList();
        double limit=Math.max(80,Math.min(1152,viewportWidth-128));
        double size=Math.max(36,Math.min(72,viewportWidth*.06));
        Layout result=null;
        for(int attempt=0;attempt<24;attempt++,size*=.88) {
            result=arrange(segments,line.start(),size,limit,emWidth);
            if(result.width()<=limit&&result.height()<=availableHeight) return result;
        }
        double fit=Math.min(1,Math.min(limit/Math.max(1,result.width()),availableHeight/Math.max(1,result.height())));
        return new Layout(result.words(),result.size(),result.width(),result.height(),fit);
    }
    private static Layout arrange(List<LyricWords.Segment> segments, double seed, double size, double limit,
                                  ToDoubleFunction<String> measure) {
        var rows=new ArrayList<List<Slot>>(); var row=new ArrayList<Slot>(); double rowWidth=0;
        for(int i=0;i<segments.size();i++) {
            var segment=segments.get(i); double width=measure.applyAsDouble(segment.text())*size;
            double dx=(random(seed+i+1)-.5)*40,dy=(random(seed+i+2)-.5)*40;
            double rotation=(random(seed+i+3)-.5)*10,scale=1.1+random(seed+i+4)*.2;
            double passed=(random(seed+i+8)-.5)*45;
            // Include the 4.3% spring overshoot and the later rotation, not just the resting word width.
            double peak=scale*1.4*1.044;
            double angle=radians(Math.abs(rotation)+Math.abs(passed));
            double boxWidth=Math.max((width+size*.15)*peak,
                    (width*Math.cos(angle)+size*1.22*Math.sin(angle))*scale)+40;
            double boxHeight=Math.max(size*1.3*peak,
                    (width*Math.sin(angle)+size*1.22*Math.cos(angle))*scale)+40;
            var slot=new Slot(segment,width,dx,dy,rotation,scale,passed,boxWidth,boxHeight);
            if(!row.isEmpty()&&rowWidth+boxWidth>limit) {rows.add(row);row=new ArrayList<>();rowWidth=0;}
            row.add(slot);rowWidth+=boxWidth+size*.084;
        }
        if(!row.isEmpty()) rows.add(row);
        var result=new ArrayList<Word>(); double height=0,usedWidth=0;
        int justify=(int)Math.floor(seed%5),align=(int)Math.floor(seed*2%3);
        for(var slots:rows) {
            double width=slots.stream().mapToDouble(Slot::boxWidth).sum()+Math.max(0,slots.size()-1)*size*.084;
            double rowHeight=slots.stream().mapToDouble(Slot::boxHeight).max().orElse(0);
            double spare=Math.max(0,limit-width),extra=0;
            double x=switch(justify) { case 0,4 -> 0; case 2 -> spare; case 3 -> spare/(slots.size()*2); default -> spare/2; };
            if(justify==3) extra=spare/slots.size();
            if(justify==4&&slots.size()>1) extra=spare/(slots.size()-1);
            for(var slot:slots) {
                double y=height+slot.boxHeight()/2+(align==0?0:align==1?(rowHeight-slot.boxHeight())/2:rowHeight-slot.boxHeight());
                result.add(new Word(slot.segment(),slot.width(),x+slot.boxWidth()/2,y,
                        slot.dx(),slot.dy(),slot.angle(),slot.scale(),slot.passedAngle()));
                x+=slot.boxWidth()+size*.084+extra;
            }
            height+=rowHeight+size*.12;usedWidth=Math.max(usedWidth,Math.max(limit,width));
        }
        return new Layout(List.copyOf(result),size,usedWidth,Math.max(0,height-size*.12),1);
    }
}
