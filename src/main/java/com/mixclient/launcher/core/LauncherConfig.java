package com.mixclient.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Launcher settings, persisted next to {@code accounts.json}. */
public final class LauncherConfig {

    /** Aikar-style G1 flags: the default tuning used by most PvP clients. */
    public static final String DEFAULT_PVP_JVM_ARGS = String.join(" ",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapRegionSize=8M",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem",
            "-XX:MaxTenuringThreshold=1");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Game directory; empty means {@link AppPaths#gameDir()}. */
    public String gameDir = "";
    /** Java executable used for the game; empty means the JVM running the launcher. */
    public String javaPath = "";
    public int maxMemoryMb = 4096;
    public int minMemoryMb = 1024;
    public int windowWidth = 1280;
    public int windowHeight = 720;
    /** Extra JVM flags appended after the memory flags. */
    public String jvmArgs = DEFAULT_PVP_JVM_ARGS;
    public boolean pvpOptimizations = true;
    public String selectedVersion = "";
    public String lastAccountId = "";
    public boolean closeLauncherOnLaunch = false;

    public static LauncherConfig load() {
        Path file = AppPaths.configFile();
        if (!Files.exists(file)) {
            LauncherConfig fresh = new LauncherConfig();
            fresh.save();
            return fresh;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            LauncherConfig loaded = GSON.fromJson(json, LauncherConfig.class);
            return loaded != null ? loaded.withDefaults() : new LauncherConfig();
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("[config] Could not read " + file + ": " + e);
            return new LauncherConfig();
        }
    }

    private LauncherConfig withDefaults() {
        if (maxMemoryMb < 512) {
            maxMemoryMb = 4096;
        }
        if (minMemoryMb < 256) {
            minMemoryMb = 1024;
        }
        if (jvmArgs == null) {
            jvmArgs = DEFAULT_PVP_JVM_ARGS;
        }
        if (windowWidth < 320) {
            windowWidth = 1280;
        }
        if (windowHeight < 240) {
            windowHeight = 720;
        }
        if (gameDir == null) {
            gameDir = "";
        }
        if (javaPath == null) {
            javaPath = "";
        }
        if (selectedVersion == null) {
            selectedVersion = "";
        }
        if (lastAccountId == null) {
            lastAccountId = "";
        }
        return this;
    }

    public void save() {
        try {
            AppPaths.ensureDirectories();
            Path file = AppPaths.configFile();
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[config] Could not save settings: " + e);
        }
    }
}
