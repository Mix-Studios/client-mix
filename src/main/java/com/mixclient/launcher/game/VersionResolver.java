package com.mixclient.launcher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mixclient.launcher.core.Progress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads a version JSON from {@code versions/<id>/<id>.json} (already installed profiles such as
 * Fabric/Forge win) or downloads it from Mojang, then flattens {@code inheritsFrom} chains.
 */
public final class VersionResolver {

    /** Merged version data plus which version actually owns the client jar. */
    public record Resolved(JsonObject json, String versionId, String clientJarVersionId, String versionType) {
    }

    private VersionResolver() {
    }

    public static Resolved resolve(MojangApi api, Path gameDir, String versionId, Progress progress) throws IOException {
        JsonObject child = loadVersion(api, gameDir, versionId, progress);
        String clientJarVersionId = versionId;
        String parentId = stringOrNull(child, "inheritsFrom");

        JsonObject merged = child.deepCopy();
        JsonObject current = child;
        int depth = 0;

        while (parentId != null && !parentId.isBlank() && depth++ < 8) {
            progress.update("Resolving parent version " + parentId, -1);
            JsonObject parent = loadVersion(api, gameDir, parentId, progress);
            if (parent.getAsJsonObject("downloads") != null
                    && parent.getAsJsonObject("downloads").has("client")
                    && !clientJarVersionId.equals(parentId)) {
                clientJarVersionId = parentId;
            }
            merged = merge(current, parent);
            current = merged;
            parentId = stringOrNull(parent, "inheritsFrom");
        }

        String type = stringOrNull(merged, "type");
        if (type == null) {
            type = "release";
        }
        return new Resolved(merged, versionId, clientJarVersionId, type);
    }

    private static JsonObject loadVersion(MojangApi api, Path gameDir, String versionId, Progress progress)
            throws IOException {
        Path jsonPath = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (Files.exists(jsonPath)) {
            return api.readJson(jsonPath);
        }

        progress.update("Downloading version metadata for " + versionId, -1);
        MojangApi.VersionSummary summary = api.version(versionId);
        api.downloadIfMissing(summary.url(), jsonPath);
        if (!Files.exists(jsonPath)) {
            throw new IOException("Version " + versionId + " isn't installed and couldn't be downloaded.");
        }
        return api.readJson(jsonPath);
    }

    /**
     * Child values win for scalars; libraries and arguments are concatenated child-first, which is
     * what the official launcher does for {@code inheritsFrom} profiles.
     */
    private static JsonObject merge(JsonObject child, JsonObject parent) {
        JsonObject merged = child.deepCopy();

        for (String key : parent.keySet()) {
            boolean missing = !merged.has(key) || merged.get(key).isJsonNull();
            if (missing && !"libraries".equals(key) && !"arguments".equals(key)
                    && !"inheritsFrom".equals(key)) {
                merged.add(key, parent.get(key).deepCopy());
            }
        }

        JsonArray libraries = new JsonArray();
        addAll(libraries, child.getAsJsonArray("libraries"));
        addAll(libraries, parent.getAsJsonArray("libraries"));
        if (!libraries.isEmpty() || merged.has("libraries")) {
            merged.add("libraries", libraries);
        }

        JsonObject arguments = new JsonObject();
        JsonObject childArgs = child.getAsJsonObject("arguments");
        JsonObject parentArgs = parent.getAsJsonObject("arguments");
        JsonArray gameArgs = new JsonArray();
        JsonArray jvmArgs = new JsonArray();
        if (childArgs != null) {
            addAll(gameArgs, childArgs.getAsJsonArray("game"));
            addAll(jvmArgs, childArgs.getAsJsonArray("jvm"));
        }
        if (parentArgs != null) {
            addAll(gameArgs, parentArgs.getAsJsonArray("game"));
            addAll(jvmArgs, parentArgs.getAsJsonArray("jvm"));
        }
        if (!gameArgs.isEmpty()) {
            arguments.add("game", gameArgs);
        }
        if (!jvmArgs.isEmpty()) {
            arguments.add("jvm", jvmArgs);
        }
        if (!arguments.isEmpty() || merged.has("arguments")) {
            merged.add("arguments", arguments);
        }

        if (!merged.has("minecraftArguments") && parent.has("minecraftArguments")) {
            merged.addProperty("minecraftArguments", parent.get("minecraftArguments").getAsString());
        }
        return merged;
    }

    private static void addAll(JsonArray target, JsonArray source) {
        if (source == null) {
            return;
        }
        for (JsonElement element : source) {
            target.add(element.deepCopy());
        }
    }

    private static String stringOrNull(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    // ---------------------------------------------------------------- rules

    public static Map<String, Boolean> defaultFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("has_custom_resolution", true);
        features.put("is_demo_user", false);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);
        return features;
    }

    /** Applies Minecraft's allow/disallow rule list for the current OS and features. */
    public static boolean rulesAllowed(JsonElement rules, Map<String, Boolean> features) {
        if (rules == null || !rules.isJsonArray() || rules.getAsJsonArray().isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules.getAsJsonArray()) {
            JsonObject rule = element.getAsJsonObject();
            if (ruleMatches(rule, features)) {
                allowed = "allow".equals(rule.has("action") ? rule.get("action").getAsString() : "allow");
            }
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject rule, Map<String, Boolean> features) {
        if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !os.get("name").getAsString().equalsIgnoreCase(osName())) {
                return false;
            }
            if (os.has("arch") && !archMatches(os.get("arch").getAsString())) {
                return false;
            }
            if (os.has("version")) {
                String regex = os.get("version").getAsString();
                String osVersion = System.getProperty("os.version", "");
                try {
                    if (!Pattern.compile(regex).matcher(osVersion).find()) {
                        return false;
                    }
                } catch (RuntimeException ignored) {
                    // A malformed pattern should not hide the game.
                }
            }
        }
        if (rule.has("features")) {
            JsonObject required = rule.getAsJsonObject("features");
            for (String key : required.keySet()) {
                boolean expected = required.get(key).getAsBoolean();
                if (features.getOrDefault(key, false) != expected) {
                    return false;
                }
            }
        }
        return true;
    }

    public static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    private static boolean archMatches(String arch) {
        String normalized = arch.toLowerCase(Locale.ROOT);
        boolean is64 = System.getProperty("os.arch", "").contains("64") || "aarch64".equals(System.getProperty("os.arch"));
        boolean isArm = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).contains("aarch");
        return switch (normalized) {
            case "x86" -> !is64;
            case "x86_64", "amd64" -> is64 && !isArm;
            case "arm64", "aarch64" -> isArm;
            default -> true;
        };
    }
}
