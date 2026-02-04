package GTNHNightlyUpdater;

import lombok.Cleanup;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
@RequiredArgsConstructor
public class StableUpdater {

    private static final String MULTI_MC_BASE = "https://downloads.gtnewhorizons.com/Multi_mc_downloads/";
    private static final String SERVER_PACKS_BASE = "https://downloads.gtnewhorizons.com/ServerPacks/";

    /** Matches Prism/MultiMC client zips: e.g. GT_New_Horizons_2.8.4_Java_17-25.zip */
    private static final Pattern MULTI_MC_STABLE_PATTERN =
            Pattern.compile("GT_New_Horizons_(\\d+\\.\\d+\\.\\d+)_Java_17-\\d+\\.zip");
    /** Matches server zips: e.g. GT_New_Horizons_2.8.4_Server_Java_17-25.zip */
    private static final Pattern SERVER_STABLE_PATTERN =
            Pattern.compile("GT_New_Horizons_(\\d+\\.\\d+\\.\\d+)_Server_Java_17-\\d+\\.zip");

    private final Main.Options options;

    public void run(Path cacheDir) throws IOException, InterruptedException {
        log.info("Starting stable update. Cache directory: {}", cacheDir);

        if (options.configsOnly) {
            log.warn("configsOnly flag is ignored for STABLE updates; performing full pack update as described on the GTNH wiki.");
        }
        if (options.updateConfigs) {
            log.warn("updateConfigs flag is ignored for STABLE updates; stable packs already contain the correct configs.");
        }
        if (options.getLatestRelease) {
            log.warn("get-latest flag is not applicable for STABLE; the latest stable zip from downloads.gtnewhorizons.com is always used.");
        }

        @Cleanup HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        boolean needsClientPack = options.instances.stream()
                .anyMatch(i -> i.config.side == Main.Options.Instance.InstanceConfig.Side.CLIENT);
        boolean needsServerPack = options.instances.stream()
                .anyMatch(i -> i.config.side == Main.Options.Instance.InstanceConfig.Side.SERVER);

        if (!needsClientPack && !needsServerPack) {
            log.warn("No instances configured; nothing to do.");
            return;
        }

        StablePack multiMcPack = null;
        StablePack serverPack = null;

        if (needsClientPack) {
            multiMcPack = findLatestStablePack(client,
                    "client",
                    MULTI_MC_BASE + "?raw",
                    MULTI_MC_STABLE_PATTERN,
                    MULTI_MC_BASE);
        }

        if (needsServerPack) {
            serverPack = findLatestStablePack(client,
                    "server",
                    SERVER_PACKS_BASE + "?raw",
                    SERVER_STABLE_PATTERN,
                    SERVER_PACKS_BASE);
        }

        Path multiMcRoot = null;
        if (multiMcPack != null) {
            Path zipPath = cacheDir.resolve(multiMcPack.fileName());
            downloadIfMissing(client, multiMcPack.url(), zipPath);

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
            downloadIfMissing(client, serverPack.url(), zipPath);

            Path extractDir = cacheDir.resolve("server-" + serverPack.version());
            ensureExtracted(zipPath, extractDir);

            serverRoot = findMinecraftRoot(extractDir);
            if (serverRoot == null) {
                throw new IOException("Unable to find server root (mods/config) in extracted pack at " + extractDir);
            }
            log.info("Detected server pack root at {}", serverRoot);
        }

        for (val instance : options.instances) {
            val side = instance.config.side;
            Path targetDir = instance.config.getMinecraftDir();

            if (side == Main.Options.Instance.InstanceConfig.Side.CLIENT) {
                if (multiMcRoot == null) {
                    log.error("No stable Prism/MultiMC client pack available; skipping {}", targetDir);
                    continue;
                }
                log.info("Updating CLIENT instance at {}", targetDir);
                updateClientInstance(targetDir, multiMcRoot);
                log.info("Finished updating CLIENT instance at {}", targetDir);
            } else if (side == Main.Options.Instance.InstanceConfig.Side.SERVER) {
                if (serverRoot == null) {
                    log.error("No stable server pack available; skipping {}", targetDir);
                    continue;
                }
                log.info("Updating SERVER instance at {}", targetDir);
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

    private StablePack findLatestStablePack(HttpClient client,
                                            String label,
                                            String indexUrl,
                                            Pattern pattern,
                                            String baseUrl) throws IOException, InterruptedException {
        log.info("Querying latest {} stable pack from {}", label, indexUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(indexUrl))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch " + label + " pack index " + indexUrl + " (HTTP " + response.statusCode() + ")");
        }

        String body = response.body();
        Matcher matcher = pattern.matcher(body);

        String bestVersion = null;
        String bestFile = null;

        while (matcher.find()) {
            String version = matcher.group(1);
            String fileName = matcher.group(0);

            if (bestVersion == null ||
                    new DefaultArtifactVersion(version).compareTo(new DefaultArtifactVersion(bestVersion)) > 0) {
                bestVersion = version;
                bestFile = fileName;
            }
        }

        if (bestVersion == null || bestFile == null) {
            throw new IOException("Could not determine latest stable " + label + " pack from " + indexUrl);
        }

        String url = baseUrl + bestFile;
        log.info("Latest {} stable detected: version {} ({})", label, bestVersion, url);
        return new StablePack(bestVersion, bestFile, url);
    }

    private void downloadIfMissing(HttpClient client, String url, Path target) throws IOException, InterruptedException {
        if (Files.exists(target)) {
            log.info("Using cached pack at {}", target);
            return;
        }

        log.info("Downloading pack from {} to {}", url, target);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download pack from " + url + " (HTTP " + response.statusCode() + ")");
        }

        Files.createDirectories(target.getParent());
        Files.write(target, response.body());
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

    private void updateClientInstance(Path instanceDir, Path packMinecraftRoot) throws IOException {
        // Follow "Method 2: Direct Update" from the GTNH wiki (Installing and Migrating).
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

        log.info("Client update done at {}. User files like saves, journeymap, resourcepacks etc. were left untouched.", instanceDir);
        log.info("If the game still crashes with Pack200/ClassNotFoundException: In Prism Launcher use Edit Instance -> Version -> Reload, or create a new instance by importing the latest GTNH zip and copy your saves into it.");
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

    private void updateServerInstance(Path serverDir, Path packRoot) throws IOException {
        // Delete and replace key modpack directories from the new server pack.
        String[] dirsToUpdate = new String[]{
                "mods",
                "config",
                "scripts",
                "resources",
                "libraries",
                "serverutilities"
        };

        for (String dir : dirsToUpdate) {
            copyDirectoryFromPack(packRoot, serverDir, dir);
        }

        // Also update main jars and start scripts in the server root (but keep world/backups/etc. intact).
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

        log.info("Server update done at {}. World folders, backups and other custom data were left untouched.", serverDir);
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
     * Secure zip extraction, adapted from the existing updater implementation.
     */
    private void extractZip(Path zipPath, Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                Path resolvedPath = targetDir.resolve(entry.getName()).normalize();

                // Prevent Zip Slip vulnerability
                if (!resolvedPath.startsWith(targetDir)) {
                    throw new IOException("Entry is outside the target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (Files.exists(resolvedPath)) {
                        FileUtils.deleteDirectory(resolvedPath.toFile());
                    }
                    Files.createDirectories(resolvedPath);
                } else {
                    if (!Files.exists(resolvedPath.getParent())) {
                        Files.createDirectories(resolvedPath.getParent());
                    }

                    Files.copy(zis, resolvedPath, StandardCopyOption.REPLACE_EXISTING);
                }

                zis.closeEntry();
            }
        }
    }
}

