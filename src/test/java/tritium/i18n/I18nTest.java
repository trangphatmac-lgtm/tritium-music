package tritium.i18n;

import org.junit.jupiter.api.Test;
import tritium.management.FontManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class I18nTest {
    private Properties catalog(Language language) throws Exception {
        Properties properties = new Properties();
        try (var stream = getClass().getResourceAsStream("/tritium/i18n/" + language.tag() + ".properties")) {
            assertNotNull(stream);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    void catalogsHaveMatchingKeysAndFormatArguments() throws Exception {
        Properties reference = catalog(Language.SIMPLIFIED_CHINESE);
        Pattern arguments = Pattern.compile("%(?:\\d+\\$)?[-0-9.]*[sd]");
        for (Language language : Language.values()) {
            Properties translated = catalog(language);
            assertEquals(reference.stringPropertyNames(), translated.stringPropertyNames(), language.tag());
            for (String key : reference.stringPropertyNames()) {
                String value = translated.getProperty(key);
                assertFalse(value.isBlank(), key);
                var expected = arguments.matcher(reference.getProperty(key)).results().map(match -> match.group()).toList();
                var actual = arguments.matcher(value).results().map(match -> match.group()).toList();
                assertEquals(expected, actual, language.tag() + ": " + key);
                Object[] values = actual.stream().map(format -> format.endsWith("d") ? 12 : "example").toArray();
                assertDoesNotThrow(() -> I18n.text(language, key, values), key);
            }
        }
    }

    @Test
    void resolvesUtf8NativeNamesAndSafeFallbacks() {
        assertEquals("简体中文", Language.fromTag("zh-CN").nativeName());
        assertEquals("繁體中文", Language.fromTag("zh-TW").nativeName());
        assertEquals("English", Language.fromTag("en").nativeName());
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromTag("unknown"));
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromTag(null));
        assertEquals("設定", I18n.text(Language.TRADITIONAL_CHINESE, "settings.title"));
        assertEquals("Settings", I18n.text(Language.ENGLISH, "settings.title"));
        assertEquals("missing.key", I18n.text(Language.ENGLISH, "missing.key"));
        assertEquals("Freed 12 MB", I18n.text(Language.ENGLISH, "cache.freed", "12 MB"));
    }

    @Test
    void bundledFontsCoverAllCatalogCharacters() throws Exception {
        var regular = FontManager.readFont("/tritium/fonts/pf_normal.ttf");
        var bold = FontManager.readFont("/tritium/fonts/pf_middleblack.ttf");
        var latin = FontManager.readFont("/tritium/fonts/sfregular.otf");
        var latinBold = FontManager.readFont("/tritium/fonts/sfbold.otf");
        for (Language language : Language.values()) {
            for (Object value : catalog(language).values()) {
                for (int codePoint : value.toString().codePoints().toArray()) {
                    assertTrue(regular.canDisplay(codePoint) || latin.canDisplay(codePoint),
                            () -> "Missing regular glyph: " + Character.toString(codePoint));
                    assertTrue(bold.canDisplay(codePoint) || latinBold.canDisplay(codePoint),
                            () -> "Missing bold glyph: " + Character.toString(codePoint));
                }
            }
        }
    }
}
