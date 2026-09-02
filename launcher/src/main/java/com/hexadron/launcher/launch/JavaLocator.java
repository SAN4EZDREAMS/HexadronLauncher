/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.launch;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a Java runtime capable of running a given Minecraft version.
 *
 * <p>This matters more than it used to: Minecraft 26.2 requires Java 25, 1.20.5+
 * required 21, 1.17-1.20.4 required 17 and anything older wants 8. A launcher
 * that claims to support all versions must be able to point each profile at a
 * different runtime, so the Java in use is resolved per launch, not per install.
 *
 * <p>Detection is deliberately broad. The launcher is handed to people who never
 * installed a JDK on purpose, and the ways a Java ends up on a Windows machine
 * are many: an installer that wrote to {@code Program Files}, a package manager
 * that put it under {@code %LOCALAPPDATA%}, an IDE that keeps its own under
 * {@code ~/.jdks}, or the official Minecraft launcher, which downloads runtimes
 * into {@code .minecraft/runtime} and is very often already present. Every one
 * of those is a runtime the user already has, and using it beats asking them to
 * download another.
 *
 * <p>What is <em>not</em> here is a search of the whole disk. It is slow, it
 * reads folders the launcher has no business reading, and it finds runtimes
 * bundled inside unrelated applications, which are not ours to borrow.
 */
public final class JavaLocator {

    /** A Java installation and the major version it reports. */
    public record JavaRuntime(Path executable, int majorVersion, String source) {

        public boolean satisfies(int requiredMajor) {
            return majorVersion >= requiredMajor;
        }

        /** The runtime's home directory, i.e. the parent of {@code bin}. */
        public Path home() {
            Path bin = executable.getParent();
            return bin == null ? executable : bin.getParent();
        }

        @Override
        public String toString() {
            return "Java " + majorVersion + " at " + executable + " (" + source + ")";
        }
    }

    private static final Pattern VERSION_LINE =
            Pattern.compile("version\\s+\"?(?:1\\.)?(\\d+)");

    /** Registry values that name a Java home, as written by the common vendors. */
    private static final Pattern REGISTRY_HOME =
            Pattern.compile("^\\s+(?:JavaHome|Path|InstallationPath)\\s+REG_(?:SZ|EXPAND_SZ)\\s+(.+?)\\s*$");

    /**
     * Registry roots worth asking about, in the order they are asked.
     *
     * <p>A whole-hive search would find these too, and would take minutes. Each
     * of these keys is missing on most machines and answers in milliseconds when
     * it is, which is what makes ten queries cheaper than one broad one.
     */
    private static final List<String> WINDOWS_REGISTRY_KEYS = List.of(
            "HKLM\\SOFTWARE\\JavaSoft",
            "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft",
            "HKLM\\SOFTWARE\\Eclipse Adoptium",
            "HKLM\\SOFTWARE\\Eclipse Foundation",
            "HKLM\\SOFTWARE\\AdoptOpenJDK",
            "HKLM\\SOFTWARE\\Microsoft\\JDK",
            "HKLM\\SOFTWARE\\Azul Systems",
            "HKLM\\SOFTWARE\\Amazon Corretto",
            "HKLM\\SOFTWARE\\BellSoft",
            "HKLM\\SOFTWARE\\IBM\\Semeru Runtime");

    /**
     * Probe results, keyed by the executable's identity on disk.
     *
     * <p>A probe that cannot read a {@code release} file costs a process spawn,
     * and {@code discover()} runs on every launch and every install. Without
     * this, a machine with six runtimes pays six process spawns each time. The
     * key carries size and modification time so that replacing a runtime in
     * place is still noticed.
     */
    private static final Map<String, Integer> PROBE_CACHE = new ConcurrentHashMap<>();

    private final GameDirs dirs;

    public JavaLocator(GameDirs dirs) {
        this.dirs = dirs;
    }

    /**
     * Picks a runtime for {@code requiredMajor}.
     *
     * <p>An explicit per-profile path always wins. Otherwise the choice is made
     * by {@link #choose}, and the failure carries the full list of what was
     * found so that the message says why nothing fit.
     *
     * @param explicitPath a user-configured java executable or java home, or null
     */
    public JavaRuntime locate(String explicitPath, int requiredMajor) throws IOException {
        JavaRuntime explicit = explicit(explicitPath, requiredMajor);
        if (explicit != null) {
            return explicit;
        }

        List<JavaRuntime> candidates = discover();
        return choose(candidates, requiredMajor)
                .orElseThrow(() -> new IOException(describeMissing(requiredMajor, candidates)));
    }

