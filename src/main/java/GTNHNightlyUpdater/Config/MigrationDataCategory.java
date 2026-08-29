package GTNHNightlyUpdater.Config;

/**
 * User-data categories that {@code StableUpdater}'s Migration mode (Method 1) can carry over
 * from the old instance into the freshly created one. Exposed in the GUI's "Erweitert" section
 * so users can opt in/out instead of always getting the same fixed list.
 */
public enum MigrationDataCategory {

    /** options.txt, optionsnf.txt (includes key bindings/controls). */
    OPTIONS,

    /** saves/, backups/. */
    SAVES,

    /** servers.dat (multiplayer server list). */
    SERVER_LIST,

    /** JourneyMap, VisualProspecting, TCNodeTracker, schematics, resource-/shaderpacks, Botania vars, shaders.properties. */
    OTHER_USER_DATA,

    /**
     * Copies the entire old {@code config/} folder over the new pack's config, overwriting any
     * matching files. This can break the new version if config formats changed between releases,
     * so it defaults to off and should carry a clear warning in the UI.
     */
    CUSTOM_CONFIGS
}
