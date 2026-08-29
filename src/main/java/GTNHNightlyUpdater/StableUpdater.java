package GTNHNightlyUpdater;

import GTNHNightlyUpdater.Config.MigrationDataCategory;
import GTNHNightlyUpdater.Config.UpdateRequest;
import lombok.Cleanup;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles updating GTNH instances to the latest stable release using the official zip packs
 * from downloads.gtnewhorizons.com.
 *
 * This updater:
 * - Detects the latest stable version for Java 17+ packs (e.g. Java_17-21, Java_17-25)
 * - Downloads and caches the corresponding client/server zips
 * - Extracts them once into the cache directory
 * - For CLIENT instances: replaces mods/config/serverutilities (and scripts/resources if present),
 *   and for Java 17+ also libraries/patches/mmc-pack.json in the instance root (Prism/MultiMC)
 * - For SERVER instances (Linux/Windows): replaces mods/config/scripts/resources/libraries/serverutilities
 *   and the main jar / start scripts in the server root
 *
 * World data, saves and other user data are left untouched, following the guidelines from the
 * official GTNH wiki.
 */
@Log4j2(topic = "GTNHNightlyUpdater-Stable")
public class StableUpdater {

    /**
     * The old Apache-style directory listings ({@code Multi_mc_downloads/?raw} / {@code ServerPacks/?raw})
     * that used to be scraped for the latest version now return HTTP 404. GTNH's downloads moved to
     * https://www.gtnewhorizons.com/downloads/, and the full release list (including betas/RCs) lives at
     * the version history page below, which is parsed instead.
     */
    private static final String VERSION_HISTORY_URL = "https://www.gtnewhorizons.com/version-history/";

    /** Matches Prism/MultiMC client zips for Java 17+: e.g. .../Multi_mc_downloads/betas/GT_New_Horizons_2.9.0-beta-2_Java_17-25.zip */
    private static final Pattern MULTI_MC_JAVA17_PATTERN =
            Pattern.compile("Multi_mc_downloads/.*_Java_17-\\d+\\.zip$");
    /** Matches server zips for Java 17+: e.g. .../ServerPacks/betas/GT_New_Horizons_2.9.0-beta-2_Server_Java_17-25.zip */
    private static final Pattern SERVER_JAVA17_PATTERN =
            Pattern.compile("ServerPacks/.*_Server_Java_17-\\d+\\.zip$");
    /** A release heading must look like a version number, e.g. "2.8.4", "2.9.0-beta-2", "2.5.0-RC1". */
    private static final Pattern VERSION_HEADING_PATTERN =
            Pattern.compile("\\d+\\.\\d+\\.\\d+.*");

    private final UpdateRequest options;
    private final DownloadProgressListener progressListener;

    public StableUpdater(UpdateRequest options) {
        this(options, DownloadProgressListener.NO_OP);
    }

    public StableUpdater(UpdateRequest options, DownloadProgressListener progressListener) {
        this.options = options;
        this.progressListener = progressListener;
    }