    /**
     * Resolves a profile's explicit Java setting, or returns null when there is
     * none.
     *
     * <p>Separate from {@link #locate} because the caller that can offer to
     * download a runtime still has to honour an explicit path first, and has to
     * fail the same way when that path is wrong: silently downloading a second
     * runtime because the configured one is broken would hide the real fault.
     */
    public JavaRuntime explicit(String explicitPath, int requiredMajor) throws IOException {
        if (explicitPath == null || explicitPath.isBlank()) {
            return null;
        }
        JavaRuntime runtime = probe(normaliseToExecutable(Paths.get(explicitPath.trim())), "profile setting");
        if (runtime == null) {
            throw new IOException("the Java path configured for this profile is not usable: " + explicitPath);
        }
        if (!runtime.satisfies(requiredMajor)) {
            throw new IOException("this profile is set to use " + runtime
                    + ", but this Minecraft version requires Java " + requiredMajor + " or newer");
        }
        return runtime;
    }

    /**
     * Picks the best of {@code candidates} for {@code requiredMajor}.
     *
     * <p>An exact match on the major version is preferred over anything else,
     * and after that the lowest version that still satisfies the requirement
     * wins. Both rules point the same way: run each Minecraft version on the
     * runtime its own era was built and tested against. Mojang publishes a
     * {@code javaVersion} block naming exactly one major per version, and the
     * mod loaders compile against that same one - a newer JVM is a change of
     * environment that nothing in the chain was checked against, and that is
     * where the reflection and module-access failures live.
     */
    public static Optional<JavaRuntime> choose(List<JavaRuntime> candidates, int requiredMajor) {
        return candidates.stream()
                .filter(runtime -> runtime.satisfies(requiredMajor))
                .min(Comparator
                        .comparingInt((JavaRuntime runtime) ->
                                runtime.majorVersion() == requiredMajor ? 0 : 1)
                        .thenComparingInt(JavaRuntime::majorVersion));
    }

    /** Whether any candidate reports exactly {@code major}. */
    public static boolean hasExactly(List<JavaRuntime> candidates, int major) {
        return candidates.stream().anyMatch(runtime -> runtime.majorVersion() == major);
    }

    /** The message shown when nothing on the machine can run this version. */
    public static String describeMissing(int requiredMajor, List<JavaRuntime> candidates) {
        StringBuilder message = new StringBuilder(
                "no Java " + requiredMajor + " or newer runtime was found.\n");
        if (candidates.isEmpty()) {
            message.append("No Java installation was detected at all.");
        } else {
            message.append("Detected:");
            candidates.forEach(runtime -> message.append("\n  ").append(runtime));
        }
        message.append("\n\nInstall a JDK/JRE ").append(requiredMajor)
                .append(" or newer, or set an explicit Java path in the profile settings.");
        return message.toString();
    }

    /** Every Java runtime this machine appears to have. */
    public List<JavaRuntime> discover() {
        Set<Path> seen = new LinkedHashSet<>();
        List<JavaRuntime> found = new ArrayList<>();

        // 1. Runtimes the launcher downloaded. Preferred because they are the
        //    ones whose major version was chosen for the job.
        collectFrom(dirs.javaRuntimes(), 3, "launcher-managed", seen, found);

        // 2. JAVA_HOME.
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            add(executableIn(Paths.get(javaHome)), "JAVA_HOME", seen, found);
        }

        // 3. The JVM running the launcher.
        //
        //    Present when the launcher was started by a real JDK. In a packaged
        //    build it depends on the bundle keeping bin/java: jpackage strips
        //    the native commands out of its embedded runtime by default, and a
        //    runtime with no bin/java cannot start a child process, so there is
        //    nothing to offer here. See the --jlink-options line in build.gradle.
        add(executableIn(Paths.get(System.getProperty("java.home", ""))),
                "launcher's own JVM", seen, found);

        // 4. Anything on PATH. This is how a runtime installed by winget, scoop,
        //    Homebrew, apt or sdkman is found without knowing where each of them
        //    puts things.
        for (Path directory : pathEntries()) {
            add(executableFile(directory.resolve(Platform.javaConsoleExecutableName())), "PATH", seen, found);
        }

        // 5. Conventional install roots.
        for (Path root : commonInstallRoots()) {
            collectFrom(root, 2, root.toString(), seen, found);
        }

        // 6. The official Minecraft launcher's own runtimes. Nested three deep
        //    as <component>/<platform>/<component>/bin/java, and very often the
        //    only Java on a player's machine.
        for (Path root : minecraftRuntimeRoots()) {
            collectFrom(root, 4, "Minecraft launcher runtime", seen, found);
        }

        // 7. Vendor entries in the Windows registry: the reliable way to find an
        //    installation that was put somewhere other than Program Files.
        for (Path home : windowsRegistryJavaHomes()) {
            add(executableIn(home), "Windows registry", seen, found);
        }

