package com.mixclient.launcher.core;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/** Version of the running launcher, taken from the jar manifest so one release is one number. */
public final class BuildInfo {

    private BuildInfo() {
    }

    public static final String VERSION = read();

    /**
     * The jar this launcher was started from, or {@code null} when it runs from a classes
     * directory (an IDE run). The updater replaces this file so the next start is new too.
     */
    public static Path jarPath() {
        try {
            URL location = BuildInfo.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path path = Path.of(location.toURI());
            return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")
                    ? path.toAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String read() {
        String version = BuildInfo.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "1.0.0" : version;
    }
}
