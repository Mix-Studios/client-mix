package com.mixclient.launcher.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mixclient.launcher.core.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Downloads and extracts everything a version needs before it can start. */
public final class AssetInstaller {

    private final MojangApi api;
    private final Path gameDir;

    public AssetInstaller(MojangApi api, Path gameDir) {
        this.api = api;
        this.gameDir = gameDir;
    }

    public PreparedInstall prepare(String versionId, Progress progress) throws IOException {
        progress.update("Resolving " + versionId, 0.02);
        VersionResolver.Resolved resolved =
                VersionResolver.resolve(api, gameDir, versionId, progress);
        JsonObject meta = resolved.json();

        Path librariesDir = gameDir.resolve("libraries");
        Path versionsDir = gameDir.resolve("versions");
        Path assetsDir = gameDir.resolve("assets");
        Path nativesDir = versionsDir.resolve(versionId).resolve("natives");

        Map<String, Boolean> features = VersionResolver.defaultFeatures();
        String osName = VersionResolver.osName();

        // ---- client jar -------------------------------------------------
        progress.update("Checking client jar", 0.05);
        JsonObject downloads = meta.getAsJsonObject("downloads");
        JsonObject client = downloads != null && downloads.has("client")
                ? downloads.getAsJsonObject("client") : null;
        if (client == null || !client.has("url")) {
            throw new IOException("Version " + versionId + " has no client jar to download.");
        }
        Path clientJar = versionsDir.resolve(resolved.clientJarVersionId())
                .resolve(resolved.clientJarVersionId() + ".jar");
        api.downloadIfMissing(client.get("url").getAsString(), clientJar);

        // ---- libraries + natives ---------------------------------------
        List<Path> classpath = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        Files.createDirectories(nativesDir);

        var libraries = meta.has("libraries") ? meta.getAsJsonArray("libraries") : null;
        int libraryCount = libraries == null ? 0 : libraries.size();
        int index = 0;

        if (libraries != null) {
            for (JsonElement element : libraries) {
                index++;
                double fraction = 0.05 + 0.55 * (libraryCount == 0 ? 1 : (double) index / libraryCount);
                JsonObject library = element.getAsJsonObject();
                if (!VersionResolver.rulesAllowed(library.get("rules"), features)) {
                    continue;
                }
                // Old Forge profiles mark server-only libraries such as jline this way.
                if (library.has("clientreq") && !library.get("clientreq").getAsBoolean()) {
                    continue;
                }

                String name = library.has("name") ? library.get("name").getAsString() : "";
                JsonObject libDownloads = library.getAsJsonObject("downloads");
                JsonObject artifact = libDownloads != null && libDownloads.has("artifact")
                        ? libDownloads.getAsJsonObject("artifact") : null;

                Path artifactFile = null;
                if (artifact != null && artifact.has("path")) {
                    String path = artifact.get("path").getAsString();
                    String url = artifact.has("url") ? artifact.get("url").getAsString() : mavenUrl(library, path);
                    artifactFile = librariesDir.resolve(path);
                    progress.update("Library " + shortName(name), fraction);
                    if (url != null && !url.isBlank()) {
                        api.downloadIfMissing(url, artifactFile);
                    }
                } else if (libDownloads == null) {
                    // Forge and Fabric profiles list plain maven coordinates instead of downloads.
                    // A downloads block without an artifact means natives-only (LWJGL 2), so the
                    // classifier below is the only jar to fetch.
                    String path = mavenPath(name);
                    if (path != null) {
                        artifactFile = librariesDir.resolve(path);
                        progress.update("Library " + shortName(name), fraction);
                        api.downloadIfMissing(mavenUrl(library, path), artifactFile);
                    }
                }
                if (artifactFile != null && Files.exists(artifactFile)) {
                    classpath.add(artifactFile);
                }

                // Old versions ship natives as classifier jars that must be extracted.
                JsonObject nativesMap = library.getAsJsonObject("natives");
                if (nativesMap != null) {
                    String classifier = nativesMap.has(osName) ? nativesMap.get(osName).getAsString() : null;
                    if (classifier == null) {
                        continue;
                    }
                    classifier = classifier.replace("${arch}", is64Bit() ? "64" : "32");
                    JsonObject classifierArtifact = libDownloads != null && libDownloads.has("classifiers")
                            ? libDownloads.getAsJsonObject("classifiers").getAsJsonObject(classifier) : null;
                    if (classifierArtifact == null) {
                        continue;
                    }
                    String path = classifierArtifact.get("path").getAsString();
                    String url = classifierArtifact.has("url")
                            ? classifierArtifact.get("url").getAsString() : mavenUrl(library, path);
                    Path jar = librariesDir.resolve(path);
                    if (url != null && !url.isBlank()) {
                        api.downloadIfMissing(url, jar);
                    }
                    extractNatives(jar, nativesDir, library);
                }
            }
        }

        // ---- assets -----------------------------------------------------
        String assetIndexId = "";
        Path legacyAssetsDir = null;
        JsonObject assetIndex = meta.getAsJsonObject("assetIndex");
        if (assetIndex != null && assetIndex.has("id") && assetIndex.has("url")) {
            assetIndexId = assetIndex.get("id").getAsString();
            Path indexFile = assetsDir.resolve("indexes").resolve(assetIndexId + ".json");
            progress.update("Downloading asset index", 0.62);
            api.downloadIfMissing(assetIndex.get("url").getAsString(), indexFile);

            JsonObject objects = api.readJson(indexFile).getAsJsonObject("objects");
            int total = objects == null ? 0 : objects.size();
            progress.update("Checking " + total + " game assets", 0.65);
            downloadAssets(objects, assetsDir, progress);

            // 1.6-era versions read assets from a flat directory.
            String virtual = readVirtualDirectory(indexFile);
            if (virtual != null) {
                legacyAssetsDir = assetsDir.resolve(virtual).resolve(assetIndexId);
                linkVirtualAssets(objects, legacyAssetsDir);
            }
        }

        // ---- logging config --------------------------------------------
        Path loggingConfig = null;
        JsonObject logging = meta.getAsJsonObject("logging");
        if (logging != null && logging.has("client")) {
            JsonObject loggingClient = logging.getAsJsonObject("client");
            JsonObject file = loggingClient.getAsJsonObject("file");
            if (file != null && file.has("id") && file.has("url")) {
                Path configPath = assetsDir.resolve("log_configs").resolve(file.get("id").getAsString());
                api.downloadIfMissing(file.get("url").getAsString(), configPath);
                loggingConfig = configPath;
            }
        }

        int requiredJava = 8;
        if (meta.has("javaVersion")) {
            JsonObject javaVersion = meta.getAsJsonObject("javaVersion");
            if (javaVersion.has("majorVersion")) {
                requiredJava = javaVersion.get("majorVersion").getAsInt();
            }
        }

        progress.update("Ready", 1.0);
        return new PreparedInstall(versionId, resolved.versionType(), meta, gameDir, assetsDir,
                assetIndexId, legacyAssetsDir, nativesDir, clientJar, List.copyOf(classpath),
                loggingConfig, requiredJava);
    }

