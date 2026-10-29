package tritium.minecraft.runtime.lyric;

import tritium.minecraft.protocol.PlaybackState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable, GL-free timeline for Gale-style world lyrics. Word timestamps are absolute milliseconds. */
public final class WorldLyricTimeline {
    private static final Pattern CREDIT = Pattern.compile("^\\s*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|发行|统筹|词|曲|OP|SP|Lyricist|Lyrics by|Composer|Composed by|Producer|Produced by|Arranger|Arranged by)\\s*[:：]", Pattern.CASE_INSENSITIVE);
    public final List<Entry> lines;

    public WorldLyricTimeline(List<PlaybackState.Line> input, boolean splitLines) {
        List<Entry> kept = new ArrayList<Entry>();
        if (input != null) for (PlaybackState.Line source : input) {
            if (source == null || source.placeholder) continue;
            String text = source.breakLine ? "" : safe(source.text).trim();
            if (CREDIT.matcher(text).find() || placeholder(text)) continue;
            List<Word> words = new ArrayList<Word>();
            if (!source.breakLine && source.words != null) for (PlaybackState.Word w : source.words) {
                if (w != null && w.text != null && !w.text.isEmpty()) words.add(new Word(w.timestamp, w.duration, w.text));
            }
            kept.add(new Entry(source.timestamp, text, words));
        }
        Collections.sort(kept, Comparator.comparingLong(e -> e.startMs));
        lines = Collections.unmodifiableList(splitLines ? split(kept) : kept);
    }

