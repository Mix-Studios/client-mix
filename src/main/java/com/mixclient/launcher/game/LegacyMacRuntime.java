package com.mixclient.launcher.game;

import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The native Apple-silicon way to start 1.8.9.
 *
 * <p>Mojang's 1.8.9 natives are Intel-only, so the launcher used to run the game on an Intel Java 8
 * through Rosetta. Apple's OpenGL driver occasionally aborts that translated process while the game
 * window is being placed (SIGABRT, exit code 134). This class installs the two pieces that remove
 * Rosetta from the equation: Azul's arm64 Java 8 and the MinecraftMachina build of LWJGL 2, whose
 * macOS patches keep the OpenGL work on the main thread. The returned natives directory is put in
 * front of the version's Intel natives on {@code java.library.path}.</p>
 *
 * <p>Everything is downloaded once, checked against a pinned SHA-256, and kept under the launcher's
 * data folder; a download failure leaves the Intel Java 8 path as the fallback.</p>
 */
public final class LegacyMacRuntime {

    /** The runtime and the arm64 natives that must be searched before the version's natives. */
    public record Runtime(Path java, Path nativesDir) {
    }

    private static final String JDK_URL =
            "https://cdn.azul.com/zulu/bin/zulu8.98.0.201-ca-jdk8.0.514-macosx_aarch64.tar.gz";
    private static final String JDK_SHA256 =
            "792935f732653f5c4fd4cdc68e3ade65cb1fa1fda6b6769fc5dd8098b330b298";
    private static final String NATIVES_URL = "https://github.com/MinecraftMachina/lwjgl/releases/"
            + "download/2.9.4-20150209-mmachina.2/"
            + "lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar";
    private static final String NATIVES_SHA256 =
            "a14379458c749872a1e183e88edceaab87c762328fb031481d98d0aaed5e6311";
    private static final String NATIVES_JAR = "lwjgl2-macos-arm64-natives.jar";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile Boolean appleSilicon;

    private LegacyMacRuntime() {
    }

    /** True on an Apple-silicon Mac, including when the launcher itself runs translated by Rosetta. */
    public static boolean isAppleSilicon() {
        Boolean known = appleSilicon;
        if (known != null) {
            return known;
        }
        boolean result = false;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                result = true;
            } else {
                try {
                    Process process = new ProcessBuilder("sysctl", "-n", "hw.optional.arm64")
                            .redirectErrorStream(true)
                            .start();
                    String output = new String(process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8).trim();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                    result = output.equals("1");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException | RuntimeException e) {
                    // An Intel Mac without the sysctl answers nothing, which means "not arm64".
                }
            }
        }
        appleSilicon = result;
        return result;
    }

    /** True when this version should use the native path instead of an Intel Java through Rosetta. */
    public static boolean isNeeded(int requiredMajor) {
        return requiredMajor <= 8 && isAppleSilicon();
    }

    /** The installed runtime, or null before the first 1.8.9 launch on this machine. */
    public static Runtime installed() {
        Path java = runtimeHome().resolve("bin").resolve("java");
        Path natives = nativesDir();
        if (!Files.isExecutable(java) || !Files.exists(natives.resolve("liblwjgl.dylib"))
                || !Files.exists(natives.resolve("openal.dylib"))) {
            return null;
        }
        return new Runtime(java, natives);
    }

    /** Installs the runtime and natives once, then returns them. */
    public static Runtime ensure(Progress progress) throws IOException {
        Runtime runtime = installed();
        if (runtime != null) {
            return runtime;
        }

        Path archive = AppPaths.cacheDir().resolve("runtime").resolve("zulu8-macos-aarch64.tar.gz");
        if (!Files.exists(archive) || !JDK_SHA256.equalsIgnoreCase(sha256Of(archive))) {
            progress.update("Downloading Java 8 for Apple silicon (99 MB)…", -1);
            download(JDK_URL, archive, JDK_SHA256, "Java 8", progress);
        }

        progress.update("Installing Java 8 for Apple silicon…", 0.04);
        extractJdk(archive);

        progress.update("Installing patched LWJGL 2 for Apple silicon…", 0.06);
        installNatives(progress);

        runtime = installed();
        if (runtime == null) {
            throw new IOException("The Apple-silicon Java 8 runtime did not install correctly.");
        }
        return runtime;
    }

    private static Path runtimeHome() {
        return AppPaths.appDir().resolve("runtimes").resolve("java8-macos-aarch64");
    }

    private static Path nativesDir() {
        return AppPaths.appDir().resolve("natives").resolve("lwjgl2-macos-arm64");
    }

    private static void installNatives(Progress progress) throws IOException {
        Path jar = AppPaths.cacheDir().resolve("loaders").resolve(NATIVES_JAR);
        if (!Files.exists(jar) || !NATIVES_SHA256.equalsIgnoreCase(sha256Of(jar))) {
            download(NATIVES_URL, jar, NATIVES_SHA256, "patched LWJGL 2", progress);
        }

        Path target = nativesDir();
        Files.createDirectories(target);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : new String[]{"liblwjgl.dylib", "openal.dylib"}) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    throw new IOException(NATIVES_JAR + " is missing " + name);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void extractJdk(Path archive) throws IOException {
        Path runtimes = AppPaths.appDir().resolve("runtimes");
        Files.createDirectories(runtimes);
        Path temp = runtimes.resolve("zulu8.tmp");
        deleteTree(temp);
        Files.createDirectories(temp);

        try {
            // The Azul archive wraps the JDK in a versioned folder, so drop that level.
            Process process = new ProcessBuilder("/usr/bin/tar", "-xzf", archive.toString(),
                    "--strip-components=1", "-C", temp.toString())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IOException("Couldn't unpack the Java 8 download"
                        + (output.isEmpty() ? "." : ": " + output));
            }

            Path home = temp.resolve("Contents").resolve("Home");
            if (!Files.isExecutable(home.resolve("bin").resolve("java"))) {
                throw new IOException("The Java 8 download did not contain a runtime.");
            }

            Path target = runtimeHome();
            deleteTree(target);
            Files.move(home, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Installing Java 8 was interrupted.", e);
        } finally {
            deleteTree(temp);
        }
    }

    private static void download(String url, Path target, String expectedSha, String label,
                                 Progress progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "MixClient/" + com.mixclient.launcher.core.BuildInfo.VERSION)
                .GET()
                .build();

        MessageDigest digest = sha256();
        long total = 0;
        long reportedMb = -1;
        try {
            HttpResponse<InputStream> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode() + " for " + label);
            }
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    total += read;
                    long megabytes = total / (1024 * 1024);
                    if (megabytes >= reportedMb + 8) {
                        reportedMb = megabytes;
                        progress.update("Downloading " + label + "… " + megabytes + " MB", -1);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Downloading " + label + " was interrupted.", e);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw new IOException("Couldn't download " + label + ": " + e.getMessage(), e);
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (!expectedSha.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(temp);
            throw new IOException("The " + label + " download did not match its SHA-256.");
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256Of(Path file) {
        try {
            MessageDigest digest = sha256();
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover file only makes the next attempt re-extract.
                }
            });
        } catch (IOException ignored) {
            // Same here.
        }
    }
}
