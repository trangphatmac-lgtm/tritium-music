package tritium.ncm.music.lyrics;

import com.google.gson.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import tritium.screens.ncm.LyricLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Converts provider formats into the player's millisecond timeline, including secondary tracks. */
public final class OnlineLyricParser {
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern ANGLE = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern QRC = Pattern.compile("\\((\\d+),(\\d+)(?:,\\d+)?\\)");
    private static final Pattern KRC = Pattern.compile("<(\\d+),(\\d+)(?:,\\d+)?>");
    private OnlineLyricParser() {}

    public static LyricDocument netease(JsonObject json) {
        String lrc = track(json, "lrc"), yrc = track(json, "yrc");
        boolean pure = bool(json, "nolyric") || lrc.contains("纯音乐，请欣赏") || lrc.contains("純音樂，請欣賞");
        var lines = timed(yrc, "yrc");
        boolean words = !lines.isEmpty();
        if (!words) lines = lrc(lrc);
        attach(lines, parseText(words ? first(track(json, "ytlrc"), track(json, "tlyric")) : track(json, "tlyric")), false);
        attach(lines, parseText(words ? first(track(json, "yromalrc"), track(json, "romalrc")) : track(json, "romalrc")), true);
        return pure ? new LyricDocument(List.of(), true) : LyricDocument.fromLines(lines, false);
    }

    public static LyricDocument parse(String format, String text, String translation, String romanization) throws Exception {
        if (format.equals("ttml")) return ttml(text);
        text = unwrapQrc(text);
        var lines = text.stripLeading().startsWith("WEBVTT") ? vtt(text)
                : format.equals("qrc") || format.equals("krc") || format.equals("yrc") ? timed(text, format) : lrc(text);
        if (lines.isEmpty()) lines = lrc(text);
        attach(lines, parseText(unwrapQrc(translation)), false);
        attach(lines, parseText(unwrapQrc(romanization)), true);
        if (format.equals("krc")) embeddedKrcTracks(text, lines);
        return LyricDocument.fromLines(lines, false);
    }

    private static List<LyricLine> parseText(String text) {
        if (text.stripLeading().startsWith("WEBVTT")) return vtt(text);
        var lines = timed(text, "qrc");
        return lines.isEmpty() ? lrc(text) : lines;
    }

    private static List<LyricLine> vtt(String text) {
        var result = new ArrayList<LyricLine>();
        var cue = Pattern.compile("(?m)^([\\d:.]+)\\s+-->\\s+([\\d:.]+)[^\\r\\n]*[\\r\\n]+(.*?)(?=\\r?\\n\\r?\\n|\\z)", Pattern.DOTALL).matcher(text);
        while (cue.find()) {
            String body = cue.group(3).replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
            if (body.isBlank()) continue;
            var line = new LyricLine(clock(cue.group(1)), body);
            line.duration = Math.max(0, clock(cue.group(2)) - line.timestamp);
            result.add(line);
        }
        return result;
    }

    private static long timestamp(MatchResult m) {
        String fraction = m.group(3);
        return Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000
                + (fraction == null ? 0 : Long.parseLong((fraction + "000").substring(0, 3)));
    }

    private static List<LyricLine> lrc(String input) {
        var result = new ArrayList<LyricLine>();
        long offset = 0;
        var offsetMatch = Pattern.compile("(?i)\\[offset:([+-]?\\d+)]").matcher(input);
        if (offsetMatch.find()) offset = Long.parseLong(offsetMatch.group(1));
        for (String raw : input.replace("\\n", "\n").split("\\R")) {
            var tags = TIME.matcher(raw);
            var starts = new ArrayList<Long>();
            int end = 0;
            while (tags.find() && raw.substring(end, tags.start()).isBlank()) {
                starts.add(timestamp(tags)); end = tags.end();
            }
            String body = raw.substring(end).trim();
            if (body.isBlank()) continue;
            for (long start : starts) {
                var line = new LyricLine(Math.max(0, start + offset), body);
                var wordTags = ANGLE.matcher(body);
                var matches = wordTags.results().toList();
                if (!matches.isEmpty()) {
                    var full = new StringBuilder();
                    for (int i = 0; i < matches.size(); i++) {
                        var tag = matches.get(i);
                        String word = body.substring(tag.end(), i + 1 < matches.size() ? matches.get(i + 1).start() : body.length());
                        if (word.isEmpty()) continue;
                        long time = timestamp(tag) + offset;
                        long next = i + 1 < matches.size() ? timestamp(matches.get(i + 1)) + offset : time;
                        line.words.add(new LyricLine.Word(word, Math.max(0, time), Math.max(0, next - time)));
                        full.append(word);
                    }
                    line.lyric = full.toString();
                    if (!line.words.isEmpty()) {
                        var last = line.words.getLast();
                        line.duration = Math.max(0, last.timestamp + last.duration - line.timestamp);
                    }
                }
                if (!line.lyric.isBlank()) result.add(line);
            }
        }
        result.sort(Comparator.comparingLong(l -> l.timestamp));
        return result;
    }

