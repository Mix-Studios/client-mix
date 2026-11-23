package com.mixclient.launcher.game;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/** Everything the launch command needs after files have been installed. */
public record PreparedInstall(
        String versionId,
        String versionType,
        JsonObject meta,
        Path gameDir,
        Path assetsDir,
        String assetIndexId,
        Path legacyAssetsDir,
        Path nativesDir,
        Path clientJar,
        List<Path> classpath,
        Path loggingConfig,
        int requiredJavaMajor) {
}
