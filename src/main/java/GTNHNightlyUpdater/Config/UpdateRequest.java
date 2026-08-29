package GTNHNightlyUpdater.Config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Plain, picocli-free representation of the settings needed to run a STABLE update.
 * Both the CLI ({@code Main.Options}, via {@code Main.toUpdateRequest}) and the GUI can build
 * one of these and hand it to {@link GTNHNightlyUpdater.StableUpdater}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateRequest {

    public enum Side {
        CLIENT,
        SERVER
    }

    @Builder.Default
    private boolean replace = false;

    @Builder.Default
    private boolean beta = false;

    private String stableVersion;

    @Builder.Default
    private List<InstanceTarget> instances = new ArrayList<>();

    /**
     * Which user-data categories Migration mode (Method 1) should carry over from the old
     * instance. Defaults to everything the old hardcoded behaviour used to copy, minus
     * {@link MigrationDataCategory#CUSTOM_CONFIGS} (opt-in only, since it can break things).
     */
    @Builder.Default
    private Set<MigrationDataCategory> migrationDataCategories = EnumSet.of(
            MigrationDataCategory.OPTIONS,
            MigrationDataCategory.SAVES,
            MigrationDataCategory.SERVER_LIST,
            MigrationDataCategory.OTHER_USER_DATA);

    /**
     * Paths of individual config files to carry over, relative to the instance's {@code .minecraft}
     * folder (e.g. {@code "config/nomifactory.cfg"}). Only used when
     * {@link MigrationDataCategory#CUSTOM_CONFIGS} is enabled; lets users pick specific edited
     * configs instead of overwriting the whole {@code config/} folder.
     */
    @Builder.Default
    private List<String> customConfigRelativePaths = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InstanceTarget {
        private Path minecraftDir;
        private Side side;
    }
}
