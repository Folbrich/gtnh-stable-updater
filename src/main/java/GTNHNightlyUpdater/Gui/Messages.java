package GTNHNightlyUpdater.Gui;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tiny i18n helper around a {@code gui.messages} {@link ResourceBundle} (German/English). Callers
 * use {@link #get(String)} for static text, and {@code String.format(Messages.get(key), args)} for
 * templated text — consistent with the {@code %s}/{@code %.0f} style already used throughout the GUI.
 */
public final class Messages {

    private static volatile ResourceBundle bundle = ResourceBundle.getBundle("gui.messages", Locale.ENGLISH);

    private Messages() {
    }

    /** @param languageCode {@code "en"} for English, anything else falls back to German. */
    public static void setLanguage(String languageCode) {
        Locale locale = "en".equalsIgnoreCase(languageCode) ? Locale.ENGLISH : Locale.GERMAN;
        bundle = ResourceBundle.getBundle("gui.messages", locale);
    }

    public static boolean isEnglish() {
        return Locale.ENGLISH.getLanguage().equals(bundle.getLocale().getLanguage());
    }

    public static String get(String key) {
        return bundle.getString(key);
    }
}
