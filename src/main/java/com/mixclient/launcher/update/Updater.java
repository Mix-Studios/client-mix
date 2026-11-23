package com.mixclient.launcher.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.BuildInfo;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Keeps the launcher current from its own GitHub releases.
 *
 * <p>The feed is {@code /releases/latest/download/latest.json}, which GitHub only serves for a
 * stable release: the file names one version and, for every platform, one URL with its size and
 * SHA-256. The launcher reads that feed, ignores anything that is not a plain release newer than
 * {@link BuildInfo#VERSION}, downloads the file it names, refuses it unless the bytes hash to the
 * published SHA-256, and keeps the build it replaced so a version that fails to start can be
 * rolled back.</p>
 *
 * <p>The GitHub API is used only when the feed itself cannot be read; it is rate limited and
 * carries no hashes, so the checksums file is read in that case.</p>
 */
public final class Updater {

    public static final String REPOSITORY = "Mix-Studios/client-mix";

    private static final String LATEST_RELEASE =
            "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    /**
     * Published by the release workflow next to checksums.txt. Small, cache-free and not rate
     * limited, unlike the GitHub API, and it already carries the per-file SHA-256. The
     * {@code /releases/latest} path is GitHub's stable channel, so a test build never appears here.
     */
    private static final String LATEST_JSON = System.getProperty("mixclient.update.url",
            "https://github.com/" + REPOSITORY + "/releases/latest/download/latest.json");
    private static final String USER_AGENT = "MixClient-Launcher/" + BuildInfo.VERSION;

    /** Written by every launcher that reaches its window; the updater waits for it after a swap. */
    private static final String START_STAMP = "last-start.json";
    /** How long a freshly installed launcher gets to show its window before it is rolled back. */
    private static final long START_TIMEOUT_MS = 30_000;
    /**
     * How long the swap helper waits for the launcher it replaces to close its jar. Windows keeps
     * the jar of a running process locked, so the old launcher has to be gone before the swap.
     */
    private static final long PARENT_EXIT_TIMEOUT_MS = 30_000;
    /**
     * The helper mode:
     * {@code --apply-update <new jar> <installed jar> <pid of the launcher> <version>}.
     */
    public static final String HELPER_FLAG = "--apply-update";
    /** Puts the {@code .old} build back without downloading anything or opening a window. */
    public static final String ROLLBACK_FLAG = "--rollback";

    /** The release workflow's test rule, so the launcher and the site agree on what is stable. */
    private static final Pattern TEST_WORDS = Pattern.compile(
            "(^|[^a-z])(test|beta|alpha|preview|snapshot|nightly|canary|rc\\d*)([^a-z]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_SHAPED = Pattern.compile("^v?\\d+(\\.\\d+)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * What one release offers for this machine. The {@code *Sha256} values come from latest.json;
     * when they are null the download falls back to the release's checksums.txt.
     */
    public record Release(String tag, String version, String jarName, String jarUrl,
                          String installerName, String installerUrl, String checksumUrl,
                          long size, String jarSha256, String installerSha256) {
    }

    /** Thrown when the feed describes a test build; the stable channel never installs one. */
    public static final class TestBuildException extends IOException {
        TestBuildException(String message) {
            super(message);
        }
    }

    private Updater() {
    }

    /**
     * Asks for the newest stable release. Returns empty when GitHub's latest release is a test
     * build, so a pre-release can never replace a stable launcher.
     */
    public static Optional<Release> check() throws IOException, InterruptedException {
        try {
            return fromLatestJson();
        } catch (TestBuildException e) {
            System.err.println("[update] " + e.getMessage() + "; staying on "
                    + BuildInfo.VERSION);
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            System.err.println("[update] latest.json unavailable (" + e.getMessage()
                    + "); falling back to the GitHub API");
        }
        try {
            return fromApi();
        } catch (TestBuildException e) {
            // Nothing stable is newer: treat it as up to date rather than as a failed check.
            System.err.println("[update] " + e.getMessage() + "; staying on "
                    + BuildInfo.VERSION);
            return Optional.empty();
        }
    }

    /**
     * Reads the small {@code latest.json} the release publishes: version, a per-platform URL,
     * size and SHA-256. One request, no API quota, and the hash to verify is already known.
     */
    private static Optional<Release> fromLatestJson() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_JSON))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        // The published feed uses "version" for the tag plus "version_number"; older builds
        // wrote "version" already stripped and a separate "tag".
        String rawVersion = text(root, "version");
        String tag = text(root, "tag");
        String version = text(root, "version_number");
        if (version == null && rawVersion != null) {
            version = rawVersion.replaceFirst("^[vV]", "");
        }
        if (tag == null && rawVersion != null && rawVersion.matches("^[vV].*")) {
            tag = rawVersion;
        }
        if (version == null || version.isBlank()) {
            throw new IOException("latest.json has no version");
        }
        if (tag == null || tag.isBlank()) {
            tag = "v" + version;
        }
        if (isTestBuild(flagged(root), tag, text(root, "name"))) {
            throw new TestBuildException("latest.json describes test build " + tag);
        }

        List<JsonObject> files = candidates(root);
        JsonObject jar = bestJar(files);
        JsonObject installer = bestInstaller(files);
        if (installer == null && jar == null) {
            throw new IOException("latest.json has no download for " + platformKey());
        }

        String checksumUrl = text(root, "checksums_url");
        if (checksumUrl == null) {
            checksumUrl = text(root, "checksums");
        }
        return Optional.of(new Release(tag, version,
                fileName(jar), absolute(url(jar)),
                fileName(installer), absolute(url(installer)),
                absolute(checksumUrl), size(jar), sha256(jar), sha256(installer)));
    }

    /** True when the feed marks the release as pre-release. */
    private static boolean flagged(JsonObject root) {
        return root.has("prerelease") && !root.get("prerelease").isJsonNull()
                && root.get("prerelease").getAsBoolean();
    }

    /**
     * The same rule the release workflow applies: pre-release flag, a name or tag that says test,
     * or a tag that is not a plain version. This is what keeps {@code Test} tags off every
     * stable launcher even if one ends up as GitHub's latest release.
     */
    private static boolean isTestBuild(boolean flagged, String tag, String name) {
        if (flagged) {
            return true;
        }
        String plainTag = tag == null ? "" : tag.trim();
        String label = (plainTag + " " + (name == null ? "" : name)).trim();
        if (TEST_WORDS.matcher(label).find()) {
            return true;
        }
        return plainTag.isEmpty() || !VERSION_SHAPED.matcher(plainTag).matches();
    }

    /**
     * Every file entry in latest.json: the published {@code files} array, and the
     * {@code assets} list or map older feeds used.
     */
    private static List<JsonObject> candidates(JsonObject root) {
        List<JsonObject> result = new ArrayList<>();
        addEntries(root.get("files"), result);
        addEntries(root.get("assets"), result);
        return result;
    }

    private static void addEntries(JsonElement element, List<JsonObject> target) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    target.add(item.getAsJsonObject());
                }
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    target.add(entry.getValue().getAsJsonObject());
                }
            }
        }
    }

    /** The launcher jar: the runnable {@code -all} jar when the release has one. */
    private static JsonObject bestJar(List<JsonObject> files) {
        JsonObject best = null;
        boolean bestIsAll = false;
        for (JsonObject file : files) {
            String name = fileName(file);
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            boolean isAll = name.toLowerCase(Locale.ROOT).contains("-all");
            if (best == null || (isAll && !bestIsAll)) {
                best = file;
                bestIsAll = isAll;
            }
        }
        return best;
    }

    /** The best installer for this OS and architecture, preferring the native format. */
    private static JsonObject bestInstaller(List<JsonObject> files) {
        JsonObject best = null;
        int bestScore = 0;
        for (JsonObject file : files) {
            String name = fileName(file);
            if (name == null || name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (file.has("installer") && !file.get("installer").getAsBoolean()) {
                continue;
            }
            if (!isForThisMachine(file, name)) {
                continue;
            }
            int score = formatScore(file, name);
            if (score > bestScore) {
                best = file;
                bestScore = score;
            }
        }
        return best;
    }

    /** True when the entry's platform (and arch, when given) is this machine's. */
    private static boolean isForThisMachine(JsonObject file, String name) {
        String platform = text(file, "platform");
        if (platform != null) {
            String value = platform.toLowerCase(Locale.ROOT);
            String os = osKey();
            boolean matches = value.equals(os) || value.equals("any") || value.equals("universal")
                    || (os.equals("macos") && value.equals("mac"))
                    || (os.equals("windows") && (value.equals("win") || value.equals("win32")))
                    || (os.equals("linux") && value.equals("unix"));
            if (!matches) {
                return false;
            }
        } else if (!isInstallerForThisOs(name.toLowerCase(Locale.ROOT))) {
            return false;
        }

        String arch = text(file, "arch");
        if (arch == null || arch.equalsIgnoreCase("any") || arch.equalsIgnoreCase("universal")) {
            return true;
        }
        String value = arch.toLowerCase(Locale.ROOT);
        boolean entryIsArm = value.equals("arm64") || value.equals("aarch64") || value.equals("arm");
        return isArm() == entryIsArm;
    }

    /** Prefers the native format for this OS: dmg on macOS, msi on Windows, deb over rpm. */
    private static int formatScore(JsonObject file, String name) {
        String format = text(file, "format");
        String ext = format == null
                ? name.substring(name.lastIndexOf('.') + 1)
                : format;
        ext = ext.toLowerCase(Locale.ROOT).replace(".", "");
        String os = osKey();
        if (os.equals("macos")) {
            return switch (ext) {
                case "dmg" -> 3;
                case "pkg" -> 2;
                default -> 1;
            };
        }
        if (os.equals("windows")) {
            return switch (ext) {
                case "msi" -> 3;
                case "exe" -> 2;
                default -> 1;
            };
        }
        boolean rpm = isRpmDistro();
        return switch (ext) {
            case "deb" -> rpm ? 1 : 3;
            case "rpm" -> rpm ? 3 : 1;
            case "appimage" -> 2;
            default -> 1;
        };
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static String url(JsonObject asset) {
        return asset != null && asset.has("url") ? asset.get("url").getAsString() : null;
    }

    private static String fileName(JsonObject asset) {
        if (asset == null) {
            return null;
        }
        if (asset.has("file")) {
            return asset.get("file").getAsString();
        }
        if (asset.has("name")) {
            return asset.get("name").getAsString();
        }
        String url = url(asset);
        if (url == null) {
            return null;
        }
        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(slash + 1);
    }

    private static long size(JsonObject asset) {
        return asset != null && asset.has("size") ? asset.get("size").getAsLong() : 0;
    }

    private static String sha256(JsonObject asset) {
        if (asset == null || !asset.has("sha256") || asset.get("sha256").isJsonNull()) {
            return null;
        }
        String value = asset.get("sha256").getAsString().trim().toLowerCase(Locale.ROOT);
        return SHA256.matcher(value).matches() ? value : null;
    }

    /** Resolves a path in latest.json against the file's own URL, so relative links work. */
    private static String absolute(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return URI.create(LATEST_JSON).resolve(url.trim()).toString();
    }

    /** The key latest.json uses for this machine, e.g. {@code macos-arm64}. */
    private static String platformKey() {
        boolean arm = isArm();
        String os = osKey();
        return switch (os) {
            case "windows" -> arm ? "windows-arm64" : "windows-x64";
            case "macos" -> arm ? "macos-arm64" : "macos-x64";
            default -> arm ? "linux-arm64" : (isRpmDistro() ? "linux-x86_64" : "linux-amd64");
        };
    }

    private static String osKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    }

    private static boolean isArm() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    /** True on Fedora/RHEL/openSUSE, where the RPM is the right Linux download. */
    private static boolean isRpmDistro() {
        try {
            String release = Files.readString(Path.of("/etc/os-release"), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            return release.contains("id_like=\"rhel") || release.contains("id_like=fedora")
                    || release.contains("id=fedora") || release.contains("id=rhel")
                    || release.contains("suse");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Asks the GitHub API for the newest release, skipping anything that is a test build. */
    private static Optional<Release> fromApi() throws IOException, InterruptedException {
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
        String name = release.has("name") && !release.get("name").isJsonNull()
                ? release.get("name").getAsString() : tag;
        if (isTestBuild(flagged(release), tag, name)) {
            throw new TestBuildException("the latest release is test build " + tag);
        }

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
                String assetName = asset.get("name").getAsString();
                String url = asset.get("browser_download_url").getAsString();
                String lower = assetName.toLowerCase(Locale.ROOT);

                if (lower.matches("^(checksums?|sha-?256sums?)(\\.txt|\\.sha256)?$")) {
                    checksumUrl = url;
                } else if (lower.endsWith(".jar")) {
                    // The runnable jar wins over any other jar in the release.
                    if (jarName == null || lower.contains("-all")) {
                        jarName = assetName;
                        jarUrl = url;
                        size = asset.has("size") ? asset.get("size").getAsLong() : 0;
                    }
                } else if (installerName == null && isInstallerForThisOs(lower)) {
                    installerName = assetName;
                    installerUrl = url;
                }
            }
        }

        if (jarUrl == null && installerUrl == null) {
            throw new IOException("Release " + tag + " has no launcher file to download.");
        }

        return Optional.of(new Release(tag, version, jarName, jarUrl, installerName, installerUrl,
                checksumUrl, size, null, null));
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
        if (left.length == 0) {
            return false;
        }
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

    /**
     * Installs {@code release}: opens its installer, or downloads the jar and hands it to the swap
     * helper. Nothing is installed before its SHA-256 matched. Either way the update is no longer
     * this process's job once this returns, so the caller closes its window.
     */
    public static void apply(Release release, Path updatesDir, Consumer<String> status)
            throws IOException {
        Files.createDirectories(updatesDir);
        pruneHelperCopies(updatesDir);

        if (isPackaged() && release.installerUrl() != null) {
            status.accept("Downloading MixClient " + release.version() + " installer…");
            Path installer = download(release.installerName(), release.installerUrl(),
                    release.installerSha256(), release.checksumUrl(), updatesDir, status);
            status.accept("Opening the " + release.installerName() + " installer…");
            open(installer);
            return;
        }

        if (release.jarUrl() == null) {
            throw new IOException("Release " + release.tag() + " has no jar to restart from.");
        }

        status.accept("Downloading MixClient " + release.version() + "…");
        Path jar = download(release.jarName(), release.jarUrl(),
                release.jarSha256(), release.checksumUrl(), updatesDir, status);

        Path target = BuildInfo.jarPath();
        if (target == null) {
            // An IDE or build-directory run has no installed jar to replace.
            status.accept("Running from a build directory; starting the downloaded launcher.");
            relaunch(jar);
            return;
        }

        status.accept("Restarting into MixClient " + release.version() + "…");
        startSwapHelper(jar, target, release.version(), updatesDir);
    }

    /** True when there is another build beside the running one to go back to. */
    public static boolean canRollBack() {
        Path backup = backupOfRunning();
        return backup != null && Files.isRegularFile(backup);
    }

    /** The version of the build {@link #rollback} would install, or null when it has none. */
    public static String previousVersion() {
        Path backup = backupOfRunning();
        return backup == null ? null : versionOfJar(backup);
    }

    /**
     * Puts the previous build back and starts it, through the same helper an update uses, so it
     * works on Windows too. The build being replaced stays beside it as {@code .old}, which is what
     * makes the action reversible: rolling back again returns to where you started.
     */
    public static void rollback(Path updatesDir, Consumer<String> status) throws IOException {
        Path target = BuildInfo.jarPath();
        if (target == null) {
            throw new IOException("MixClient isn't running from an installed jar, so there is "
                    + "nothing to go back to.");
        }
        Path backup = backupOfRunning();
        if (backup == null || !Files.isRegularFile(backup)) {
            throw new IOException("There is no previous build beside " + target.getFileName()
                    + " yet; one appears after the first update.");
        }
        Files.createDirectories(updatesDir);
        pruneHelperCopies(updatesDir);

        String version = versionOfJar(backup);
        status.accept("Going back to MixClient "
                + (version == null ? "the previous build" : version) + "…");
        startSwapHelper(backup, target, version == null ? BuildInfo.VERSION : version, updatesDir);
    }

    /** The {@code .old} jar next to the running one, or null when there is no running jar. */
    private static Path backupOfRunning() {
        Path running = BuildInfo.jarPath();
        return running == null ? null : running.resolveSibling(running.getFileName() + ".old");
    }

    /** The {@code Implementation-Version} a jar carries, or null when it has none. */
    private static String versionOfJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() == null) {
                return null;
            }
            String version = file.getManifest().getMainAttributes()
                    .getValue("Implementation-Version");
            return version == null || version.isBlank() ? null : version.trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Starts the verified download in helper mode and leaves the swap to it.
     *
     * <p>The helper is a <em>copy of this build</em>, not the download, for two reasons: it is a
     * different file from the installed jar, so it can already be running while this process exits
     * and Windows lets go of the jar it was started from, and it is known to run, so a release that
     * cannot start still gets rolled back instead of leaving the player with nothing.</p>
     */
    private static void startSwapHelper(Path downloaded, Path target, String version,
                                        Path updatesDir) throws IOException {
        Path running = BuildInfo.jarPath();
        if (running == null) {
            throw new IOException("no running jar to start the update helper from");
        }
        Path helper = updatesDir.resolve("swap-helper-" + ProcessHandle.current().pid() + ".jar");
        Files.copy(running, helper, StandardCopyOption.REPLACE_EXISTING);
        new ProcessBuilder(javaCommand(), "-jar", helper.toString(),
                HELPER_FLAG, downloaded.toString(), target.toString(),
                Long.toString(ProcessHandle.current().pid()), version)
                .directory(updatesDir.toFile())
                .inheritIO()
                .start();
    }

    /**
     * The update helper, running from a scratch copy of the installed build rather than from the
     * installed jar itself.
     *
     * <p>It waits for the launcher that started it to close the installed jar, copies the verified
     * download over that path, keeps the old one as {@code .old}, starts the installed copy and
     * watches it the way {@link #apply} used to. This is what makes an update survive on Windows,
     * where a running jar cannot be moved or overwritten; the installed file is only touched once
     * no process has it open, and the new build is only opened after the swap succeeded.</p>
     *
     * <p>Arguments: {@code --apply-update <new jar> <target jar> <pid of the launcher> <version>}.</p>
     */
    public static void runSwapHelper(String[] args) {
        Path downloaded;
        Path target;
        long parentPid;
        String version;
        try {
            downloaded = Path.of(args[1]);
            target = Path.of(args[2]);
            parentPid = Long.parseLong(args[3]);
            version = args.length > 4 ? args[4] : BuildInfo.VERSION;
        } catch (RuntimeException e) {
            System.err.println("[update] helper started with bad arguments; giving up");
            return;
        }

        Consumer<String> status = message -> System.err.println("[update] " + message);
        awaitExit(parentPid);

        try {
            Path backup = replaceWithRetry(downloaded, target, status);
            if (!awaitStart(relaunch(target), target, backup, version, status)) {
                // The launcher that started this helper is gone, so whatever is installed now has
                // to be opened here or the player is left with no window at all.
                relaunchQuietly(target, status);
            }
        } catch (IOException e) {
            // A read-only install (or a jar still locked after the retries) must not leave the
            // player with nothing: start what is still installed, which is this build.
            status.accept("couldn't install " + version + " (" + e.getMessage()
                    + "); starting the installed copy");
            relaunchQuietly(target, status);
        }
        System.exit(0);
    }

    /** Waits for {@code pid} to close, then a moment longer for it to release its jar. */
    private static void awaitExit(long pid) {
        long deadline = System.currentTimeMillis() + PARENT_EXIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                sleep(300);
                return;
            }
            sleep(100);
        }
        System.err.println("[update] the launcher is still open after "
                + (PARENT_EXIT_TIMEOUT_MS / 1000) + "s; trying the swap anyway");
    }

    /**
     * Replaces the installed jar, retrying briefly: Windows can keep the handle for a moment after
     * the process that owned it has gone. A failed attempt leaves the installed file untouched (or
     * restored), so trying again is safe.
     */
    private static Path replaceWithRetry(Path downloaded, Path target, Consumer<String> status)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                return replaceVerified(downloaded, target, status);
            } catch (IOException e) {
                last = e;
                status.accept("the installed jar is still busy (" + e.getMessage()
                        + "); retrying");
                sleep(300L * attempt);
            }
        }
        throw last != null ? last : new IOException("the launcher could not be replaced");
    }

    private static void relaunchQuietly(Path jar, Consumer<String> status) {
        try {
            relaunch(jar);
        } catch (IOException e) {
            status.accept("couldn't start " + jar.getFileName() + ": " + e.getMessage());
        }
    }

    /** Removes helper copies left by earlier updates; windows hands them back as they close. */
    private static void pruneHelperCopies(Path updatesDir) {
        try (var files = Files.list(updatesDir)) {
            files.filter(path -> path.getFileName().toString().startsWith("swap-helper-"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException stillRunning) {
                            // A helper from a previous update is still up; it goes next time.
                        }
                    });
        } catch (IOException e) {
            System.err.println("[update] could not tidy " + updatesDir + ": " + e.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Watches the new launcher until it reports its window, exits, or {@link #START_TIMEOUT_MS}
     * passes.
     *
     * <p>The previous build is only put back when the new one <em>exited with an error</em>: a
     * launcher that is merely slow is left running untouched, because replacing it mid-start would
     * end with two windows. A clean exit is not treated as a failure either.</p>
     *
     * @return true while a launcher is running or on its way; false when nothing is on screen and
     *         the caller has to open what is installed
     */
    private static boolean awaitStart(Process child, Path target, Path backup, String version,
                                      Consumer<String> status) {
        long launchedAt = System.currentTimeMillis();
        while (child.isAlive() && System.currentTimeMillis() - launchedAt < START_TIMEOUT_MS) {
            if (startedSince(launchedAt, version)) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        if (startedSince(launchedAt, version)) {
            return true;
        }

        if (child.isAlive()) {
            status.accept("MixClient " + version + " is still starting; leaving it running"
                    + " (previous build kept as " + backup.getFileName() + ")");
            System.err.println("[update] MixClient " + version
                    + " has not reported in after " + (START_TIMEOUT_MS / 1000)
                    + "s but is still running; not rolling back");
            return true;
        }

        int code = exitCode(child);
        if (code == 0) {
            status.accept("MixClient " + version + " closed before it reported in; opening it again");
            System.err.println("[update] MixClient " + version
                    + " exited with 0 before reporting in; nothing was restored");
            return false;
        }

        try {
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            status.accept("MixClient " + version + " failed to start (exit " + code
                    + "); restored the previous build");
            System.err.println("[update] MixClient " + version + " exited with " + code
                    + "; restored " + backup);
        } catch (IOException e) {
            status.accept("MixClient " + version + " failed to start (exit " + code
                    + ") and the rollback failed: " + e.getMessage());
            System.err.println("[update] rollback failed: " + e);
        }
        return false;
    }

    /** The child's exit status; -1 if it is somehow still running. */
    private static int exitCode(Process child) {
        try {
            return child.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** True when a launcher wrote its start stamp after {@code since} for {@code version}. */
    private static boolean startedSince(long since, String version) {
        try {
            Path stamp = AppPaths.appDir().resolve(START_STAMP);
            if (!Files.exists(stamp)) {
                return false;
            }
            JsonObject root = JsonParser.parseString(
                    Files.readString(stamp, StandardCharsets.UTF_8)).getAsJsonObject();
            String started = text(root, "version");
            long at = root.has("at") ? root.get("at").getAsLong() : Files.getLastModifiedTime(stamp).toMillis();
            return at >= since && started != null && started.replaceFirst("^[vV]", "")
                    .equals(version.replaceFirst("^[vV]", ""));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Records that this build reached its window. The launcher that installed it waits for this
     * before it hands over; a build that exits with an error first is rolled back.
     */
    public static void noteStarted() {
        try {
            Path file = AppPaths.appDir().resolve(START_STAMP);
            JsonObject stamp = new JsonObject();
            stamp.addProperty("version", BuildInfo.VERSION);
            stamp.addProperty("at", System.currentTimeMillis());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, stamp.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("[update] Could not record the start: " + e);
        }
    }

    /**
     * Copies {@code downloaded} over {@code target}, checking the SHA-256 of the copy before it is
     * moved in and again after, and restoring the previous file if either check fails. Replacing
     * (not overwriting) the running jar works on macOS and Linux because the JVM keeps its own
     * handle on the old file.
     *
     * @return the previous build, kept as {@code .old} for a rollback
     */
    static Path replaceVerified(Path downloaded, Path target, Consumer<String> status)
            throws IOException {
        String expected = sha256Of(downloaded);
        Path staging = target.resolveSibling(target.getFileName() + ".new");
        Path backup = target.resolveSibling(target.getFileName() + ".old");

        Files.copy(downloaded, staging, StandardCopyOption.REPLACE_EXISTING);
        if (!expected.equals(sha256Of(staging))) {
            Files.deleteIfExists(staging);
            throw new IOException("the copy didn't match its SHA-256");
        }

        Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.copy(staging, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(staging);
            }
        } catch (IOException e) {
            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("couldn't put it in place: " + e.getMessage(), e);
        }

        if (!expected.equals(sha256Of(target))) {
            Files.deleteIfExists(target);
            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("the installed file didn't match its SHA-256");
        }

        status.accept("Replaced " + target.getFileName() + " (previous kept as "
                + backup.getFileName() + " for rollback)");
        return backup;
    }

    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** True when MixClient runs from an installer rather than a plain jar. */
    public static boolean isPackaged() {
        return System.getProperty("jpackage.app-path") != null;
    }

    /**
     * Downloads {@code url} to {@code dir/{name}} and refuses it unless it hashes to the SHA-256
     * the feed published (or the release's checksums.txt when the feed had none). The finished
     * file only appears under its real name once the hash matched.
     */
    private static Path download(String name, String url, String expectedSha, String checksumUrl,
                                 Path dir, Consumer<String> status) throws IOException {
        String fileName = safeFileName(name, url);
        Path target = dir.resolve(fileName);
        Path temp = dir.resolve(fileName + ".part");

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        MessageDigest digest = sha256();
        long total = 0;
        try (InputStream in = send(request);
             var out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
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

        // latest.json hands us the hash; otherwise read it from the release's checksums.txt.
        String expected = expectedSha;
        if (expected == null && checksumUrl != null) {
            expected = checksums(checksumUrl).get(fileName);
        }
        if (expected == null) {
            Files.deleteIfExists(temp);
            throw new IOException("No SHA-256 is published for " + fileName
                    + ", so the update wasn't installed.");
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(temp);
            throw new IOException("Checksum mismatch for " + fileName
                    + " (expected " + expected + ", got " + actual + ")");
        }
        status.accept("Verified " + fileName + " (" + actual.substring(0, 12) + "…)");

        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** The feed's file name, reduced to a plain name so a release can't write outside {@code dir}. */
    private static String safeFileName(String name, String url) throws IOException {
        String candidate = name;
        if (candidate == null || candidate.isBlank()) {
            candidate = url == null ? "" : url.substring(url.lastIndexOf('/') + 1);
        }
        candidate = candidate.replace('\\', '/');
        candidate = candidate.substring(candidate.lastIndexOf('/') + 1).trim();
        if (candidate.isEmpty() || candidate.equals(".") || candidate.equals("..")) {
            throw new IOException("The release names no downloadable file.");
        }
        return candidate;
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
        } catch (IOException e) {
            // No checksums leaves nothing to verify against, so download() refuses the file.
            System.err.println("[update] Could not read " + url + ": " + e.getMessage());
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
    public static Process relaunch(Path jar) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", javaCommand());
        return new ProcessBuilder(java.toString(), "-jar", jar.toString())
                .directory(jar.getParent().toFile())
                .inheritIO()
                .start();
    }

    private static String javaCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
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