    private void downloadAssets(JsonObject objects, Path assetsDir, Progress progress) throws IOException {
        if (objects == null || objects.isEmpty()) {
            return;
        }
        Path objectDir = assetsDir.resolve("objects");
        List<Map.Entry<String, JsonElement>> pending = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            Path target = objectDir.resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.exists(target) || sizeOf(target) != object.get("size").getAsLong()) {
                pending.add(entry);
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        int total = pending.size();
        int[] done = {0};
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Map.Entry<String, JsonElement> entry : pending) {
                futures.add(pool.submit(() -> {
                    JsonObject object = entry.getValue().getAsJsonObject();
                    String hash = object.get("hash").getAsString();
                    Path target = objectDir.resolve(hash.substring(0, 2)).resolve(hash);
                    api.downloadIfMissing(MojangApi.RESOURCES_URL + hash.substring(0, 2) + "/" + hash, target);
                    int current;
                    synchronized (done) {
                        current = ++done[0];
                    }
                    progress.update("Assets " + current + "/" + total,
                            0.65 + 0.3 * ((double) current / total));
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException("Asset download failed", cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Asset download interrupted", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void linkVirtualAssets(JsonObject objects, Path virtualDir) throws IOException {
        if (objects == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            Path source = gameDir.resolve("assets").resolve("objects")
                    .resolve(hash.substring(0, 2)).resolve(hash);
            Path target = virtualDir.resolve(entry.getKey());
            if (!Files.exists(source) || Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            try {
                Files.createLink(target, source);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String readVirtualDirectory(Path indexFile) {
        try {
            JsonObject index = com.google.gson.JsonParser
                    .parseString(Files.readString(indexFile)).getAsJsonObject();
            if (!index.has("virtual")) {
                return null;
            }
            return index.get("virtual").getAsBoolean() ? "virtual" : "resources";
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void extractNatives(Path jar, Path nativesDir, JsonObject library) throws IOException {
        if (!Files.exists(jar)) {
            return;
        }
        List<String> excludes = new ArrayList<>();
        JsonObject extract = library.getAsJsonObject("extract");
        if (extract != null && extract.has("exclude")) {
            for (JsonElement element : extract.getAsJsonArray("exclude")) {
                excludes.add(element.getAsString());
            }
        }

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") || isExcluded(name, excludes)) {
                    continue;
                }
                Path target = nativesDir.resolve(name).normalize();
                if (!target.startsWith(nativesDir)) {
                    continue;
                }
                if (Files.exists(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean isExcluded(String name, List<String> excludes) {
        for (String pattern : excludes) {
            String prefix = pattern.replace("**", "").replace("*", "");
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Turns {@code group:artifact:version[:classifier][@ext]} into a maven repository path. */
    private static String mavenPath(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String extension = "jar";
        int at = name.indexOf('@');
        if (at >= 0) {
            extension = name.substring(at + 1);
            name = name.substring(0, at);
        }
        String[] parts = name.split(":");
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        String classifier = parts.length > 3 && !parts[3].isBlank() ? "-" + parts[3] : "";
        String fileName = parts[1] + "-" + parts[2] + classifier + "." + extension;
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + fileName;
    }

    private static String mavenUrl(JsonObject library, String path) {
        String base = library.has("url") ? library.get("url").getAsString() : MojangApi.LIBRARIES_URL;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + path;
    }

    private static String shortName(String mavenName) {
        String[] parts = mavenName.split(":");
        return parts.length >= 2 ? parts[1] : mavenName;
    }

    private static boolean is64Bit() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("64") || arch.contains("aarch");
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
