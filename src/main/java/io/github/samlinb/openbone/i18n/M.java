package io.github.samlinb.openbone.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

/**
 * Central access point for all user-facing text. Bundles live under {@code i18n/messages};
 * English is the default, Simplified Chinese (zh-CN) and Traditional Chinese (zh-TW) are supported.
 * Resolution order: {@code -Dopenbone.lang=} system property, stored user preference, English.
 */
public final class M {
    public static final String LANG_SYSTEM_PROPERTY = "openbone.lang";
    public static final Locale EN = Locale.ENGLISH;
    public static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
    public static final Locale ZH_TW = Locale.TRADITIONAL_CHINESE;

    private static final String BUNDLE = "i18n.messages";
    private static final String PREF_NODE = "io/github/samlinb/openbone";
    private static final String PREF_KEY = "language";

    private static final Locale LOCALE = resolveLocale();
    private static final ResourceBundle RESOURCES = ResourceBundle.getBundle(BUNDLE, LOCALE);

    private M() {
    }

    public static String m(String key) {
        try {
            return RESOURCES.getString(key);
        } catch (MissingResourceException missing) {
            return "!" + key + "!";
        }
    }

    public static String m(String key, Object... args) {
        return MessageFormat.format(m(key), args);
    }

    public static Locale locale() {
        return LOCALE;
    }

    /** Supported UI languages in menu order. */
    public static Locale[] supported() {
        return new Locale[]{EN, ZH_CN, ZH_TW};
    }

    public static String tag(Locale locale) {
        if (ZH_TW.equals(locale)) return "zh-TW";
        if (ZH_CN.equals(locale)) return "zh-CN";
        return "en";
    }

    public static void storePreference(Locale locale) {
        Preferences.userRoot().node(PREF_NODE).put(PREF_KEY, tag(locale));
    }

    private static Locale resolveLocale() {
        String tag = System.getProperty(LANG_SYSTEM_PROPERTY);
        if (tag == null || tag.isBlank()) tag = Preferences.userRoot().node(PREF_NODE).get(PREF_KEY, null);
        return parse(tag);
    }

    private static Locale parse(String tag) {
        if (tag == null) return EN;
        String normalized = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "zh-cn", "zh-hans", "zh" -> ZH_CN;
            case "zh-tw", "zh-hant", "zh-hk", "zh-mo" -> ZH_TW;
            default -> EN;
        };
    }
}
