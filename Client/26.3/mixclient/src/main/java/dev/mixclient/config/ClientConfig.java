package dev.mixclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings for MixClient, stored in {@code config/mixclient.json}. */
public final class ClientConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The Discord application that owns the Rich Presence assets. Create one at
     * https://discord.com/developers/applications, upload the logo as an asset named
     * {@code mixclient}, and put its application id here (or in the config file).
     */
    public String discordAppId = "";

    public boolean performanceTweaks = true;

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("mixclient.json");
    }

    public static ClientConfig load() {
        Path file = path();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[MixClient] Could not read " + file + ": " + e.getMessage());
            }
        }

        ClientConfig config = new ClientConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[MixClient] Could not write " + path() + ": " + e.getMessage());
        }
    }
}
