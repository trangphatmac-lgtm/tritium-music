package tritium.screens.ncm.lyrics;

import org.junit.jupiter.api.Test;
import tritium.screens.ncm.LyricLine;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClassicSceneTest {
    private static LyricTimeline.Line line(String text,long duration) {
        var source=new LyricLine(1000,text);source.duration=duration;
        return new LyricTimeline(List.of(source),30).line(0);
    }
    @Test void englishAndChineseLayoutsFitWithoutSplittingWordsOrLosingTiming() {
        for(var text:List.of("All I know","I'll stay, and we'll find our way.",
                "When the city lights are fading, we will follow every echo through the rain",
                "光穿过漫长的时间，像繁星缓缓落在肩上", "Supercalifragilisticexpialidocious")) {
            var line=line(text,7000);
            for(double width:new double[]{1280,518,320}) {
                var layout=ClassicLayout.create(line,width,420,s->s.codePointCount(0,s.length())*.6);
                assertTrue(layout.width()*layout.fit()<=Math.max(80,width-128)+.01);
                assertTrue(layout.height()*layout.fit()<=420.01);
                assertEquals(LyricWords.split(line).stream().filter(w->!w.text().isBlank()).toList(),
                        layout.words().stream().map(ClassicLayout.Word::segment).toList());
                assertTrue(layout.size()>0&&Double.isFinite(layout.size()));
            }
        }
    }
    @Test void springAndGlyphGlowFollowTheirTimingsAndRemainStableAfterSeeking() {
        var line=line("All I know",6000);
        var word=ClassicLayout.create(line,1280,420,s->s.length()*.6).words().getFirst();
        var p=ClassicMotion.Profile.NORMAL;double end=ClassicMotion.renderEnd(line);
        assertEquals(0,ClassicMotion.word(word,p,.5,end).opacity());
        var atPeak=ClassicMotion.word(word,p,1.25,end);
        assertTrue(atPeak.scale()>word.scale()*1.4,"Original underdamped spring overshoots");
        assertEquals(1,atPeak.opacity());
        var first=word.segment().glyphs().getFirst();var last=word.segment().glyphs().getLast();
        assertTrue(ClassicMotion.glow(word.segment(),first,p,1.3,end)>0);
        assertEquals(0,ClassicMotion.glow(word.segment(),last,p,1.3,end));
        assertEquals(0,ClassicMotion.glow(word.segment(),first,p,8,end));
        ClassicMotion.word(word,p,30,end);
        assertEquals(atPeak,ClassicMotion.word(word,p,1.25,end),"Backward seeks must not retain spring state");
        assertEquals(.82,ClassicMotion.word(word,p,15,end).opacity(),1e-6);
    }
    @Test void veryShortLinesRemainVisibleAndExitWithoutLeavingFrozenText() {
        for(long duration:new long[]{50,130,3000}) {
            var line=line("go",duration);var p=ClassicMotion.Profile.of(line);
            assertEquals(duration==50?ClassicMotion.Profile.INSTANT:duration==130?ClassicMotion.Profile.FAST:ClassicMotion.Profile.NORMAL,p);
            double end=ClassicMotion.renderEnd(line);
            assertTrue(end>=line.end());
            assertTrue(ClassicMotion.line(line,line.start()+.04,end).opacity()>0);
            assertEquals(0,ClassicMotion.line(line,end+p.exit+.01,end).opacity());
        }
    }
    @Test void repeatedChorusUsesFoliasFrequencyRuleAndFloatIsPeriodic() {
        var a=new LyricLine(1000,"We shine");
        var b=new LyricLine(4000,"Walk through the rain");
        var d=new LyricLine(7000,"We shine");
        assertEquals(java.util.Set.of("We shine"),ClassicScene.chorusLines(new LyricTimeline(List.of(a,b,d),11)));
        assertTrue(ClassicScene.chorusLines(new LyricTimeline(List.of(a,b),8)).isEmpty());
        assertEquals(ClassicMotion.breathing(1.25,false),ClassicMotion.breathing(8.25,false),1e-8);
        assertEquals(ClassicMotion.breathing(1.25,true),ClassicMotion.breathing(8.25,true),1e-8);
    }
}
