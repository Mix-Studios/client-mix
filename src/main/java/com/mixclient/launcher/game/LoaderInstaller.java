package com.mixclient.launcher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs the mod loader the bundled client runs on.
 *
 * <p>A vanilla launch ignores {@code mods/}, so the launcher starts Minecraft through a loader
 * profile instead: Forge for 1.8.9 and Fabric for 26.3. A loader is written as an ordinary
 * {@code versions/<id>/<id>.json} that inherits from the vanilla version, so
 * {@link VersionResolver} merges it with 1.8.9/26.3 and {@link AssetInstaller} installs the
 * loader's libraries, assets and natives the same way it installs everything else.</p>
 */
public final class LoaderInstaller {

    /** The loader profile to launch instead of the vanilla version. */
    public record Loader(String versionId, String name) {
    }

    private static final String FORGE_MINECRAFT = "1.8.9";
    private static final String FORGE_VERSION = "1.8.9-11.15.1.2318-1.8.9";
    private static final String FORGE_PROFILE_ID = "1.8.9-forge11.15.1.2318";
    private static final String FORGE_NAME = "Forge 11.15.1.2318";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    private static final String FORGE_INSTALLER_URL = FORGE_MAVEN + "net/minecraftforge/forge/"
            + FORGE_VERSION + "/forge-" + FORGE_VERSION + "-installer.jar";

    private static final String FABRIC_MINECRAFT = "26.3";
    private static final String FABRIC_LOADER_VERSION = "0.20.1";
    private static final String FABRIC_PROFILE_ID =
            "fabric-loader-" + FABRIC_LOADER_VERSION + "-" + FABRIC_MINECRAFT;
    private static final String FABRIC_NAME = "Fabric Loader " + FABRIC_LOADER_VERSION;
    private static final String FABRIC_PROFILE_URL = "https://meta.fabricmc.net/v2/versions/loader/"
            + FABRIC_MINECRAFT + "/" + FABRIC_LOADER_VERSION + "/profile/json";
    private static final String FABRIC_API_VERSIONS_URL =
            "https://api.modrinth.com/v2/project/fabric-api/version"
                    + "?game_versions=%5B%22" + FABRIC_MINECRAFT + "%22%5D&loaders=%5B%22fabric%22%5D";

    private LoaderInstaller() {
    }

    /**
     * Returns what to launch for the selected version, installing the loader first if needed.
     * Versions without a bundled client are launched as they are.
     */
    public static Loader ensure(String versionId, Path gameDir, Progress progress) throws IOException {
        if (FORGE_MINECRAFT.equals(versionId)) {
            return forge(gameDir, progress);
        }
        if (FABRIC_MINECRAFT.equals(versionId)) {
            return fabric(gameDir, progress);
        }
        return new Loader(versionId, "");
    }

    /** True for the profiles this class writes, so the version list only offers real versions. */
    public static boolean isLoaderProfile(String versionId) {
        return FORGE_PROFILE_ID.equals(versionId) || versionId.startsWith("fabric-loader-");
    }

    // ---------------------------------------------------------------- forge

    private static Loader forge(Path gameDir, Progress progress) throws IOException {
        Path profile = profileFile(gameDir, FORGE_PROFILE_ID);
        if (Files.exists(profile)) {
            return new Loader(FORGE_PROFILE_ID, FORGE_NAME);
        }

        progress.update("Installing " + FORGE_NAME, 0.03);
        MojangApi api = new MojangApi(AppPaths.cacheDir());
        Path installer = AppPaths.cacheDir().resolve("loaders")
                .resolve("forge-" + FORGE_VERSION + "-installer.jar");
        api.downloadIfMissing(FORGE_INSTALLER_URL, installer);

        JsonObject versionInfo;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            ZipEntry entry = zip.getEntry("install_profile.json");
            if (entry == null) {
                throw new IOException("The Forge " + FORGE_VERSION + " installer has no install_profile.json.");
            }
            JsonObject root;
            try (InputStream in = zip.getInputStream(entry)) {
                root = JsonParser.parseString(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            versionInfo = root.getAsJsonObject("versionInfo");
        }
        if (versionInfo == null) {
            throw new IOException("The Forge " + FORGE_VERSION + " installer has no version information.");
        }

        versionInfo.addProperty("id", FORGE_PROFILE_ID);

        // Forge 1.8.9 is only published as the "universal" artifact; the profile names the jar
        // without that classifier, which is a 404 on the maven.
        JsonArray libraries = versionInfo.getAsJsonArray("libraries");
        if (libraries != null) {
            for (JsonElement element : libraries) {
                JsonObject library = element.getAsJsonObject();
                String name = library.has("name") ? library.get("name").getAsString() : "";
                if (name.equals("net.minecraftforge:forge:" + FORGE_VERSION)) {
                    library.addProperty("name", name + ":universal");
                }
            }
        }

        // The client jar declares TweakClass: MixinTweaker in its manifest, and Forge loads that
        // tweaker from any mod in mods/, so no launch argument is added here.
        writeProfile(profile, versionInfo);
        return new Loader(FORGE_PROFILE_ID, FORGE_NAME);
    }

    // --------------------------------------------------------------- fabric

    private static Loader fabric(Path gameDir, Progress progress) throws IOException {
        Path profile = profileFile(gameDir, FABRIC_PROFILE_ID);
        if (!Files.exists(profile)) {
            progress.update("Installing " + FABRIC_NAME, 0.03);
            MojangApi api = new MojangApi(AppPaths.cacheDir());
            JsonObject metadata = api.fetchJson(FABRIC_PROFILE_URL);
            metadata.addProperty("id", FABRIC_PROFILE_ID);
            writeProfile(profile, metadata);
        }

        // The bundled Fabric mod depends on Fabric API, which the loader profile does not ship.
        ensureFabricApi(gameDir, progress);
        return new Loader(FABRIC_PROFILE_ID, FABRIC_NAME);
    }

    private static void ensureFabricApi(Path gameDir, Progress progress) throws IOException {
        Path mods = gameDir.resolve("mods");
        if (hasFabricApi(mods)) {
            return;
        }

        progress.update("Installing Fabric API", 0.04);
        MojangApi api = new MojangApi(AppPaths.cacheDir());
        JsonArray versions = api.fetchJsonArray(FABRIC_API_VERSIONS_URL);

        JsonObject chosen = null;
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            if (!"release".equals(string(version, "version_type"))) {
                continue;
            }
            if (primaryFile(version) != null) {
                chosen = primaryFile(version);
                break;
            }
        }
        if (chosen == null) {
            for (JsonElement element : versions) {
                JsonObject file = primaryFile(element.getAsJsonObject());
                if (file != null) {
                    chosen = file;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new IOException("No Fabric API build for Minecraft " + FABRIC_MINECRAFT + " was found.");
        }

        String filename = chosen.get("filename").getAsString();
        api.download(chosen.get("url").getAsString(), mods.resolve(filename), progress);
    }

    private static JsonObject primaryFile(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null) {
            return null;
        }
        JsonObject first = null;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (!file.has("url") || !file.has("filename")) {
                continue;
            }
            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                return file;
            }
            if (first == null) {
                first = file;
            }
        }
        return first;
    }

    private static boolean hasFabricApi(Path mods) {
        if (!Files.isDirectory(mods)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "fabric-api-*.jar")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- files

    private static Path profileFile(Path gameDir, String versionId) {
        return gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
    }

    private static void writeProfile(Path profile, JsonObject json) throws IOException {
        Files.createDirectories(profile.getParent());
        Path temp = profile.resolveSibling(profile.getFileName() + ".part");
        Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
        Files.move(temp, profile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
