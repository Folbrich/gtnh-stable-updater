package GTNHNightlyUpdater.Gui;

import GTNHNightlyUpdater.Main;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads/saves the GUI's remembered form state ({@link GuiSettings}) as JSON under the STABLE
 * updater's cache directory, so the last-used configuration survives across GUI launches.
 */
@Log4j2(topic = "GTNHNightlyUpdater-Gui")
public final class GuiSettingsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "gui-settings.json";

    private GuiSettingsStore() {
    }

    /** @return the saved settings, or {@code null} if none exist yet or they could not be read. */
    public static GuiSettings load() {
        try {
            Path file = settingsFile();
            if (Files.notExists(file)) {
                return null;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return GSON.fromJson(json, GuiSettings.class);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load saved GUI settings, starting with defaults: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Updates just the theme preference, preserving whatever form settings are already saved
     * (a theme toggle isn't tied to the "save on update start" flow).
     */
    public static void updateDarkTheme(boolean darkTheme) {
        GuiSettings settings = load();
        if (settings == null) {
            settings = new GuiSettings();
        }
        settings.setDarkTheme(darkTheme);
        save(settings);
    }

    /**
     * Updates just the language preference, preserving whatever form settings are already saved
     * (a language toggle isn't tied to the "save on update start" flow).
     */
    public static void updateLanguage(String language) {
        GuiSettings settings = load();
        if (settings == null) {
            settings = new GuiSettings();
        }
        settings.setLanguage(language);
        save(settings);
    }

    public static void save(GuiSettings settings) {
        try {
            Path file = settingsFile();
            Files.writeString(file, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not save GUI settings: {}", e.getMessage());
        }
    }

    private static Path settingsFile() throws IOException {
        return Main.resolveStableCacheDir().resolve(FILE_NAME);
    }
}