    private static List<LyricLine> timed(String text, String format) {
        var result = new ArrayList<LyricLine>();
        for (String raw : text.replace("\\n", "\n").replace("\uFEFF", "").split("\\R")) {
            var match = LINE.matcher(raw.trim());
            if (!match.matches()) continue;
            try {
                var line = new LyricLine(Long.parseLong(match.group(1)), "");
                line.duration = Long.parseLong(match.group(2));
                String body = match.group(3);
                var tags = (format.equals("krc") ? KRC : QRC).matcher(body).results().toList();
                boolean leading = format.equals("qrc") && !tags.isEmpty() && !body.substring(0, tags.getFirst().start()).isBlank();
                var full = new StringBuilder();
                for (int i = 0; i < tags.size(); i++) {
                    var tag = tags.get(i);
                    String word = leading ? body.substring(i == 0 ? 0 : tags.get(i - 1).end(), tag.start())
                            : body.substring(tag.end(), i + 1 == tags.size() ? body.length() : tags.get(i + 1).start());
                    if (word.isEmpty()) continue;
                    long start = Long.parseLong(tag.group(1)) + (format.equals("krc") ? line.timestamp : 0);
                    long duration = Long.parseLong(tag.group(2));
                    line.words.add(new LyricLine.Word(word, start, duration));
                    full.append(word);
                }
                line.lyric = full.toString();
                if (!line.lyric.isBlank()) result.add(line);
            } catch (NumberFormatException ignored) {
                // A malformed line must not discard the other usable lines.
            }
        }
        result.sort(Comparator.comparingLong(l -> l.timestamp));
        return result;
    }

    private static void attach(List<LyricLine> lines, List<LyricLine> track, boolean roman) {
        for (var line : lines) {
            var closest = track.stream().filter(t -> Math.abs(t.timestamp - line.timestamp) <= 500)
                    .min(Comparator.comparingLong(t -> Math.abs(t.timestamp - line.timestamp))).orElse(null);
            if (closest != null) {
                if (roman) line.romanizationText = closest.lyric;
                else line.translationText = closest.lyric;
            }
        }
    }

    private static void embeddedKrcTracks(String text, List<LyricLine> lines) {
        var tag = Pattern.compile("\\[language:([^]]+)]").matcher(text);
        if (!tag.find()) return;
        try {
            var json = new JsonParser().parse(new String(Base64.getDecoder().decode(tag.group(1).trim()), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : json.getAsJsonArray("content")) {
                var track = entry.getAsJsonObject();
                int type = track.get("type").getAsInt();
                var content = track.getAsJsonArray("lyricContent");
                for (int i = 0; i < Math.min(lines.size(), content.size()); i++) {
                    var value = content.get(i);
                    StringBuilder joined = new StringBuilder();
                    if (value.isJsonArray()) value.getAsJsonArray().forEach(part -> joined.append(part.getAsString()));
                    else joined.append(value.getAsString());
                    if (type == 0) lines.get(i).romanizationText = joined.toString().trim();
                    if (type == 1) lines.get(i).translationText = joined.toString().trim();
                }
            }
        } catch (RuntimeException ignored) { /* Malformed optional language metadata. */ }
    }

    private static Document xml(String text) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder.parse(new InputSource(new StringReader(text.replace("\uFEFF", ""))));
    }

