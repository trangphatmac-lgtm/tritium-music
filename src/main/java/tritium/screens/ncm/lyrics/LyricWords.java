package tritium.screens.ncm.lyrics;

import com.ibm.icu.text.BreakIterator;
import java.util.*;

/** Dictionary word boundaries for CJK and space-separated scripts; timing remains grapheme based. */
final class LyricWords {
    record Segment(String text,List<LyricTimeline.Glyph> glyphs) {
        double start(){return glyphs.getFirst().start();}
        double end(){return glyphs.getLast().end();}
    }
    static List<Segment> split(LyricTimeline.Line line){
        BreakIterator words=BreakIterator.getWordInstance(Locale.ROOT);words.setText(line.text());
        var result=new ArrayList<Segment>();int begin=words.first(),cursor=0,offset=0;
        for(int end=words.next();end!=BreakIterator.DONE;begin=end,end=words.next()){
            var glyphs=new ArrayList<LyricTimeline.Glyph>();
            while(cursor<line.glyphs().size()&&offset<end){var glyph=line.glyphs().get(cursor++);glyphs.add(glyph);offset+=glyph.text().length();}
            if(!glyphs.isEmpty()){
                String text=line.text().substring(begin,end);
                boolean closing=text.codePoints().allMatch(ch->",.;:!?，。！？；：、…)]}»”’".indexOf(ch)>=0);
                if(closing&&!result.isEmpty()&&!result.getLast().text().isBlank()){
                    var previous=result.removeLast();var joined=new ArrayList<>(previous.glyphs());joined.addAll(glyphs);
                    result.add(new Segment(previous.text()+text,List.copyOf(joined)));
                }else result.add(new Segment(text,List.copyOf(glyphs)));
            }
        }
        return result;
    }
}
