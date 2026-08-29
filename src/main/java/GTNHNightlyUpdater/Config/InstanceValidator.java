package GTNHNightlyUpdater.Config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared validation for an instance's minecraft directory, so both the CLI ({@code Main.Options})
 * and the GUI can surface the same error messages without duplicating the rules.
 */
public final class InstanceValidator {

    private InstanceValidator() {
    }

    /**
     * @return a human-readable error message if {@code path} is not usable, or {@code null} if it's valid.
     */
    public static String validateMinecraftDir(Path path) {
        if (path == null) {
            return "No path given.";
        }
        if (!Files.exists(path)) {
            return String.format("Path does not exist: '%s'", path);
        }
        return null;
    }

    /**
     * Client (Prism/MultiMC) instances are laid out as {@code <instance root>/.minecraft/}. Users
     * naturally pick the instance root in a folder browser, not the hidden {@code .minecraft}
     * subfolder, so resolve down into it automatically when possible.
     *
     * @return the actual {@code .minecraft} folder to use, or {@code null} if {@code selected}
     * itself isn't one and doesn't contain one.
     */
    public static Path resolveClientMinecraftDir(Path selected) {
        if (selected == null) {
            return null;
        }
        if (selected.getFileName() != null && selected.getFileName().toString().equals(".minecraft")) {
            return selected;
        }
        Path nested = selected.resolve(".minecraft");
        if (Files.isDirectory(nested)) {
            return nested;
        }
        return null;
    }

    /**
     * @return an explanatory error for a client path that isn't (and doesn't contain) a
     * {@code .minecraft} folder, e.g. after {@link #resolveClientMinecraftDir(Path)} returned {@code null}.
     */
    public static String clientDirErrorMessage(Path selected) {
        return String.format(
                "'%s' is not a Prism/MultiMC instance folder: it must either be named '.minecraft', "
                        + "or contain a '.minecraft' subfolder (e.g. select your instance's root folder, "
                        + "the one that contains '.minecraft', 'mmc-pack.json', etc.).",
                selected);
    }
}
