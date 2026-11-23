package com.mixclient.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where MixClient keeps its data.
 *
 * <p>The launcher writes everything it owns into one directory so that uninstalling is a single
 * folder delete. The game files themselves live in {@link #gameDir()} which defaults to
 * {@code <app dir>/game} and can be pointed at an existing {@code .minecraft} folder in Settings.</p>
 */
public final class AppPaths {

    private static final String APP_NAME = "MixClient";

    private AppPaths() {
    }

    /** Root of all launcher data; override with the {@code MIXCLIENT_HOME} environment variable. */
    public static Path appDir() {
        String override = System.getenv("MIXCLIENT_HOME");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null && !appData.isBlank()
                    ? Path.of(appData)
                    : Path.of(home, "AppData", "Roaming");
            return base.resolve(APP_NAME);
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", APP_NAME);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
        return base.resolve(APP_NAME.toLowerCase(Locale.ROOT));
    }

    /** Serialized {@code JavaAuthManager} sessions for every saved account. */
    public static Path accountsFile() {
        return appDir().resolve("accounts.json");
    }

    /** Launcher settings (memory, JVM args, selected version, game directory...). */
    public static Path configFile() {
        return appDir().resolve("config.json");
    }

    /** Default game directory; may be overridden in the config file. */
    public static Path gameDir() {
        return appDir().resolve("game");
    }

    /** Cached Mojang metadata (version manifest, version JSONs, asset indexes). */
    public static Path cacheDir() {
        return appDir().resolve("cache");
    }

    /** Launcher log, useful when the UI cannot be opened. */
    public static Path logFile() {
        return appDir().resolve("launcher.log");
    }

    public static void ensureDirectories() {
        try {
            Files.createDirectories(appDir());
            Files.createDirectories(cacheDir());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create launcher directory " + appDir(), e);
        }
    }
}
