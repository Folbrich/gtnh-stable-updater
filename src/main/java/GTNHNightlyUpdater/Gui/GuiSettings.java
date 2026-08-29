package GTNHNightlyUpdater.Gui;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain, Gson-serializable snapshot of the last-used {@link GTNHNightlyUpdater.Gui.StableView}
 * form state, persisted so the GUI can restore it on the next launch instead of starting blank
 * every time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuiSettings {

    private List<InstanceSetting> instances = new ArrayList<>();

    private boolean replace;

    /** One of {@code LATEST_STABLE}, {@code LATEST_BETA}, {@code EXACT}. */
    private String versionSource = "LATEST_STABLE";

    /** Only meaningful when {@link #versionSource} is {@code EXACT}. */
    private String exactVersion;

    /** Names of {@link GTNHNightlyUpdater.Config.MigrationDataCategory} enum constants. */
    private Set<String> migrationDataCategories = new LinkedHashSet<>();

    /** Absolute paths of individually selected custom config files. */
    private List<String> customConfigFiles = new ArrayList<>();

    private boolean darkTheme = false;

    /** One of {@code "de"}, {@code "en"}. */
    private String language = "en";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstanceSetting {
        private String path;
        private String side;
    }
}