    private static String unwrapQrc(String text) {
        if (!text.contains("LyricContent=")) return text;
        try {
            // XML parsers normalize literal newlines in attributes to spaces. QQ puts the
            // whole multiline QRC in this attribute, so preserve those line separators first.
            String preserved = Pattern.compile("(LyricContent=\")([^\"]*)(\")", Pattern.DOTALL).matcher(text)
                    .replaceAll(match -> Matcher.quoteReplacement(match.group(1)
                            + match.group(2).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "&#10;") + match.group(3)));
            var nodes = xml(preserved).getElementsByTagName("Lyric_1");
            return nodes.getLength() == 0 ? text : ((Element) nodes.item(0)).getAttribute("LyricContent");
        } catch (Exception ignored) { return text; }
    }

    private static String role(Element element) {
        return element.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role");
    }

    private static boolean secondary(Element element) {
        return role(element).equals("x-translation") || role(element).equals("x-roman");
    }

    private static long clock(String value) {
        if (value.isBlank()) return 0;
        if (value.endsWith("ms")) return Math.round(Double.parseDouble(value.substring(0, value.length() - 2)));
        if (value.endsWith("s")) return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1000);
        double seconds = 0;
        for (String part : value.split(":")) seconds = seconds * 60 + Double.parseDouble(part);
        return Math.round(seconds * 1000);
    }

    private static LyricDocument ttml(String text) throws Exception {
        var document = xml(text);
        var result = new ArrayList<LyricLine>();
        var paragraphs = document.getElementsByTagNameNS("*", "p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            var p = (Element) paragraphs.item(i);
            readTtmlLine(p, result);
            var spans = p.getElementsByTagNameNS("*", "span");
            for (int j = 0; j < spans.getLength(); j++) {
                var span = (Element) spans.item(j);
                if (role(span).equals("x-bg")) readTtmlLine(span, result);
            }
        }
        result.sort(Comparator.comparingLong(line -> line.timestamp));
        return LyricDocument.fromLines(result, false);
    }

    private static void readTtmlLine(Element root, List<LyricLine> result) {
        long start = clock(root.getAttribute("begin"));
        long end = root.hasAttribute("end") ? clock(root.getAttribute("end")) : start + clock(root.getAttribute("dur"));
        var line = new LyricLine(start, "");
        line.duration = Math.max(0, end - start);
        var text = new StringBuilder();
        collectTtml(root, line, text);
        line.lyric = text.toString().trim();
        if (!root.hasAttribute("begin") && !line.words.isEmpty()) line.timestamp = line.words.getFirst().timestamp;
        if (!line.words.isEmpty()) {
            var last = line.words.getLast();
            line.duration = Math.max(line.duration, last.timestamp + last.duration - line.timestamp);
        }
        if (!line.lyric.isBlank()) result.add(line);
    }

    private static void collectTtml(Element parent, LyricLine line, StringBuilder text) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                if (role(element).equals("x-bg")) continue;
                if (secondary(element)) {
                    if (role(element).equals("x-translation")) line.translationText = element.getTextContent().trim();
                    else line.romanizationText = element.getTextContent().trim();
                } else if (element.hasAttribute("begin") && !hasTimedDescendants(element)) {
                    String word = primaryText(element);
                    long start = clock(element.getAttribute("begin"));
                    long end = element.hasAttribute("end") ? clock(element.getAttribute("end")) : start + clock(element.getAttribute("dur"));
                    String prefix = line.words.isEmpty() ? text.toString() : "";
                    line.words.add(new LyricLine.Word(prefix + word, start, Math.max(0, end - start)));
                    text.append(word);
                } else collectTtml(element, line, text);
            } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                String value = node.getNodeValue();
                // Formatting newlines are indentation; literal inter-span spaces are lyric text.
                if (value.isBlank() && (value.contains("\n") || value.contains("\r"))) continue;
                text.append(value);
                if (!value.isEmpty() && !line.words.isEmpty()) {
                    var last = line.words.removeLast();
                    line.words.add(new LyricLine.Word(last.word + value, last.timestamp, last.duration));
                }
            }
        }
    }

    private static boolean hasTimedDescendants(Element element) {
        var spans = element.getElementsByTagNameNS("*", "span");
        for (int i = 0; i < spans.getLength(); i++) {
            var span = (Element) spans.item(i);
            if (!secondary(span) && span.hasAttribute("begin")) return true;
        }
        return false;
    }

    private static String primaryText(Element element) {
        var text = new StringBuilder();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested) {
                if (!secondary(nested) && !role(nested).equals("x-bg")) text.append(primaryText(nested));
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) text.append(child.getNodeValue());
        }
        return text.toString();
    }

    private static String track(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject() ? string(json.getAsJsonObject(key), "lyric") : "";
    }
    static String string(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }
    private static boolean bool(JsonObject json, String key) { return json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsBoolean(); }
    private static String first(String a, String b) { return a.isBlank() ? b : a; }
}
