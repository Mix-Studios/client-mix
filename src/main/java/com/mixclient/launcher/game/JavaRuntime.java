package com.mixclient.launcher.game;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the Java runtime a Minecraft version needs.
 *
 * <p>Older releases pin their own Java: 1.8.9 was built for Java 8, and its stock LWJGL 2 natives
 * are Intel-only. On an Apple-silicon Mac that case is handled by
 * {@link LegacyMacRuntime}, which installs an arm64 Java 8 and patched arm64 natives; the Intel
 * Java 8 chosen here is the fallback when that runtime can't be installed. Newer releases need
 * Java 25 or newer. The launcher therefore matches the version's
 * {@code javaVersion.majorVersion} against the runtimes installed on this machine instead of
 * always reusing its own JVM.</p>
 */
public final class JavaRuntime {

    /** A usable JVM: where it is, which Java it is, and the architecture it runs as. */
    public record Match(Path java, int major, String arch) {

        /** True for 64-bit Intel builds, which are the only ones 1.8.9's natives load on. */
        public boolean isIntel() {
            String value = arch.toLowerCase(Locale.ROOT);
            return value.contains("x86_64") || value.contains("amd64")
                    || value.contains("i386") || value.equals("x86");
        }
    }

    /** No installed runtime can start this version; the message is written for the player. */
    public static final class MissingJavaException extends IOException {
        public MissingJavaException(String message) {
            super(message);
        }
    }

    private static final Pattern SPEC_VERSION =
            Pattern.compile("java\\.specification\\.version\\s*=\\s*(\\S+)");
    private static final Pattern OS_ARCH = Pattern.compile("os\\.arch\\s*=\\s*(\\S+)");

    private static final Map<Path, Match> PROBES = new ConcurrentHashMap<>();
    private static final Map<Integer, Match> CHOSEN = new ConcurrentHashMap<>();
    private static volatile List<Match> installed;

    private JavaRuntime() {
    }

    /**
     * The JVM to start {@code versionId} with.
     *
     * @param versionId      for the error message, e.g. {@code 1.8.9}
     * @param requiredMajor  the version's {@code javaVersion.majorVersion}
     * @param javaOverride   the player's Java for this version, or empty/blank to choose one
     */
    public static Match resolve(String versionId, int requiredMajor, String javaOverride)
            throws MissingJavaException {
        String explicit = javaOverride == null ? null : javaOverride.trim();
        if (explicit != null && !explicit.isEmpty()) {
            Match match = probe(Path.of(explicit.trim()));
            if (match == null) {
                throw new MissingJavaException("The Java set for " + versionId + " (" + explicit
                        + ") can't be started. Point it at a bin/java binary, or choose automatic.");
            }
            return match;
        }
        return choose(versionId, requiredMajor);
    }

    /** A short "Java 8 (x86_64), automatic" line for the launcher to show before Play. */
    public static String describe(Match match, String javaOverride) {
        boolean fixed = javaOverride != null && !javaOverride.isBlank();
        return "Java " + match.major() + " (" + match.arch() + ")"
                + (fixed ? ", set for this version" : ", automatic");
    }

    private static Match choose(String versionId, int requiredMajor) throws MissingJavaException {
        // 1.8.x's stock natives are Intel-only; Apple silicon normally uses the managed arm64
        // runtime in LegacyMacRuntime, so this is the Intel fallback for that version.
        boolean intelOnly = requiredMajor <= 8 && isAppleSilicon();

        Match cached = CHOSEN.get(requiredMajor);
        if (cached != null && Files.isExecutable(cached.java())) {
            return cached;
        }

        List<Match> all = installed();
        List<Match> usable = new ArrayList<>();
        for (Match match : all) {
            if (match.major() < requiredMajor) {
                continue;
            }
            if (intelOnly && !match.isIntel()) {
                continue;
            }
            usable.add(match);
        }
        if (usable.isEmpty()) {
            throw new MissingJavaException(missingMessage(versionId, requiredMajor, intelOnly, all));
        }

        // The lowest version that is new enough: 1.8.9 really wants a real Java 8.
        Match best = usable.get(0);
        for (Match match : usable) {
            if (match.major() < best.major()) {
                best = match;
            }
        }
        CHOSEN.put(requiredMajor, best);
        return best;
    }