        found.sort(Comparator.comparingInt(JavaRuntime::majorVersion));
        return List.copyOf(found);
    }

    /**
     * Adds every runtime found under {@code root}, descending at most
     * {@code depth} levels.
     *
     * <p>Depth rather than an unbounded walk, because these roots sit next to
     * very large trees - {@code Program Files} being the obvious one - and a
     * launcher that stalls for ten seconds before every launch is a launcher
     * people stop using.
     */
    private void collectFrom(Path root, int depth, String source, Set<Path> seen, List<JavaRuntime> found) {
        if (root == null || depth < 0 || !Files.isDirectory(root)) {
            return;
        }
        add(executableIn(root), source, seen, found);
        // macOS bundles nest the runtime inside Contents/Home.
        add(executableIn(root.resolve("Contents").resolve("Home")), source, seen, found);
        if (depth == 0) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    // "bin" and "lib" are the insides of a runtime we already
                    // looked at, not more places to look.
                    .filter(child -> !isRuntimeInternal(child))
                    .forEach(child -> collectFrom(child, depth - 1, source, seen, found));
        } catch (IOException | RuntimeException ignored) {
            // Unreadable or vanished directory: skip it. A permission error on
            // one folder must not stop the rest of the search.
        }
    }

    private static boolean isRuntimeInternal(Path directory) {
        String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("bin") || name.equals("lib") || name.equals("conf")
                || name.equals("include") || name.equals("legal") || name.equals("man");
    }

    private void add(Path executable, String source, Set<Path> seen, List<JavaRuntime> found) {
        if (executable == null || !seen.add(executable)) {
            return;
        }
        JavaRuntime runtime = probe(executable, source);
        if (runtime != null) {
            found.add(runtime);
        }
    }

    private static List<Path> pathEntries() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        for (String part : path.split(Pattern.quote(java.io.File.pathSeparator))) {
            String trimmed = part.trim().replace("\"", "");
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                entries.add(Paths.get(trimmed));
            } catch (RuntimeException ignored) {
                // A malformed PATH entry is the user's, not ours to fix.
            }
        }
        return entries;
    }

    private static List<Path> commonInstallRoots() {
        String home = System.getProperty("user.home", ".");
        return switch (Platform.os()) {
            case WINDOWS -> {
                List<Path> roots = new ArrayList<>();
                for (String programFiles : new String[]{
                        System.getenv("ProgramFiles"),
                        System.getenv("ProgramFiles(x86)"),
                        "C:\\Program Files",
                        "C:\\Program Files (x86)"}) {
                    if (programFiles == null || programFiles.isBlank()) {
                        continue;
                    }
                    Path base = Paths.get(programFiles);
                    for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Eclipse Foundation",
                            "AdoptOpenJDK", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft",
                            "Semeru", "Common Files\\Oracle\\Java", "Android\\Android Studio\\jbr"}) {
                        roots.add(base.resolve(vendor));
                    }
                }
                String localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null && !localAppData.isBlank()) {
                    roots.add(Paths.get(localAppData, "Programs", "Eclipse Adoptium"));
                    roots.add(Paths.get(localAppData, "Programs", "Microsoft"));
                    roots.add(Paths.get(localAppData, "Programs", "Zulu"));
                }
                roots.add(Paths.get(home, ".jdks"));
                roots.add(Paths.get(home, "scoop", "apps"));
                roots.add(Paths.get(home, ".gradle", "jdks"));
                yield List.copyOf(roots);
            }
            case OSX -> List.of(
                    Paths.get("/Library/Java/JavaVirtualMachines"),
                    Paths.get(home, "Library", "Java", "JavaVirtualMachines"),
                    Paths.get("/opt/homebrew/opt"),
                    Paths.get("/usr/local/opt"),
                    Paths.get(home, ".jdks"),
                    Paths.get(home, ".sdkman", "candidates", "java"),
                    Paths.get(home, ".gradle", "jdks"));
            case LINUX -> List.of(
                    Paths.get("/usr/lib/jvm"),
                    Paths.get("/usr/java"),
                    Paths.get("/opt/java"),
                    Paths.get("/opt"),
                    Paths.get(home, ".jdks"),
                    Paths.get(home, ".sdkman", "candidates", "java"),
                    Paths.get(home, ".gradle", "jdks"),
                    Paths.get(home, ".local", "share", "flatpak"));
        };
    }

    /** Where the official Minecraft launcher keeps the runtimes it downloads. */
    private static List<Path> minecraftRuntimeRoots() {
        String home = System.getProperty("user.home", ".");
        return switch (Platform.os()) {
            case WINDOWS -> {
                String appData = System.getenv("APPDATA");
                Path base = (appData == null || appData.isBlank()) ? Paths.get(home) : Paths.get(appData);
                yield List.of(base.resolve(".minecraft").resolve("runtime"));
            }
            case OSX -> List.of(Paths.get(home, "Library", "Application Support", "minecraft", "runtime"));
            case LINUX -> List.of(Paths.get(home, ".minecraft", "runtime"));
        };
    }

    /**
     * Java homes named by the Windows registry.
     *
     * <p>Every vendor installer writes one, and it is the only source that finds
     * a runtime the user installed to a folder of their own choosing. Reading it
     * means spawning {@code reg}, so the result is computed once per launcher
     * run and cached.
     */
    private static List<Path> windowsRegistryJavaHomes() {
        if (!Platform.isWindows()) {
            return List.of();
        }
        List<Path> homes = new ArrayList<>();
        for (String key : WINDOWS_REGISTRY_KEYS) {
            for (String line : runAndRead(List.of("reg", "query", key, "/s"), 5)) {
                Matcher matcher = REGISTRY_HOME.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                try {
                    homes.add(Paths.get(matcher.group(1).trim()));
                } catch (RuntimeException ignored) {
                    // Not a path we can use.
                }
            }
        }
        return homes;
    }

    /** Turns a java home or a java executable path into an executable path. */
    private static Path normaliseToExecutable(Path path) {
        if (Files.isRegularFile(path)) {
            return path;
        }
        Path executable = executableIn(path);
        if (executable != null) {
            return executable;
        }
        // A macOS bundle handed over as-is, e.g. /Applications/....jdk
        Path bundled = executableIn(path.resolve("Contents").resolve("Home"));
        return bundled != null ? bundled : path;
    }

    private static Path executableIn(Path javaHome) {
        if (javaHome == null || !Files.isDirectory(javaHome)) {
            return null;
        }
        return executableFile(javaHome.resolve("bin").resolve(Platform.javaConsoleExecutableName()));
    }

    private static Path executableFile(Path candidate) {
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return null;
        }
        // Resolve so that /usr/bin/java and a symlink chain to the same JDK are
        // recognised as one runtime rather than probed twice.
        try {
            return candidate.toRealPath();
        } catch (IOException e) {
            return candidate.toAbsolutePath().normalize();
        }
    }

    /**
     * Determines a runtime's major version.
     *
     * <p>Reads the {@code release} file first because it is free; falls back to
     * running {@code java -version}, which costs a process spawn but works for
     * runtimes that ship without one.
     */
    public JavaRuntime probe(Path executable, String source) {
        if (executable == null || !Files.isRegularFile(executable)) {
            return null;
        }
        Integer major = PROBE_CACHE.computeIfAbsent(cacheKey(executable), key -> {
            Integer fromRelease = majorFromReleaseFile(executable);
            Integer resolved = fromRelease != null ? fromRelease : majorFromVersionOutput(executable);
            // ConcurrentHashMap will not store null, and a runtime that cannot be
            // identified should not be probed again on the next launch either.
            return resolved == null ? -1 : resolved;
        });
        return (major == null || major < 0) ? null : new JavaRuntime(executable, major, source);
    }

    private static String cacheKey(Path executable) {
        try {
            return executable.toAbsolutePath() + "|" + Files.size(executable)
                    + "|" + Files.getLastModifiedTime(executable).toMillis();
        } catch (IOException e) {
            return executable.toAbsolutePath().toString();
        }
    }

    /** Forgets every probe result. For the self-check, and after an install. */
    public static void clearProbeCache() {
        PROBE_CACHE.clear();
    }

    private static Integer majorFromReleaseFile(Path executable) {
        Path bin = executable.getParent();
        if (bin == null) {
            return null;
        }
        Path home = bin.getParent();
        if (home == null) {
            return null;
        }
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                if (!line.startsWith("JAVA_VERSION=")) {
                    continue;
                }
                String value = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                return parseMajor(value);
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static Integer majorFromVersionOutput(Path executable) {
        for (String line : runAndRead(List.of(executable.toString(), "-version"), 15)) {
            Matcher matcher = VERSION_LINE.matcher(line);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Runs a command and returns its output lines, or nothing if it fails. */
    private static List<String> runAndRead(List<String> command, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            return List.of(output.split("\\R"));
        } catch (IOException | RuntimeException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return List.of();
        }
    }

    /** Parses "25.0.1", "1.8.0_402" and "21" into 25, 8 and 21 respectively. */
    public static Integer parseMajor(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String trimmed = version.trim();
        try {
            if (trimmed.startsWith("1.")) {
                String[] parts = trimmed.split("\\.");
                return parts.length >= 2 ? Integer.parseInt(parts[1]) : null;
            }
            int end = 0;
            while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
                end++;
            }
            return end == 0 ? null : Integer.parseInt(trimmed.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
