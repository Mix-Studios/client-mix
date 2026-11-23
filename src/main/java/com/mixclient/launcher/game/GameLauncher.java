package com.mixclient.launcher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mixclient.launcher.auth.PlayerSession;
import com.mixclient.launcher.core.BuildInfo;
import com.mixclient.launcher.core.LauncherConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns a {@link PreparedInstall} plus a signed-in {@link PlayerSession} into a Java command line
 * and starts the game.
 *
 * <p>The session comes straight from {@code JavaAuthManager}: the profile name, the undashed UUID
 * and the Minecraft access token are passed as {@code --username}, {@code --uuid} and
 * {@code --accessToken} with {@code --userType msa}.</p>
 */
public final class GameLauncher {

    private static final String LAUNCHER_BRAND = "MixClient";
    private static final String LAUNCHER_VERSION = BuildInfo.VERSION;

    private GameLauncher() {
    }

    public static Process start(PreparedInstall install, PlayerSession session, LauncherConfig config,
                                Consumer<String> logSink, Consumer<Integer> onExit) throws IOException {
        List<String> command = buildCommand(install, session, config);
        logSink.accept("$ " + String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(install.gameDir().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();

        Thread pump = new Thread(() -> {
            try (InputStream in = process.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logSink.accept(line);
                }
            } catch (IOException ignored) {
                // The process closing its streams is normal on exit.
            }
        }, "game-log");
        pump.setDaemon(true);
        pump.start();

        process.onExit().thenAccept(p -> onExit.accept(p.exitValue()));
        return process;
    }

    /** Visible for diagnostics and for tests: the exact command used to start the game. */
    public static List<String> buildCommand(PreparedInstall install, PlayerSession session,
                                            LauncherConfig config) throws IOException {
        JsonObject meta = install.meta();
        Map<String, Boolean> features = VersionResolver.defaultFeatures();

        String classpath = buildClasspath(install);
        Map<String, String> vars = placeholders(install, session, classpath, config);

        List<String> command = new ArrayList<>();
        // Each version gets the Java it was built for: 1.8.9 wants an Intel Java 8, 26.3 needs 25+.
        command.add(JavaRuntime.forVersion(install.versionId(), install.requiredJavaMajor(), config)
                .toString());
        command.add("-Xms" + config.minMemoryMb + "M");
        command.add("-Xmx" + config.maxMemoryMb + "M");
        command.addAll(splitArguments(config.jvmArgs));
        command.add("-Dminecraft.launcher.brand=" + LAUNCHER_BRAND);
        command.add("-Dminecraft.launcher.version=" + LAUNCHER_VERSION);

        if (install.loggingConfig() != null) {
            String loggingArgument = loggingArgument(meta);
            if (loggingArgument != null) {
                command.add(expand(loggingArgument, Map.of("path", install.loggingConfig().toString())));
            }
        }

        JsonObject arguments = meta.getAsJsonObject("arguments");
        boolean hasMetaJvmArgs = arguments != null && arguments.has("jvm");
        if (hasMetaJvmArgs) {
            for (String arg : collectArguments(arguments.getAsJsonArray("jvm"), features, vars)) {
                command.add(arg);
            }
        } else {
            command.add("-Djava.library.path=" + install.nativesDir());
            command.add("-cp");
            command.add(classpath);
        }

        String mainClass = meta.has("mainClass") ? meta.get("mainClass").getAsString() : "net.minecraft.client.main.Main";
        command.add(mainClass);

        if (arguments != null && arguments.has("game")) {
            command.addAll(collectArguments(arguments.getAsJsonArray("game"), features, vars));
        } else if (meta.has("minecraftArguments")) {
            for (String token : meta.get("minecraftArguments").getAsString().split(" ")) {
                if (!token.isEmpty()) {
                    command.add(expand(token, vars));
                }
            }
        }
        return command;
    }

    private static List<String> collectArguments(JsonArray elements, Map<String, Boolean> features,
                                                 Map<String, String> vars) {
        List<String> result = new ArrayList<>();
        if (elements == null) {
            return result;
        }
        for (JsonElement element : elements) {
            if (element.isJsonPrimitive()) {
                result.add(expand(element.getAsString(), vars));
                continue;
            }
            JsonObject conditional = element.getAsJsonObject();
            if (!VersionResolver.rulesAllowed(conditional.get("rules"), features)) {
                continue;
            }
            JsonElement value = conditional.get("value");
            if (value == null) {
                continue;
            }
            if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    result.add(expand(item.getAsString(), vars));
                }
            } else {
                result.add(expand(value.getAsString(), vars));
            }
        }
        return result;
    }

    private static Map<String, String> placeholders(PreparedInstall install, PlayerSession session,
                                                    String classpath, LauncherConfig config) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("auth_player_name", session.username());
        vars.put("auth_uuid", session.uuid());
        vars.put("auth_access_token", session.accessToken());
        vars.put("auth_session", "token:" + session.accessToken() + ":" + session.uuid());
        vars.put("auth_xuid", session.xuid() == null ? "" : session.xuid());
        vars.put("clientid", "");
        vars.put("user_type", "msa");
        vars.put("user_properties", "{}");
        vars.put("version_name", install.versionId());
        vars.put("version_type", install.versionType());
        vars.put("game_directory", install.gameDir().toString());
        vars.put("assets_root", install.assetsDir().toString());
        vars.put("game_assets", install.legacyAssetsDir() != null
                ? install.legacyAssetsDir().toString() : install.assetsDir().toString());
        vars.put("assets_index_name", install.assetIndexId());
        vars.put("natives_directory", install.nativesDir().toString());
        vars.put("library_directory", install.gameDir().resolve("libraries").toString());
        vars.put("classpath", classpath);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("primary_jar", install.clientJar().toString());
        vars.put("resolution_width", String.valueOf(config.windowWidth));
        vars.put("resolution_height", String.valueOf(config.windowHeight));
        vars.put("launcher_name", LAUNCHER_BRAND);
        vars.put("launcher_version", LAUNCHER_VERSION);
        return vars;
    }

    private static String buildClasspath(PreparedInstall install) {
        List<String> entries = new ArrayList<>();
        entries.add(install.clientJar().toString());
        for (Path path : install.classpath()) {
            String value = path.toString();
            if (!entries.contains(value)) {
                entries.add(value);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static String loggingArgument(JsonObject meta) {
        JsonObject logging = meta.getAsJsonObject("logging");
        if (logging == null || !logging.has("client")) {
            return null;
        }
        JsonObject client = logging.getAsJsonObject("client");
        return client.has("argument") ? client.get("argument").getAsString() : null;
    }

    public static Path javaExecutable(LauncherConfig config) {
        if (config.javaPath != null && !config.javaPath.isBlank()) {
            return Path.of(config.javaPath.trim());
        }
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path bundled = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.exists(bundled) ? bundled : Path.of("java");
    }

    private static String expand(String value, Map<String, String> vars) {
        String result = value;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    static List<String> splitArguments(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String token : raw.trim().split("\\s+")) {
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }
}
