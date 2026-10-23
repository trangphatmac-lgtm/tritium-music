package tritium.desktop.export;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tritium.ncm.music.dto.Artist;
import tritium.ncm.music.dto.Music;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Pure serialization of a snapshot: no playback, network or UI side effects. */
public final class PlaylistExporter {
    private PlaylistExporter() { }

    public enum Format {
        HTML("HTML", "html"), MARKDOWN("Markdown", "md"), TEXT("纯文本", "txt"),
        JSON("JSON", "json"), CSV("CSV", "csv");
        public final String label, extension;
        Format(String label, String extension) { this.label = label; this.extension = extension; }
    }

    public enum Field {
        NAME("歌名", "name"), ARTIST("歌手", "artist"), ID("网易云 ID", "id"),
        URL("网易云 URL", "url"), DURATION("时长", "duration");
        public final String label, key;
        Field(String label, String key) { this.label = label; this.key = key; }
    }

    public record Playlist(String name, List<Music> songs) {
        public Playlist {
            name = Objects.requireNonNullElse(name, "未命名歌单");
            songs = List.copyOf(songs);
        }
    }

    public static String serialize(List<Playlist> playlists, Format format, Set<Field> selected) {
        if (playlists.isEmpty()) throw new IllegalArgumentException("请至少选择一个歌单");
        if (selected.isEmpty()) throw new IllegalArgumentException("请至少选择一项导出信息");
        List<Field> fields = Arrays.stream(Field.values()).filter(selected::contains).toList();
        if (format == Format.JSON) return json(playlists, fields);
        StringBuilder out = new StringBuilder();
        if (format == Format.HTML) out.append("<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>歌单导出</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:32px;color:#222}"
                + "table{border-collapse:collapse;width:100%;margin-bottom:32px}"
                + "th,td{border:1px solid #ddd;padding:10px;text-align:left;white-space:pre-wrap}"
                + "th{background:#f5f5f5}a{color:#1769aa}</style></head><body><h1>歌单导出</h1>\n");
        if (format == Format.CSV) {
            // BOM allows desktop spreadsheet applications to recognize Chinese UTF-8 text.
            out.append('\uFEFF');
            List<String> header = new ArrayList<>(List.of("歌单"));
            fields.forEach(field -> header.add(field.label));
            csvRow(out, header);
        }
        for (Playlist playlist : playlists) {
            switch (format) {
                case HTML -> {
                    out.append("<h2>").append(html(playlist.name())).append("</h2><p>")
                            .append(playlist.songs().size()).append(" 首歌曲</p><table><thead><tr>");
                    fields.forEach(field -> out.append("<th>").append(field.label).append("</th>"));
                    out.append("</tr></thead><tbody>\n");
                }
                case MARKDOWN -> {
                    out.append("## ").append(markdown(playlist.name())).append("\n\n")
                            .append(playlist.songs().size()).append(" 首歌曲\n\n");
                    markdownRow(out, fields.stream().map(field -> field.label).toList());
                    markdownRow(out, fields.stream().map(field -> "---").toList());
                }
                case TEXT -> out.append("歌单：").append(singleLine(playlist.name())).append("（")
                        .append(playlist.songs().size()).append(" 首歌曲）\n")
                        .append(fields.stream().map(field -> field.label).collect(Collectors.joining("\t"))).append('\n');
                default -> { }
            }
            for (Music song : playlist.songs()) {
                List<String> values = fields.stream().map(field -> value(song, field)).toList();
                switch (format) {
                    case HTML -> {
                        out.append("<tr>");
                        for (int i = 0; i < fields.size(); i++) {
                            String escaped = html(values.get(i));
                            out.append("<td>");
                            if (fields.get(i) == Field.URL && !escaped.isEmpty())
                                out.append("<a href=\"").append(escaped).append("\">").append(escaped).append("</a>");
                            else out.append(escaped);
                            out.append("</td>");
                        }
                        out.append("</tr>\n");
                    }
                    case MARKDOWN -> markdownRow(out, values);
                    case TEXT -> out.append(values.stream().map(PlaylistExporter::singleLine)
                            .collect(Collectors.joining("\t"))).append('\n');
                    case CSV -> {
                        List<String> row = new ArrayList<>(List.of(playlist.name()));
                        row.addAll(values);
                        csvRow(out, row);
                    }
                    default -> { }
                }
            }
            // Keep empty playlists visible in flat CSV exports as well.
            if (format == Format.CSV && playlist.songs().isEmpty()) {
                List<String> row = new ArrayList<>(List.of(playlist.name()));
                fields.forEach(field -> row.add(""));
                csvRow(out, row);
            }
            if (format == Format.HTML) out.append("</tbody></table>\n");
            else if (format != Format.CSV) out.append('\n');
        }
        if (format == Format.HTML) out.append("</body></html>\n");
        return out.toString();
    }

    private static String json(List<Playlist> playlists, List<Field> fields) {
        JsonArray result = new JsonArray();
        for (Playlist playlist : playlists) {
            JsonObject item = new JsonObject();
            item.addProperty("name", playlist.name());
            JsonArray songs = new JsonArray();
            for (Music song : playlist.songs()) {
                JsonObject entry = new JsonObject();
                fields.forEach(field -> entry.addProperty(field.key, value(song, field)));
                songs.add(entry);
            }
            item.add("songs", songs);
            result.add(item);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n";
    }

    private static String value(Music song, Field field) {
        return switch (field) {
            case NAME -> Objects.requireNonNullElse(song.getName(), "");
            case ARTIST -> song.getArtists() == null ? "" : song.getArtists().stream()
                    .filter(Objects::nonNull).map(Artist::getName).filter(Objects::nonNull)
                    .collect(Collectors.joining(" / "));
            case ID -> song.getId() > 0 ? Long.toString(song.getId()) : "";
            case URL -> song.getId() > 0 ? "https://music.163.com/song?id=" + song.getId() : "";
            case DURATION -> {
                long seconds = Math.max(0, song.getDuration()) / 1000;
                yield seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                        : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
            }
        };
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String markdown(String text) {
        String escaped = html(text).replace("\\", "\\\\");
        for (String symbol : List.of("|", "*", "_", "[", "]", "`", "#", "!", "~"))
            escaped = escaped.replace(symbol, "\\" + symbol);
        return escaped.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }

    private static String singleLine(String text) { return text.replaceAll("[\\r\\n\\t]+", " "); }

    private static void markdownRow(StringBuilder out, List<String> cells) {
        out.append("| ").append(cells.stream().map(PlaylistExporter::markdown).collect(Collectors.joining(" | "))).append(" |\n");
    }

    private static void csvRow(StringBuilder out, List<String> cells) {
        out.append(cells.stream().map(cell -> {
            // Quoting alone does not prevent formula evaluation when CSV is opened in Excel.
            String trimmed = cell.stripLeading();
            if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0
                    || cell.startsWith("\t") || cell.startsWith("\r") || cell.startsWith("\n")) cell = "'" + cell;
            return "\"" + cell.replace("\"", "\"\"") + "\"";
        }).collect(Collectors.joining(","))).append("\r\n");
    }

    /** Stage beside the destination so a failed export cannot leave a truncated existing file. */
    public static void write(Path destination, String contents, boolean replaceExisting) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), ".tritium-export-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            if (replaceExisting) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else Files.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
}
