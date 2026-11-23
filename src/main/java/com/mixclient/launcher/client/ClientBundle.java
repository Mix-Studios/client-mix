package com.mixclient.launcher.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Installs the MixClient mod that ships inside the launcher.
 *
 * <p>The build embeds client jars under {@code /client/<game version>/} in the launcher jar and
 * writes {@code /client/index.json} next to them. Before Minecraft starts, the module that
 * matches the selected game version is copied into {@code mods/} and any module from an older
 * launcher is removed, so the client and the launcher are always the same version and players
 * never install the client by hand.</p>
 */
public final class ClientBundle {

    private static final String INDEX = "/client/index.json";
    private static final String STATE_FILE = ".mixclient-client.json";

    /** One bundled mod jar and the game versions it belongs to. */
    public record Module(String gameVersion, String file) {
    }

    public record Result(String clientVersion, List<String> installed, List<String> removed) {
        public boolean isEmpty() {
            return installed.isEmpty() && removed.isEmpty();
        }
    }

    private ClientBundle() {
    }

    /** Reads the bundle index from the launcher jar; empty when this build embeds no client. */
    public static List<Module> modules() {
        try (InputStream in = ClientBundle.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray modules = root.getAsJsonArray("modules");
            if (modules == null) {
                return List.of();
            }
            List<Module> result = new ArrayList<>();
            for (JsonElement element : modules) {
                JsonObject module = element.getAsJsonObject();
                result.add(new Module(
                        module.get("gameVersion").getAsString(),
                        module.get("file").getAsString()));
            }
            return result;
        } catch (IOException | RuntimeException e) {
            System.err.println("[client] Could not read " + INDEX + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Puts the bundled client for {@code versionId} into {@code gameDir/mods}.
     *
     * @param versionId the Minecraft version the player is about to launch, e.g. {@code 1.8.9}
     */
    public static Result install(String versionId, Path gameDir) throws IOException {
        List<Module> modules = modules();
        if (modules.isEmpty()) {
            return new Result("", List.of(), List.of());
        }

        Path modsDir = gameDir.resolve("mods");
        Set<String> previous = readState(modsDir);
        Set<String> wanted = new LinkedHashSet<>();

        for (Module module : modules) {
            if (matches(module.gameVersion(), versionId)) {
                wanted.add(module.file());
            }
        }

        List<String> installed = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        if (!wanted.isEmpty()) {
            Files.createDirectories(modsDir);
        }

        for (String stale : previous) {
            if (wanted.contains(stale)) {
                continue;
            }
            Path file = modsDir.resolve(stale);
            if (Files.deleteIfExists(file)) {
                removed.add(stale);
            }
        }

        for (Module module : modules) {
            if (!wanted.contains(module.file())) {
                continue;
            }
            Path target = modsDir.resolve(module.file());
            Path temp = modsDir.resolve(module.file() + ".part");
            try (InputStream in = ClientBundle.class.getResourceAsStream(
                    "/client/" + module.gameVersion() + "/" + module.file())) {
                if (in == null) {
                    continue;
                }
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            installed.add(module.file());
        }

        writeState(modsDir, wanted);
        return new Result("", installed, removed);
    }

    /** True when the module is built for this game version: exact match or a longer version. */
    private static boolean matches(String moduleVersion, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        return versionId.equals(moduleVersion) || versionId.startsWith(moduleVersion + ".");
    }

    private static Set<String> readState(Path modsDir) {
        Path state = modsDir.resolve(STATE_FILE);
        if (!Files.exists(state)) {
            return Set.of();
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(state, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray files = root.getAsJsonArray("files");
            Set<String> result = new LinkedHashSet<>();
            if (files != null) {
                for (JsonElement element : files) {
                    result.add(element.getAsString());
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    private static void writeState(Path modsDir, Set<String> files) throws IOException {
        if (files.isEmpty()) {
            Files.deleteIfExists(modsDir.resolve(STATE_FILE));
            return;
        }
        JsonArray array = new JsonArray();
        files.forEach(array::add);
        JsonObject root = new JsonObject();
        root.add("files", array);
        Files.writeString(modsDir.resolve(STATE_FILE), root.toString(), StandardCharsets.UTF_8);
    }
}
