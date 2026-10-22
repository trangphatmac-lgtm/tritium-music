package tritium.ncm.music.lyrics;

import com.ibm.icu.text.Transliterator;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Folia's 45/25/30 identity score and duration penalties, with a 75-point acceptance floor. */
public final class LyricMatchScore {
    private static final ThreadLocal<Transliterator> NORMALIZER = ThreadLocal.withInitial(() ->
            Transliterator.getInstance("Traditional-Simplified; Hiragana-Katakana; Katakana-Latin"));
    private static final Pattern BRACKETS = Pattern.compile("[\\(\\[（【]([^\\)\\]）】]+)[\\)\\]）】]");
    private static final Pattern VERSION = Pattern.compile("instrumental|inst|off\\s*vocal|karaoke|remix|mix|version|ver\\.?|cover|live|edit|arrange|伴奏|カラオケ|インスト|リミックス|remaster", Pattern.CASE_INSENSITIVE);

    private LyricMatchScore() {}
    public record Score(int value, boolean title, boolean artist, boolean album) {
        public boolean reliable() { return title && (artist || album) && value >= 75; }
    }

    static String normalize(String text) {
        return NORMALIZER.get().transliterate(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}]", "").replaceAll("\\s+", " ").trim();
    }

    private static String title(String text) {
        text = text.replaceAll("(?iu)[\\(\\[（【]\\s*(feat|featuring|ft)\\.?\\s+[^\\)\\]）】]+[\\)\\]）】]", "")
                .replaceAll("(?iu)\\b(feat|featuring|ft)\\.?\\s+.+$", "");
        return normalize(BRACKETS.matcher(text).replaceAll(match ->
                VERSION.matcher(match.group(1)).find() ? java.util.regex.Matcher.quoteReplacement(match.group()) : ""));
    }

    private static double similarity(String left, String right, UnaryOperator<String> normalize) {
        String a = normalize.apply(left), b = normalize.apply(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (a.contains(b) || b.contains(a)) return (double) Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
        var aa = new HashSet<>(a.codePoints().boxed().toList());
        var bb = new HashSet<>(b.codePoints().boxed().toList());
        var union = new HashSet<>(aa);
        union.addAll(bb);
        aa.retainAll(bb);
        return (double) aa.size() / union.size();
    }

    private static List<String> artists(String text) {
        return Arrays.stream(text.split("(?i)[,&、/]|featuring|feat\\.?|ft\\.?|与"))
                .map(LyricMatchScore::normalize).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean artistMatches(String a, String b) {
        return a.equals(b) || (a.length() >= 3 && b.contains(a)) || (b.length() >= 3 && a.contains(b));
    }

    private static double artistSimilarity(String left, String right) {
        var a = artists(left); var b = artists(right);
        double raw = similarity(left, right, LyricMatchScore::normalize);
        if (a.isEmpty() || b.isEmpty()) return raw;
        double count = a.stream().filter(artist -> b.stream().anyMatch(other -> artistMatches(artist, other))).count();
        double tokens = count / Math.max(a.size(), b.size());
        return Math.max(raw, artistMatches(a.getFirst(), b.getFirst()) ? Math.max(tokens, .7) : tokens);
    }

    static long duration(long value) { return value > 43_200_000 ? value / 1000 : Math.max(0, value); }

    public static Score score(LyricSong target, LyricSong candidate) {
        double title = similarity(target.title(), candidate.title(), LyricMatchScore::title);
        double artist = target.artist().isBlank() ? 1 : artistSimilarity(target.artist(), candidate.artist());
        boolean hasAlbum = !target.album().isBlank();
        double album = !hasAlbum ? 1 : similarity(target.album(), candidate.album(), LyricMatchScore::title);
        long a = duration(target.durationMs()), b = duration(candidate.durationMs()), diff = Math.abs(a - b);
        double multiplier = a == 0 || b == 0 ? .9 : diff <= 1000 ? 1 : diff <= 3000 ? .95 : diff <= 5000 ? .75 : diff <= 10000 ? .35 : .1;
        boolean titleHit = title >= .65, artistHit = artist >= .5, albumHit = hasAlbum && album >= .65;
        double identity = title * 45 + artist * 25 + album * 30;
        if (!titleHit || !(artistHit || albumHit)) identity = Math.min(identity, 74);
        return new Score((int) Math.round(identity * multiplier), titleHit, artistHit, albumHit);
    }

    public static LyricSong select(LyricSong target, List<LyricSong> candidates) {
        return candidates.stream().limit(10).filter(song -> score(target, song).reliable())
                .max(java.util.Comparator.comparingInt(song -> score(target, song).value())).orElse(null);
    }

    public static String searchTitle(LyricSong song) {
        String[] parts = song.title().trim().split("\\s+-\\s+", 2);
        return parts.length == 2 && normalize(parts[0]).replace(" ", "").equals(normalize(song.artist()).replace(" ", ""))
                ? parts[1].trim() : song.title().trim();
    }

    public static String query(LyricSong song) {
        String album = song.album().trim();
        if (album.length() > 48) album = album.replaceAll("\\s*※.*$", "").replaceAll("(?iu)\\s*(歌|歌唱|アーティスト|Artist)\\s*[:：]?\\s*.+$", "");
        if (album.length() > 48) album = album.substring(0, 48).trim();
        return String.join(" - ", List.of(searchTitle(song), song.artist().trim(), album).stream().filter(s -> !s.isBlank()).toList());
    }
}