    public void run(Path cacheDir) throws IOException, InterruptedException {
        log.info("Starting stable update. Cache directory: {}", cacheDir);

        @Cleanup HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        boolean needsClientPack = options.getInstances().stream()
                .anyMatch(i -> i.getSide() == UpdateRequest.Side.CLIENT);
        boolean needsServerPack = options.getInstances().stream()
                .anyMatch(i -> i.getSide() == UpdateRequest.Side.SERVER);

        if (!needsClientPack && !needsServerPack) {
            log.warn("No instances configured; nothing to do.");
            return;
        }

        progressListener.onPhase("Rufe Versionsliste ab…");
        List<ReleaseEntry> history = fetchVersionHistory(client);

        StablePack multiMcPack = null;
        StablePack serverPack = null;

        if (needsClientPack) {
            ReleaseEntry entry = selectRelease(history, true, false);
            multiMcPack = new StablePack(entry.version(), fileNameFromUrl(entry.prismJava17Url()), entry.prismJava17Url());
            log.info("Selected client stable pack: version {} ({})", multiMcPack.version(), multiMcPack.url());
        }

        if (needsServerPack) {
            ReleaseEntry entry = selectRelease(history, false, true);
            serverPack = new StablePack(entry.version(), fileNameFromUrl(entry.serverJava17Url()), entry.serverJava17Url());
            log.info("Selected server stable pack: version {} ({})", serverPack.version(), serverPack.url());
        }

        Path multiMcRoot = null;
        if (multiMcPack != null) {
            Path zipPath = cacheDir.resolve(multiMcPack.fileName());
            downloadIfMissing(client, multiMcPack.url(), zipPath, "Client-Pack");

            progressListener.onPhase("Entpacke Client-Pack " + multiMcPack.version() + "…");
            Path extractDir = cacheDir.resolve("client-" + multiMcPack.version());
            ensureExtracted(zipPath, extractDir);

            multiMcRoot = findMinecraftRoot(extractDir);
            if (multiMcRoot == null) {
                throw new IOException("Unable to find client .minecraft root in extracted pack at " + extractDir);
            }
            log.info("Detected client pack .minecraft root at {}", multiMcRoot);
        }

        Path serverRoot = null;
        if (serverPack != null) {
            Path zipPath = cacheDir.resolve(serverPack.fileName());
            downloadIfMissing(client, serverPack.url(), zipPath, "Server-Pack");

            progressListener.onPhase("Entpacke Server-Pack " + serverPack.version() + "…");
            Path extractDir = cacheDir.resolve("server-" + serverPack.version());
            ensureExtracted(zipPath, extractDir);

            serverRoot = findMinecraftRoot(extractDir);
            if (serverRoot == null) {
                throw new IOException("Unable to find server root (mods/config) in extracted pack at " + extractDir);
            }
            log.info("Detected server pack root at {}", serverRoot);
        }

        for (val instance : options.getInstances()) {
            val side = instance.getSide();
            Path targetDir = instance.getMinecraftDir();

            if (side == UpdateRequest.Side.CLIENT) {
                if (multiMcRoot == null) {
                    log.error("No stable Prism/MultiMC client pack available; skipping {}", targetDir);
                    continue;
                }
                // enforce that the path really is a .minecraft folder as requested
                if (!targetDir.getFileName().toString().equals(".minecraft")) {
                    throw new IllegalArgumentException(String.format(
                            "For STABLE client updates the --minecraft path must point to a '.minecraft' folder, but got: '%s'",
                            targetDir
                    ));
                }

                if (options.isReplace()) {
                    log.info("Updating CLIENT instance at {} in REPLACE mode (Method 2: Direct Update).", targetDir);
                    progressListener.onPhase("Aktualisiere Client-Instanz " + targetDir + " (In-place)…");
                    updateClientInstanceInPlace(targetDir, multiMcRoot);
                    log.info("Finished updating CLIENT instance at {} (REPLACE mode).", targetDir);
                } else {
                    log.info("Updating CLIENT instance at {} in MIGRATION mode (Method 1: Migrating).", targetDir);
                    progressListener.onPhase("Migriere Client-Instanz " + targetDir + "…");
                    migrateClientInstance(targetDir, multiMcRoot, multiMcPack.version());
                    log.info("Finished updating CLIENT instance at {} (MIGRATION mode).", targetDir);
                }
            } else if (side == UpdateRequest.Side.SERVER) {
                if (serverRoot == null) {
                    log.error("No stable server pack available; skipping {}", targetDir);
                    continue;
                }
                log.info("Updating SERVER instance at {}", targetDir);
                progressListener.onPhase("Aktualisiere Server-Instanz " + targetDir + "…");
                updateServerInstance(targetDir, serverRoot);
                log.info("Finished updating SERVER instance at {}", targetDir);
            } else {
                log.warn("Unknown side {} for instance {}; skipping", side, targetDir);
            }
        }

        log.info("Stable update complete.");
    }

    private record StablePack(String version, String fileName, String url) {
    }

