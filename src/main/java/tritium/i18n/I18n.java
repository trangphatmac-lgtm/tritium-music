package tritium.i18n;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/** UTF-8 catalogs with explicit keys; user-provided music metadata is never translated. */
public final class I18n {
    private static final Map<Language, Properties> CATALOGS = loadCatalogs();
    private static volatile Supplier<String> languageSource = () -> "zh-CN";

    private I18n() { }

    public static void bindLanguage(Supplier<String> source) {
        languageSource = java.util.Objects.requireNonNull(source);
    }

    public static Language language() {
        return Language.fromTag(languageSource.get());
    }

    public static String text(String key, Object... arguments) {
        return text(language(), key, arguments);
    }

    public static String text(Language language, String key, Object... arguments) {
        String pattern = CATALOGS.get(language).getProperty(key,
                CATALOGS.get(Language.SIMPLIFIED_CHINESE).getProperty(key, key));
        return arguments.length == 0 ? pattern : String.format(language.locale(), pattern, arguments);
    }

    public static String songCount(long count) {
        return count == 1 ? text("playlist.one") : text("playlist.count", count);
    }

    private static Map<Language, Properties> loadCatalogs() {
        Map<Language, Properties> catalogs = new EnumMap<>(Language.class);
        for (Language language : Language.values()) {
            String path = "/tritium/i18n/" + language.tag() + ".properties";
            try (var stream = I18n.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing language catalog: " + path);
                Properties catalog = new Properties();
                catalog.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                catalogs.put(language, catalog);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read language catalog: " + path, e);
            }
        }
        return Map.copyOf(catalogs);
    }
}
