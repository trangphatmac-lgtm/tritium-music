package tritium.i18n;

import java.util.Arrays;
import java.util.Locale;

/** Stable preference values and native names, independent of the selected UI language. */
public enum Language {
    SIMPLIFIED_CHINESE("zh-CN", "简体中文"),
    TRADITIONAL_CHINESE("zh-TW", "繁體中文"),
    ENGLISH("en", "English");

    private final String tag;
    private final String nativeName;

    Language(String tag, String nativeName) {
        this.tag = tag;
        this.nativeName = nativeName;
    }

    public String tag() { return tag; }
    public String nativeName() { return nativeName; }
    public Locale locale() { return Locale.forLanguageTag(tag); }

    public static Language fromTag(String tag) {
        return Arrays.stream(values()).filter(language -> language.tag.equals(tag))
                .findFirst().orElse(SIMPLIFIED_CHINESE);
    }

    public static String[] tags() {
        return Arrays.stream(values()).map(Language::tag).toArray(String[]::new);
    }
}
