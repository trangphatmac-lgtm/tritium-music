package tritium.screens.ncm.lyrics;

import org.junit.jupiter.api.Test;
import tritium.screens.ncm.LyricLine;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LyricTimelineTest {
    static LyricLine line(String text,long start,long duration){var l=new LyricLine(start,text);l.duration=duration;return l;}
    @Test void keepsWordTimingAcrossPunctuationAndMissingSubtitles(){
        var line=line("Hello, 世界!",1000,4000);
        line.words.add(new LyricLine.Word("Hello",1000,1000));line.words.add(new LyricLine.Word("世界",3000,1500));
        var timeline=new LyricTimeline(List.of(line),10);var parsed=timeline.line(0);
        assertEquals("Hello, 世界!",parsed.glyphs().stream().map(LyricTimeline.Glyph::text).reduce("",String::concat));
        assertEquals(3,parsed.glyphs().get(7).start());assertEquals("",parsed.translation());assertTrue(parsed.timedWords());
        assertEquals(-1,timeline.indexAt(.999));assertEquals(0,timeline.indexAt(1));
        assertEquals(0,parsed.characterProgress(0));assertEquals(10,parsed.characterProgress(8));
    }
    @Test void preservesGraphemesAndSeeksAcrossBreaksAndOutOfOrderLines(){
        assertEquals(3,LyricTimeline.split("Á👩‍🚀字").size());
        var blank=line("",0,1000);blank.isBreakLine=true;
        var timeline=new LyricTimeline(List.of(line("later",8000,1000),blank,line("early",2000,1000)),20);
        assertEquals(2,timeline.lines().size());assertEquals("early",timeline.line(0).text());
        assertEquals(0,timeline.indexAt(5));assertEquals(1,timeline.indexAt(8));assertNull(timeline.line(-1));
    }
    @Test void springsAreStableAndIndependentOfFrameSubdivision(){
        for(double[] parameters:new double[][]{{90,20},{100,20},{142/.82,28/.82}}){
            var a=new LyricMath.Spring(parameters[0],parameters[1]);var b=new LyricMath.Spring(parameters[0],parameters[1]);a.snap(0);b.snap(0);
            for(int i=0;i<10;i++)a.step(100,.1);for(int i=0;i<100;i++)b.step(100,.01);
            assertEquals(a.value,b.value,1e-7);assertTrue(Double.isFinite(a.velocity));
            a.snap(-500);assertEquals(-500,a.value);assertEquals(0,a.velocity);
        }
    }
    @Test void importedCatalogsHaveFiniteGeometryAndFullVariantCounts(){
        Map<String,Integer> sizes=Map.of("hanabi.json",50,"lumiere.json",100,"sonnet-geometry.json",100);
        sizes.forEach((name,size)->assertEquals(size,FoliaData.data(name).getAsJsonArray().size()));
        assertEquals(121,FoliaData.data("tempera-geometry.json").getAsJsonObject().entrySet().size());
        for(String file:List.of("hanabi-bursts.json","tempera-geometry.json","sonnet-geometry.json","diorama-surfaces.json"))finite(FoliaData.data(file));
    }
    @Test void kineticPosesRemainFiniteAtPlaybackBoundaries(){
        for(var item:FoliaData.data("hanabi.json").getAsJsonArray()){
            var text=item.getAsJsonObject().getAsJsonObject("text");
            for(double progress:new double[]{-1,0,.25,.5,.9,1,2}){
                var enter=HanabiMotion.enter(text.get("enter").getAsString(),progress,progress,733,-3,.5,false);
                var exit=HanabiMotion.exit(text.get("exit").getAsString(),progress,progress,733,-3,.5,true);
                var sing=HanabiMotion.sing(text.get("sing").getAsString(),progress,.001,733);
                for(var pose:List.of(enter,exit,sing))for(double value:new double[]{pose.x,pose.y,pose.sx,pose.sy,pose.rotation,pose.opacity})assertTrue(Double.isFinite(value));
                if(progress>=1){assertEquals(1,enter.opacity);assertEquals(0,exit.opacity);}
            }
        }
    }
    private void finite(com.google.gson.JsonElement element){
        if(element.isJsonArray())element.getAsJsonArray().forEach(this::finite);
        else if(element.isJsonObject())element.getAsJsonObject().entrySet().forEach(e->finite(e.getValue()));
        else if(element.isJsonPrimitive()&&element.getAsJsonPrimitive().isNumber())assertTrue(Double.isFinite(element.getAsDouble()));
    }
}