    private static String missingMessage(String versionId, int requiredMajor, boolean intelOnly,
                                         List<Match> found) {
        String needed = requiredMajor <= 8
                ? "Java 8" + (intelOnly ? " (a 64-bit Intel build)" : "")
                : "Java " + requiredMajor + " or newer";

        StringBuilder text = new StringBuilder("Minecraft ").append(versionId)
                .append(" needs ").append(needed);
        if (intelOnly) {
            text.append(", because its LWJGL 2 natives have no Apple-silicon build");
        }
        text.append(". ");

        if (found.isEmpty()) {
            text.append("No Java runtime was found on this machine. ");
        } else {
            text.append("Found: ");
            for (int i = 0; i < found.size(); i++) {
                Match match = found.get(i);
                if (i > 0) {
                    text.append(", ");
                }
                text.append("Java ").append(match.major()).append(" (").append(match.arch()).append(")");
            }
            text.append(". ");
        }

        text.append("Install Temurin ").append(Math.max(requiredMajor, 8))
                .append(intelOnly ? " (x64, the Intel download)" : "")
                .append(" and point Settings → Java executable at its bin/java.");
        return text.toString();
    }

    /** Every JVM that answers {@code -version} on this machine, probed once. */
    private static List<Match> installed() {
        List<Match> current = installed;
        if (current != null) {
            return current;
        }
        List<Match> matches = new ArrayList<>();
        for (Path java : candidates()) {
            Match match = probe(java);
            if (match != null && match.major() > 0) {
                matches.add(match);
            }
        }
        installed = matches;
        return matches;
    }

    private static Set<Path> candidates() {
        Set<Path> homes = new LinkedHashSet<>();
        String executable = isWindows() ? "java.exe" : "java";

        // The launcher's own runtime first, then every JDK the OS knows about.
        addHome(homes, System.getProperty("java.home"), executable);
        addHome(homes, System.getenv("JAVA_HOME"), executable);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            addHomes(homes, Path.of("/Library/Java/JavaVirtualMachines"), "Contents/Home", executable);
            addHomes(homes, Path.of(System.getProperty("user.home"), "Library", "Java",
                    "JavaVirtualMachines"), "Contents/Home", executable);
            // The Oracle browser plug-in ships a plain Intel Java 8 that still runs 1.8.9.
            addHome(homes, "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home", executable);
        } else if (os.contains("win")) {
            for (String name : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String root = System.getenv(name);
                if (root == null || root.isBlank()) {
                    continue;
                }
                for (String vendor : List.of("Java", "Eclipse Adoptium", "Eclipse Foundation",
                        "Microsoft", "Zulu", "BellSoft")) {
                    addHomes(homes, Path.of(root, vendor), "", executable);
                    addHomes(homes, Path.of(root, "Programs", vendor), "", executable);
                }
            }
        } else {
            addHomes(homes, Path.of("/usr/lib/jvm"), "", executable);
            addHomes(homes, Path.of(System.getProperty("user.home"), ".jdks"), "", executable);
        }

        Set<Path> result = new LinkedHashSet<>();
        for (Path home : homes) {
            Path java = home.resolve("bin").resolve(executable);
            if (Files.isExecutable(java)) {
                result.add(java);
            }
        }
        return result;
    }

    private static void addHome(Set<Path> homes, String home, String executable) {
        if (home == null || home.isBlank()) {
            return;
        }
        Path path = Path.of(home);
        if (Files.isDirectory(path)) {
            homes.add(path);
        }
    }

    /** Adds {@code root/*} (optionally {@code root/ * /Contents/Home}) as JVM homes. */
    private static void addHomes(Set<Path> homes, Path root, String suffix, String executable) {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    entries.add(suffix.isEmpty() ? entry : entry.resolve(suffix));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return;
        }
        for (Path home : entries) {
            if (Files.isDirectory(home)) {
                homes.add(home);
            }
        }
    }

    /** Asks a JVM what it is: {@code -XshowSettings:properties -version} reports both facts. */
    private static Match probe(Path java) {
        if (java == null || !Files.isExecutable(java)) {
            return null;
        }
        return PROBES.computeIfAbsent(java, path -> {
            Process process = null;
            try {
                process = new ProcessBuilder(path.toString(), "-XshowSettings:properties", "-version")
                        .redirectErrorStream(true)
                        .start();
                String output;
                try (InputStream in = process.getInputStream()) {
                    output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }

                Matcher version = SPEC_VERSION.matcher(output);
                if (!version.find()) {
                    return null;
                }
                String arch = "unknown";
                Matcher archMatcher = OS_ARCH.matcher(output);
                if (archMatcher.find()) {
                    arch = archMatcher.group(1).toLowerCase(Locale.ROOT);
                }
                return new Match(path, majorOf(version.group(1)), arch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException | RuntimeException e) {
                return null;
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        });
    }

    /** {@code 1.8}, {@code 1.8.0_511} and {@code 25} all collapse to their feature version. */
    static int majorOf(String specification) {
        String value = specification == null ? "" : specification.trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int dot = value.indexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isAppleSilicon() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
