package com.mixclient.launcher.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mixclient.launcher.core.BuildInfo;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Keeps the launcher current from its own GitHub releases.
 *
 * <p>A release carries the launcher jar, the installers and {@code checksums.txt}, and because the
 * client is bundled into the jar a new client always arrives as a new launcher version. The check
 * compares the release tag with {@link BuildInfo#VERSION}; installing downloads the asset, checks
 * its SHA-256 against the release's checksums and either opens the platform installer (when
 * MixClient was installed by one) or restarts the freshly downloaded jar.</p>
 */
public final class Updater {

    public static final String REPOSITORY = "Mix-Studios/client-mix";

    private static final String LATEST_RELEASE =
            "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String USER_AGENT = "MixClient-Launcher/" + BuildInfo.VERSION;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** What one release offers for this machine. */
    public record Release(String tag, String version, String jarName, String jarUrl,
                          String installerName, String installerUrl, String checksumUrl,
                          long size) {
    }

    private Updater() {
    }

    /** Asks GitHub for the newest release. */
    public static Release check() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub returned " + response.statusCode()
                    + " for the latest release" + rateLimitHint(response));
        }

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        if (release.has("draft") && release.get("draft").getAsBoolean()) {
            throw new IOException("The latest release is still a draft.");
        }

        String tag = release.get("tag_name").getAsString();
        String version = tag.replaceFirst("^[vV]", "");
        JsonArray assets = release.getAsJsonArray("assets");

        String jarName = null;
        String jarUrl = null;
        long size = 0;
        String installerName = null;
        String installerUrl = null;
        String checksumUrl = null;

        if (assets != null) {
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = asset.get("name").getAsString();
                String url = asset.get("browser_download_url").getAsString();
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.matches("^(checksums?|sha-?256sums?)(\\.txt|\\.sha256)?$")) {
                    checksumUrl = url;
                } else if (lower.endsWith(".jar")) {
                    // The runnable jar wins over any other jar in the release.
                    if (jarName == null || lower.contains("-all")) {
                        jarName = name;
                        jarUrl = url;
                        size = asset.has("size") ? asset.get("size").getAsLong() : 0;
                    }
                } else if (installerName == null && isInstallerForThisOs(lower)) {
                    installerName = name;
                    installerUrl = url;
                }
            }
        }

        if (jarUrl == null && installerUrl == null) {
            throw new IOException("Release " + tag + " has no launcher file to download.");
        }

        return new Release(tag, version, jarName, jarUrl, installerName, installerUrl, checksumUrl, size);
    }

    private static String rateLimitHint(HttpResponse<String> response) {
        return response.headers().firstValue("x-ratelimit-remaining")
                .map(remaining -> " (API requests left: " + remaining + ")")
                .orElse("");
    }

    private static boolean isInstallerForThisOs(String name) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return name.endsWith(".msi") || name.endsWith(".exe");
        }
        if (os.contains("mac")) {
            return name.endsWith(".dmg") || name.endsWith(".pkg");
        }
        return name.endsWith(".deb") || name.endsWith(".rpm") || name.endsWith(".appimage");
    }

    /** True when {@code candidate} is a higher version than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        int[] left = numbers(candidate);
        int[] right = numbers(current);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] numbers(String version) {
        String cleaned = version == null ? "" : version.replaceFirst("^[vV]", "");
        String[] parts = cleaned.split("[^0-9]+");
        int[] result = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            if (!part.isEmpty()) {
                result[count++] = Integer.parseInt(part);
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Installs {@code release}: opens its installer, or restarts from the downloaded jar. */
    public static void apply(Release release, Path updatesDir, Consumer<String> status) throws IOException {
        Files.createDirectories(updatesDir);

        if (isPackaged() && release.installerUrl() != null) {
            status.accept("Downloading MixClient " + release.version() + " installer…");
            Path installer = download(release.installerName(), release.installerUrl(),
                    release.checksumUrl(), updatesDir, status);
            status.accept("Opening the " + release.installerName() + " installer…");
            open(installer);
            return;
        }

        if (release.jarUrl() == null) {
            throw new IOException("Release " + release.tag() + " has no jar to restart from.");
        }

        status.accept("Downloading MixClient " + release.version() + "…");
        Path jar = download(release.jarName(), release.jarUrl(), release.checksumUrl(), updatesDir, status);
        status.accept("Restarting into MixClient " + release.version() + "…");
        relaunch(jar);
    }

    /** True when MixClient runs from an installer rather than a plain jar. */
    public static boolean isPackaged() {
        return System.getProperty("jpackage.app-path") != null;
    }

    private static Path download(String name, String url, String checksumUrl, Path dir,
                                 Consumer<String> status) throws IOException {
        Path target = dir.resolve(name);
        Path temp = dir.resolve(name + ".part");

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        MessageDigest digest = sha256();
        try (InputStream in = send(request);
             var out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                total += read;
                if (total % (4L * 1024 * 1024) < buffer.length) {
                    status.accept("Downloading MixClient… " + (total / (1024 * 1024)) + " MB");
                }
            }
        }

        String expected = checksumUrl == null ? null : checksums(checksumUrl).get(name);
        if (expected != null) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expected.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(temp);
                throw new IOException("Checksum mismatch for " + name
                        + " (expected " + expected + ", got " + actual + ")");
            }
            status.accept("Verified " + name);
        }

        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static InputStream send(HttpRequest request) throws IOException {
        try {
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Download failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private static Map<String, String> checksums(String url) throws IOException {
        Map<String, String> result = new HashMap<>();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try (InputStream in = send(request)) {
            for (String line : new String(in.readAllBytes()).split("\\R")) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2 && parts[0].matches("[0-9a-fA-F]{64}")) {
                    result.put(parts[1].trim().replaceFirst("^\\*", ""), parts[0].toLowerCase(Locale.ROOT));
                }
            }
        } catch (IllegalArgumentException ignored) {
            // A release without checksums still updates; it just isn't verified.
        }
        return result;
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    /** Starts the downloaded launcher jar and leaves this process to the caller. */
    public static void relaunch(Path jar) throws IOException {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        new ProcessBuilder(java.toString(), "-jar", jar.toString())
                .directory(jar.getParent().toFile())
                .inheritIO()
                .start();
    }

    private static void open(Path file) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(file.toFile());
                return;
            } catch (IOException ignored) {
                // Fall through to the platform openers.
            }
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String opener = os.contains("win") ? "cmd" : os.contains("mac") ? "open" : "xdg-open";
        if (os.contains("win")) {
            new ProcessBuilder(opener, "/c", "start", "", file.toString()).start();
        } else {
            new ProcessBuilder(opener, file.toString()).start();
        }
    }
}