    /**
     * A single release entry parsed from the GTNH version history page.
     * {@code prismJava17Url}/{@code serverJava17Url} are {@code null} when that pack type
     * wasn't published for this version (e.g. the "April fools 2025" novelty entry).
     */
    public record ReleaseEntry(String version, boolean stable, String prismJava17Url, String serverJava17Url) {
    }

    /**
     * Fetches the version history and returns a lightweight, newest-first summary of every
     * available release (version + whether it's a "Stable release"), for GUI version pickers.
     */
    public List<ReleaseEntry> listAvailableVersions() throws IOException, InterruptedException {
        @Cleanup HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return fetchVersionHistory(client);
    }

    /**
     * Fetches and parses https://www.gtnewhorizons.com/version-history/, returning all release
     * entries in the page's newest-first order.
     */
    private List<ReleaseEntry> fetchVersionHistory(HttpClient client) throws IOException, InterruptedException {
        log.info("Querying GTNH version history from {}", VERSION_HISTORY_URL);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VERSION_HISTORY_URL))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch version history " + VERSION_HISTORY_URL + " (HTTP " + response.statusCode() + ")");
        }

        Document doc = Jsoup.parse(response.body(), VERSION_HISTORY_URL);

        // Each release's version number is rendered as a heading span; walk from one heading to the
        // next (in document order) to scope the download-link search to that release's card.
        Elements headings = doc.select("span.font-semibold").stream()
                .filter(e -> VERSION_HEADING_PATTERN.matcher(e.text().trim()).matches())
                .collect(Collectors.toCollection(Elements::new));
        List<ReleaseEntry> entries = new ArrayList<>();

        for (int i = 0; i < headings.size(); i++) {
            Element heading = headings.get(i);
            String version = heading.text().trim();

            Element tagSpan = heading.nextElementSibling();
            boolean stable = tagSpan != null && tagSpan.text().trim().equalsIgnoreCase("Stable release");

            Elements scopedLinks = findReleaseCard(heading).select("a[href]");

            String prismUrl = findFirstMatchingHref(scopedLinks, MULTI_MC_JAVA17_PATTERN);
            String serverUrl = findFirstMatchingHref(scopedLinks, SERVER_JAVA17_PATTERN);

            entries.add(new ReleaseEntry(version, stable, prismUrl, serverUrl));
        }

        if (entries.isEmpty()) {
            throw new IOException("Could not find any release entries on " + VERSION_HISTORY_URL + "; the page layout may have changed.");
        }

        log.info("Parsed {} release entries from version history (latest: {})", entries.size(), entries.get(0).version());
        return entries;
    }

    /**
     * Returns all {@code <a>} elements in document order between {@code start} (exclusive) and
     * {@code end} (exclusive, or end of document if {@code null}).
     */
    /**
     * Walks up from a version heading span to the smallest ancestor element that contains that
     * release's download links, so link lookups don't spill over into other release cards.
     */
    private Element findReleaseCard(Element heading) {
        Element card = heading;
        while (card.parent() != null && card.select("a[href*=downloads.gtnewhorizons.com]").isEmpty()) {
            card = card.parent();
        }
        return card;
    }

    private String findFirstMatchingHref(Elements links, Pattern pattern) {
        for (Element a : links) {
            String href = a.absUrl("href");
            if (pattern.matcher(href).find()) {
                return href;
            }
        }
        return null;
    }

    private String fileNameFromUrl(String url) {
        int idx = url.lastIndexOf('/');
        return idx >= 0 ? url.substring(idx + 1) : url;
    }

    /**
     * Picks the release to use for a client and/or server pack, honouring {@code --stable-version}
     * (exact pin) and {@code --beta} (allow non-"Stable release" entries) from the CLI options.
     */
    private ReleaseEntry selectRelease(List<ReleaseEntry> history, boolean needClient, boolean needServer) throws IOException {
        if (options.getStableVersion() != null && !options.getStableVersion().isBlank()) {
            String pinned = options.getStableVersion().trim();
            ReleaseEntry match = history.stream()
                    .filter(e -> e.version().equalsIgnoreCase(pinned))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "Version '" + pinned + "' was not found on " + VERSION_HISTORY_URL));

            if (needClient && match.prismJava17Url() == null) {
                throw new IOException("Version '" + pinned + "' does not have a Prism/MultiMC Java 17+ download available.");
            }
            if (needServer && match.serverJava17Url() == null) {
                throw new IOException("Version '" + pinned + "' does not have a Server Java 17+ download available.");
            }
            return match;
        }

        for (ReleaseEntry entry : history) {
            if (!options.isBeta() && !entry.stable()) {
                continue;
            }
            if (needClient && entry.prismJava17Url() == null) {
                continue;
            }
            if (needServer && entry.serverJava17Url() == null) {
                continue;
            }
            return entry;
        }

        throw new IOException("Could not find a" + (options.isBeta() ? "ny" : " stable") + " release on " + VERSION_HISTORY_URL
                + " with a Java 17+ download for the requested pack type(s). Try --beta to include beta/RC releases.");
    }

    private void downloadIfMissing(HttpClient client, String url, Path target, String label) throws IOException, InterruptedException {
        if (Files.exists(target)) {
            log.info("Using cached pack at {}", target);
            progressListener.onPhase(label + " bereits im Cache, kein Download nötig.");
            return;
        }

        log.info("Downloading {} from {} to {}", label, url, target);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download pack from " + url + " (HTTP " + response.statusCode() + ")");
        }

        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);

        Files.createDirectories(target.getParent());
        Path tempTarget = target.resolveSibling(target.getFileName() + ".part");

        long bytesRead = 0;
        long lastLoggedAt = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tempTarget)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                bytesRead += n;
                progressListener.onProgress(label, bytesRead, totalBytes);

                if (bytesRead - lastLoggedAt >= 10 * 1024 * 1024) {
                    log.info("Downloading {}: {} / {}", label, humanReadableBytes(bytesRead),
                            totalBytes > 0 ? humanReadableBytes(totalBytes) : "?");
                    lastLoggedAt = bytesRead;
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tempTarget);
            throw e;
        }

        Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Finished downloading {} ({})", label, humanReadableBytes(bytesRead));
        progressListener.onPhase("Download von " + label + " abgeschlossen (" + humanReadableBytes(bytesRead) + ").");
    }

    public static String humanReadableBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB"};
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    private void ensureExtracted(Path zipPath, Path extractDir) throws IOException {
        if (Files.exists(extractDir) && Files.isDirectory(extractDir)) {
            // Assume already extracted for this version
            log.info("Using existing extracted pack at {}", extractDir);
            return;
        }

        log.info("Extracting {} into {}", zipPath, extractDir);
        Files.createDirectories(extractDir);
        extractZip(zipPath, extractDir);
    }

    /**
     * Find a directory inside {@code baseDir} that contains both a {@code mods} and {@code config}
     * directory. For client packs, this is typically the ".minecraft" folder; for server packs,
     * the server root.
     */
    private Path findMinecraftRoot(Path baseDir) throws IOException {
        try (Stream<Path> stream = Files.walk(baseDir, 4)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.isDirectory(p.resolve("mods")) && Files.isDirectory(p.resolve("config")))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * In-place update of an existing client instance (\"Method 2: Direct Update\" from the GTNH wiki).
     */
    private void updateClientInstanceInPlace(Path instanceDir, Path packMinecraftRoot) throws IOException {
        // Follow "Method 2: Direct Update" from the GTNH wiki (Installing and Migrating).
        // Selected custom configs live inside the very folders about to be overwritten (config/, mods/,
        // etc.), so they must be backed up to temp files before the overwrite and restored afterward.
        java.util.Map<String, Path> customConfigBackups = backupSelectedCustomConfigs(instanceDir);

        // 1) Replace inside .minecraft: mods, config, serverutilities, scripts, resources
        copyDirectoryFromPack(packMinecraftRoot, instanceDir, "mods");
        copyDirectoryFromPack(packMinecraftRoot, instanceDir, "config");
        copyDirectoryFromPack(packMinecraftRoot, instanceDir, "serverutilities");
        copyDirectoryFromPack(packMinecraftRoot, instanceDir, "scripts");
        copyDirectoryFromPack(packMinecraftRoot, instanceDir, "resources");

        // 2) If using Java 17+, replace in instance root (alongside .minecraft): libraries, patches, mmc-pack.json
        Path packInstanceRoot = packMinecraftRoot.getParent();
        Path instanceRoot = instanceDir.getParent();
        if (packInstanceRoot != null && instanceRoot != null) {
            copyDirectoryFromPack(packInstanceRoot, instanceRoot, "libraries");
            copyDirectoryFromPack(packInstanceRoot, instanceRoot, "patches");
            copyFileFromPack(packInstanceRoot, instanceRoot, "mmc-pack.json");
            invalidatePrismPackCache(instanceRoot);
        }

        restoreSelectedCustomConfigs(instanceDir, customConfigBackups);

        log.info("Client update (in-place) done at {}. User files like saves, journeymap, resourcepacks etc. were left untouched.", instanceDir);
        log.info("If the game still crashes with Pack200/ClassNotFoundException: In Prism Launcher use Edit Instance -> Version -> Reload, or create a new instance by importing the latest GTNH zip and copy your saves into it.");
    }

    /**
     * Backs up the user-selected custom config files (if the {@code CUSTOM_CONFIGS} category is
     * active) to temp files, so they can survive {@link #updateClientInstanceInPlace} overwriting
     * the instance's own {@code config/} etc. folders in place.
     */
    private java.util.Map<String, Path> backupSelectedCustomConfigs(Path instanceDir) throws IOException {
        java.util.Map<String, Path> backups = new java.util.LinkedHashMap<>();
        if (!options.getMigrationDataCategories().contains(MigrationDataCategory.CUSTOM_CONFIGS)) {
            return backups;
        }
        for (String relativePath : options.getCustomConfigRelativePaths()) {
            Path src = instanceDir.resolve(relativePath);
            if (!Files.exists(src) || !Files.isRegularFile(src)) {
                log.warn("Selected custom config '{}' not found in instance, skipping.", relativePath);
                continue;
            }
            Path tempFile = Files.createTempFile("gtnh-custom-config-", ".bak");
            Files.copy(src, tempFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            backups.put(relativePath, tempFile);
        }
        return backups;
    }

    /**
     * Restores the custom config files backed up by {@link #backupSelectedCustomConfigs}, overwriting
     * whatever version the new pack just placed at each relative path, then deletes the temp file.
     */
    private void restoreSelectedCustomConfigs(Path instanceDir, java.util.Map<String, Path> backups) throws IOException {
        for (var entry : backups.entrySet()) {
            Path dest = instanceDir.resolve(entry.getKey());
            log.warn("Restoring selected custom config {}. This overwrites the new pack's version of this file "
                    + "and can break it if the config format changed between releases.", dest);
            Files.createDirectories(dest.getParent());
            Files.copy(entry.getValue(), dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.deleteIfExists(entry.getValue());
        }
    }

    /**
     * Delete Prism/MultiMC cache files in the instance root so the launcher re-reads mmc-pack.json
     * and re-resolves components (e.g. patched Forge for Java 17+). Otherwise the launcher may
     * keep using cached component resolution and load the unpatched Forge from the global cache.
     */
    private void invalidatePrismPackCache(Path instanceRoot) {
        String[] cacheFiles = {"pack_index", "pack_index.json", ".pack_index"};
        for (String name : cacheFiles) {
            Path p = instanceRoot.resolve(name);
            if (Files.exists(p)) {
                try {
                    if (Files.isDirectory(p)) {
                        FileUtils.deleteDirectory(p.toFile());
                        log.info("Deleted pack cache directory {} so launcher re-reads mmc-pack.json", p);
                    } else {
                        Files.delete(p);
                        log.info("Deleted pack cache file {} so launcher re-reads mmc-pack.json", p);
                    }
                } catch (IOException e) {
                    log.warn("Could not delete pack cache {}: {}", p, e.getMessage());
                }
            }
        }
    }

    /**
     * Create a new instance based on the latest stable pack and copy user data from the source
     * instance's .minecraft (\"Method 1: Migrating\" from the GTNH wiki).
     *
     * The original instance (sourceMinecraftDir) is left untouched as an additional backup.
     */
    private void migrateClientInstance(Path sourceMinecraftDir, Path packMinecraftRoot, String stableVersion) throws IOException {
        // sourceMinecraftDir is the existing .minecraft folder
        Path sourceInstanceRoot = sourceMinecraftDir.getParent();
        if (sourceInstanceRoot == null) {
            throw new IOException("Unable to determine instance root for " + sourceMinecraftDir);
        }

        Path packInstanceRoot = packMinecraftRoot.getParent();
        if (packInstanceRoot == null) {
            throw new IOException("Unable to determine instance root in extracted pack for " + packMinecraftRoot);
        }

        String baseName = sourceInstanceRoot.getFileName().toString();
        String safeVersion = stableVersion.replaceAll("[^0-9A-Za-z._-]", "_");
        Path instancesParent = sourceInstanceRoot.getParent();
        if (instancesParent == null) {
            throw new IOException("Unable to determine parent for instance root " + sourceInstanceRoot);
        }

        Path newInstanceRoot = instancesParent.resolve(baseName + "_gtnh_" + safeVersion);
        if (Files.exists(newInstanceRoot)) {
            throw new IOException("Target instance directory already exists: " + newInstanceRoot);
        }

        log.info("Creating new instance at {} based on stable pack (version {}).", newInstanceRoot, stableVersion);
        FileUtils.copyDirectory(packInstanceRoot.toFile(), newInstanceRoot.toFile());

        Path newMinecraftDir = newInstanceRoot.resolve(".minecraft");
        if (Files.notExists(newMinecraftDir) || !Files.isDirectory(newMinecraftDir)) {
            throw new IOException("New instance does not contain a .minecraft directory at " + newMinecraftDir);
        }

        // Copy user data from old .minecraft to new .minecraft (Method 1 from wiki)
        copyUserDataForMethod1(sourceMinecraftDir, newMinecraftDir);

        log.info("Migration complete. Original instance kept at: {}", sourceMinecraftDir);
        log.info("New migrated instance created at: {}", newInstanceRoot);
        log.info("You can now add/import '{}' as a new instance in Prism/MultiMC and use it for GTNH {}.", newInstanceRoot, stableVersion);
    }

    /**
     * Copy user data from source .minecraft to target .minecraft following the GTNH wiki
     * \"Method 1: Migrating\" list.
     */
    private void copyUserDataForMethod1(Path sourceMinecraftDir, Path targetMinecraftDir) throws IOException {
        val categories = options.getMigrationDataCategories();

        if (categories.contains(MigrationDataCategory.SAVES)) {
            copyUserDirIfExists(sourceMinecraftDir, targetMinecraftDir, "saves");
            copyUserDirIfExists(sourceMinecraftDir, targetMinecraftDir, "backups");
        }

        if (categories.contains(MigrationDataCategory.OTHER_USER_DATA)) {
            for (String dir : new String[]{
                    "journeymap", "visualprospecting", "TCNodeTracker", "schematics", "resourcepacks", "shaderpacks"
            }) {
                copyUserDirIfExists(sourceMinecraftDir, targetMinecraftDir, dir);
            }
        }

        if (categories.contains(MigrationDataCategory.OPTIONS)) {
            copyUserFileIfExists(sourceMinecraftDir, targetMinecraftDir, "options.txt");
            copyUserFileIfExists(sourceMinecraftDir, targetMinecraftDir, "optionsnf.txt");
        }

        if (categories.contains(MigrationDataCategory.SERVER_LIST)) {
            copyUserFileIfExists(sourceMinecraftDir, targetMinecraftDir, "servers.dat");
        }

        if (categories.contains(MigrationDataCategory.OTHER_USER_DATA)) {
            copyUserFileIfExists(sourceMinecraftDir, targetMinecraftDir, "localconfig.cfg");
            copyUserFileIfExists(sourceMinecraftDir, targetMinecraftDir, "BotaniaVars.dat");

            Path srcShaders = sourceMinecraftDir.resolve("config").resolve("shaders.properties");
            if (Files.exists(srcShaders) && Files.isRegularFile(srcShaders)) {
                Path destShaders = targetMinecraftDir.resolve("config").resolve("shaders.properties");
                log.info("Copying user file {} -> {}", srcShaders, destShaders);
                Files.createDirectories(destShaders.getParent());
                Files.copy(srcShaders, destShaders, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }

        if (categories.contains(MigrationDataCategory.CUSTOM_CONFIGS)) {
            for (String relativePath : options.getCustomConfigRelativePaths()) {
                Path src = sourceMinecraftDir.resolve(relativePath);
                if (!Files.exists(src) || !Files.isRegularFile(src)) {
                    log.warn("Selected custom config '{}' not found in old instance, skipping.", relativePath);
                    continue;
                }
                Path dest = targetMinecraftDir.resolve(relativePath);
                log.warn("Copying selected custom config {} -> {}. This overwrites the new pack's version of this file "
                        + "and can break it if the config format changed between releases.", src, dest);
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private void copyUserDirIfExists(Path sourceMinecraftDir, Path targetMinecraftDir, String dir) throws IOException {
        Path src = sourceMinecraftDir.resolve(dir);
        if (Files.exists(src) && Files.isDirectory(src)) {
            Path dest = targetMinecraftDir.resolve(dir);
            if (Files.exists(dest)) {
                FileUtils.deleteDirectory(dest.toFile());
            }
            log.info("Copying user directory {} -> {}", src, dest);
            FileUtils.copyDirectory(src.toFile(), dest.toFile());
        }
    }

    private void copyUserFileIfExists(Path sourceMinecraftDir, Path targetMinecraftDir, String file) throws IOException {
        Path src = sourceMinecraftDir.resolve(file);
        if (Files.exists(src) && Files.isRegularFile(src)) {
            Path dest = targetMinecraftDir.resolve(file);
            log.info("Copying user file {} -> {}", src, dest);
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void updateServerInstance(Path serverDir, Path packRoot) throws IOException {
        // Ensure local server cache directory exists
        Path localCache = serverDir.resolve(".cache");
        if (!Files.exists(localCache)) {
            Files.createDirectories(localCache);
            log.info("Created server local cache directory {}", localCache);
        }

        // Backup JourneyMapServer folder from config before deleting configs
        Path jmSource = serverDir.resolve("config").resolve("JourneyMapServer");
        Path jmBackup = localCache.resolve("JourneyMapServer");
        if (Files.exists(jmSource) && Files.isDirectory(jmSource)) {
            if (Files.exists(jmBackup)) {
                FileUtils.deleteDirectory(jmBackup.toFile());
            }
            log.info("Backing up JourneyMapServer from {} to {}", jmSource, jmBackup);
            FileUtils.copyDirectory(jmSource.toFile(), jmBackup.toFile());
        }

        // Delete and replace key modpack directories from the new server pack, as per GTNH server update guide.
        // Delete from serverDir regardless; only copy back if present in packRoot so removed folders stay removed.
        String[] dirsToUpdate = new String[]{
                "mods",
                "config",
                "scripts",
                "resources",
                "libraries"
        };

        for (String dir : dirsToUpdate) {
            Path dest = serverDir.resolve(dir);
            if (Files.exists(dest)) {
                log.info("Deleting existing server directory {}", dest);
                FileUtils.deleteDirectory(dest.toFile());
            }

            Path src = packRoot.resolve(dir);
            if (Files.exists(src) && Files.isDirectory(src)) {
                log.info("Copying server directory {} -> {}", src, dest);
                FileUtils.copyDirectory(src.toFile(), dest.toFile());
            } else {
                log.debug("Server pack does not contain '{}', leaving it absent in target.", dir);
            }
        }

        // For Java 17+ servers remove these files from server root, then replace them with the ones from the pack
        String[] filesToRemove = new String[]{
                "lwjgl3ify-forgePatches.jar",
                "java9args.txt",
                "startserver-java9.bat",
                "startserver-java9.sh"
        };
        for (String file : filesToRemove) {
            Path f = serverDir.resolve(file);
            if (Files.exists(f)) {
                log.info("Deleting server root file {}", f);
                Files.delete(f);
            }
        }

        // Copy main jars and start scripts from the server pack root (but keep world/backups/etc. intact).
        try (Stream<Path> stream = Files.list(packRoot)) {
            stream
                    .filter(Files::isRegularFile)
                    .forEach(src -> {
                        String name = src.getFileName().toString();
                        String lower = name.toLowerCase(Locale.ROOT);

                        boolean isJar = lower.endsWith(".jar");
                        boolean isScript = lower.endsWith(".sh") || lower.endsWith(".bat") || lower.endsWith(".command");

                        if (!isJar && !isScript) {
                            return;
                        }

                        Path dest = serverDir.resolve(name);
                        try {
                            log.info("Copying server root file {} -> {}", src, dest);
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to copy " + src + " to " + dest, e);
                        }
                    });
        }

        // Restore JourneyMapServer folder into the new config
        if (Files.exists(jmBackup) && Files.isDirectory(jmBackup)) {
            Path dest = serverDir.resolve("config").resolve("JourneyMapServer");
            if (Files.exists(dest)) {
                FileUtils.deleteDirectory(dest.toFile());
            }
            log.info("Restoring JourneyMapServer from backup {} -> {}", jmBackup, dest);
            FileUtils.copyDirectory(jmBackup.toFile(), dest.toFile());
        }

        log.info("Server update done at {}. World folders, backups and other custom data (including JourneyMapServer UUID) were left untouched.", serverDir);
    }

    private void copyDirectoryFromPack(Path packRoot, Path targetRoot, String directoryName) throws IOException {
        Path src = packRoot.resolve(directoryName);
        if (!Files.exists(src) || !Files.isDirectory(src)) {
            log.debug("Pack does not contain '{}', skipping.", directoryName);
            return;
        }

        Path dest = targetRoot.resolve(directoryName);
        if (Files.exists(dest)) {
            log.info("Deleting existing directory {}", dest);
            FileUtils.deleteDirectory(dest.toFile());
        }

        log.info("Copying directory {} -> {}", src, dest);
        FileUtils.copyDirectory(src.toFile(), dest.toFile());
    }

    private void copyFileFromPack(Path packRoot, Path targetRoot, String fileName) throws IOException {
        Path src = packRoot.resolve(fileName);
        if (!Files.exists(src) || !Files.isRegularFile(src)) {
            log.debug("Pack does not contain file '{}', skipping.", fileName);
            return;
        }
        Path dest = targetRoot.resolve(fileName);
        log.info("Copying file {} -> {}", src, dest);
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * Secure zip extraction using the JDK Zip FileSystem. This mirrors the full contents
     * of the GTNH zip (including all files and directories) into {@code targetDir}.
     */
    private void extractZip(Path zipPath, Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        // Mount the zip as a FileSystem and copy everything 1:1
        try (FileSystem zipFs = FileSystems.newFileSystem(zipPath, (ClassLoader) null)) {
            for (Path root : zipFs.getRootDirectories()) {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.forEach(src -> {
                        try {
                            Path rel = root.relativize(src);
                            if (rel.toString().isEmpty()) {
                                return; // skip root itself
                            }
                            Path dest = targetDir.resolve(rel.toString());

                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dest);
                            } else {
                                Files.createDirectories(dest.getParent());
                                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to extract entry from zip: " + src, e);
                        }
                    });
                }
            }
        }
    }
}

