package com.mixclient.launcher.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mixclient.launcher.core.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Thin client for the public Mojang metadata and download endpoints. */
public final class MojangApi {

    public static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String RESOURCES_URL = "https://resources.download.minecraft.net/";
    public static final String LIBRARIES_URL = "https://libraries.minecraft.net/";

    private static final String USER_AGENT = "MixClient/1.0";
    private static final Duration MANIFEST_MAX_AGE = Duration.ofMinutes(30);

    public record VersionSummary(String id, String type, String url, String releaseTime) {
        public boolean isRelease() {
            return "release".equals(type);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
    private final Gson gson = new Gson();
    private final Path cacheDir;

    public MojangApi(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** All shipped versions, newest first, from the cached or freshly downloaded manifest. */
    public List<VersionSummary> versions() throws IOException {
        JsonObject manifest = manifest();
        List<VersionSummary> result = new ArrayList<>();
        for (var element : manifest.getAsJsonArray("versions")) {
            JsonObject version = element.getAsJsonObject();
            result.add(new VersionSummary(
                    version.get("id").getAsString(),
                    version.get("type").getAsString(),
                    version.get("url").getAsString(),
                    version.has("releaseTime") ? version.get("releaseTime").getAsString() : ""));
        }
        return result;
    }

    public String latestRelease() throws IOException {
        String id = manifest().getAsJsonObject("latest").get("release").getAsString();
        return id;
    }

    public VersionSummary version(String id) throws IOException {
        for (VersionSummary version : versions()) {
            if (version.id().equals(id)) {
                return version;
            }
        }
        throw new IOException("Unknown Minecraft version: " + id);
    }

    public JsonObject fetchJson(String url) throws IOException {
        HttpResponse<String> response = send(URI.create(url), HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    public JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private JsonObject manifest() throws IOException {
        Path cached = cacheDir.resolve("version_manifest_v2.json");
        boolean fresh = false;
        if (Files.exists(cached)) {
            try {
                Instant modified = Files.getLastModifiedTime(cached).toInstant();
                fresh = modified.plus(MANIFEST_MAX_AGE).isAfter(Instant.now());
            } catch (IOException ignored) {
                // Treat an unreadable timestamp as stale.
            }
        }

        if (fresh) {
            return readJson(cached);
        }

        try {
            JsonObject manifest = fetchJson(VERSION_MANIFEST_URL);
            Files.createDirectories(cacheDir);
            Files.writeString(cached, gson.toJson(manifest), StandardCharsets.UTF_8);
            return manifest;
        } catch (IOException e) {
            if (Files.exists(cached)) {
                // Offline with an old manifest is better than failing outright.
                return readJson(cached);
            }
            throw new IOException("Couldn't download the Minecraft version list. Check your connection.", e);
        }
    }

    /**
     * Downloads {@code url} to {@code target} if it is missing, streaming to a {@code .part} file
     * first so an interrupted download never leaves a corrupt jar behind.
     */
    public void downloadIfMissing(String url, Path target) throws IOException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }
        download(url, target, null);
    }

    public void download(String url, Path target, Progress progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(90))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    try (InputStream ignored = response.body()) {
                        // Drain so the connection can be reused.
                    }
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                try (InputStream body = response.body()) {
                    Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted: " + url, interrupted);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted: " + url, e);
            }
        }
        throw new IOException("Couldn't download " + url + (last != null ? " (" + last.getMessage() + ")" : ""), last);
    }

    private <T> HttpResponse<T> send(URI uri, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted: " + uri, e);
        } catch (IOException e) {
            throw new IOException("Couldn't reach " + uri.getHost() + ". Check your connection.", e);
        }
    }
}