    public int indexAt(long positionMs) {
        int low = 0, high = lines.size() - 1, result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).startMs <= positionMs) { result = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return result;
    }

    public long nextMs(int index) {
        return index + 1 < lines.size() ? lines.get(index + 1).startMs : lines.get(index).startMs + 5000;
    }

    /** Times correspond to non-whitespace UTF-16 characters, matching the original 3D animation. */
    public static long[] characterTimes(Entry line, long nextMs) {
        List<Long> times = new ArrayList<Long>();
        if (!line.words.isEmpty()) {
            for (Word word : line.words) {
                long at = Math.max(0, word.startMs - line.startMs);
                for (int i = 0; i < word.text.length(); i++)
                    if (!Character.isWhitespace(word.text.charAt(i))) times.add(at);
            }
        } else {
            List<String> tokens = tokens(line.text);
            double span = Math.min(Math.max(nextMs - line.startMs, 300) * .75, tokens.size() * 330.0);
            for (int i = 0; i < tokens.size(); i++) {
                long at = (long) (span * i / Math.max(1, tokens.size()));
                for (int j = 0; j < tokens.get(i).length(); j++) times.add(at);
            }
        }
        long[] result = new long[times.size()];
        for (int i = 0; i < result.length; i++) result[i] = times.get(i);
        return result;
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<String>(); StringBuilder token = new StringBuilder(); int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { flush(token, result); cjk = 0; }
            else if (c >= '\u2e80') {
                if (token.length() > 0 && cjk == 0) flush(token, result);
                token.append(c);
                if (++cjk >= 2) { flush(token, result); cjk = 0; }
            } else {
                if (cjk > 0) { flush(token, result); cjk = 0; }
                token.append(c);
            }
        }
        flush(token, result); return result;
    }

    private static void flush(StringBuilder builder, List<String> out) {
        if (builder.length() > 0) out.add(builder.toString()); builder.setLength(0);
    }

    private static List<Entry> split(List<Entry> input) {
        List<Entry> result = new ArrayList<Entry>();
        for (int i = 0; i < input.size(); i++) {
            Entry line = input.get(i);
            Long next = i + 1 < input.size() ? input.get(i + 1).startMs : null;
            List<Segment> segments = segments(line, next);
            if (segments.size() <= 1) { result.add(line); continue; }
            List<Segment> merged = new ArrayList<Segment>();
            for (Segment part : segments) {
                Segment previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (previous != null && (count(part.text) < 2 || count(previous.text) < 2 || part.startMs - previous.startMs < 500)) previous.join(part);
                else merged.add(part);
            }
            if (merged.size() > 1 && next != null && next - merged.get(merged.size() - 1).startMs < 500) {
                Segment last = merged.remove(merged.size() - 1); merged.get(merged.size() - 1).join(last);
            }
            if (merged.size() <= 1) result.add(line);
            else for (Segment part : merged) result.add(new Entry(part.startMs, part.text, part.words));
        }
        Collections.sort(result, Comparator.comparingLong(e -> e.startMs)); return result;
    }

    private static List<Segment> segments(Entry line, Long nextMs) {
        if (!line.words.isEmpty()) {
            StringBuilder full = new StringBuilder(); for (Word word : line.words) full.append(word.text);
            List<Integer> cuts = cuts(full.toString());
            if (cuts.size() <= 1) return Collections.emptyList();
            List<Segment> parts = new ArrayList<Segment>();
            for (int ignored : cuts) parts.add(new Segment("", Long.MAX_VALUE));
            int position = 0;
            for (Word word : line.words) {
                int first = -1;
                for (int i = 0; i < word.text.length(); i++) if (!Character.isWhitespace(word.text.charAt(i))) { first = i; break; }
                int at = position + Math.max(first, 0), target = 0;
                for (int i = cuts.size() - 1; i >= 0; i--) if (cuts.get(i) <= at) { target = i; break; }
                Segment segment = parts.get(target); segment.words.add(word);
                if (first >= 0) segment.startMs = Math.min(segment.startMs, word.startMs);
                position += word.text.length();
            }
            List<Segment> kept = new ArrayList<Segment>();
            for (Segment part : parts) {
                StringBuilder text = new StringBuilder(); for (Word word : part.words) text.append(word.text);
                part.text = text.toString().trim(); if (!part.text.isEmpty()) kept.add(part);
            }
            return kept;
        }
        List<Integer> cuts = cuts(line.text);
        if (cuts.size() <= 1) return Collections.emptyList();
        List<Segment> result = new ArrayList<Segment>(); int total = 0;
        for (int i = 0; i < cuts.size(); i++) {
            String text = line.text.substring(cuts.get(i), i + 1 < cuts.size() ? cuts.get(i + 1) : line.text.length()).trim();
            total += count(text); result.add(new Segment(text, 0));
        }
        long end = nextMs == null ? line.startMs + total * 300L : nextMs;
        double span = Math.max(0, end - line.startMs) * .85; int before = 0;
        for (Segment part : result) { part.startMs = line.startMs + (long) (span * before / Math.max(1, total)); before += count(part.text); }
        return result;
    }

    private static List<Integer> cuts(String text) {
        List<Integer> starts = new ArrayList<Integer>(); starts.add(0);
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\u3000') { i++; continue; }
            int j = i; boolean full = false;
            while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\u3000')) { full |= text.charAt(j) == '\u3000'; j++; }
            int before = i - 1; while (before >= 0 && Character.isWhitespace(text.charAt(before))) before--;
            if (before >= 0 && j < text.length() && (full || cjk(text.charAt(before)) && cjk(text.charAt(j)))) starts.add(j);
            i = j;
        }
        return starts;
    }

    private static boolean cjk(char c) { return c >= '\u2e80' && c != '\u3000'; }
    private static int count(String text) { int n = 0; for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) n++; return n; }
    private static String safe(String text) { return text == null ? "" : text; }
    private static boolean placeholder(String text) {
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) normalized.append(text.charAt(i));
        String t = normalized.toString().toLowerCase(Locale.ROOT);
        return t.equals("纯音乐") || t.startsWith("纯音乐请欣赏") || t.contains("没有填词的纯音乐") || t.equals("instrumental") || t.equals("纯音乐请您欣赏");
    }

    public static final class Entry {
        public final long startMs;
        public final String text;
        public final List<Word> words;
        Entry(long startMs, String text, List<Word> words) {
            this.startMs = startMs; this.text = text; this.words = Collections.unmodifiableList(new ArrayList<Word>(words));
        }
    }
    public static final class Word {
        public final long startMs, durationMs; public final String text;
        Word(long startMs, long durationMs, String text) { this.startMs = startMs; this.durationMs = durationMs; this.text = text; }
    }
    private static final class Segment {
        String text; long startMs; final List<Word> words = new ArrayList<Word>();
        Segment(String text, long startMs) { this.text = text; this.startMs = startMs; }
        void join(Segment other) { text += " " + other.text; words.addAll(other.words); }
    }
}
