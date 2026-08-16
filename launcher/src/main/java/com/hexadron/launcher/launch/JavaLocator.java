package com.hexadron.launcher.launch;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 */
public final class JavaLocator {

    /** A Java installation and the major version it reports. */
    public record JavaRuntime(Path executable, int majorVersion, String source) {

        public boolean satisfies(int requiredMajor) {
            return majorVersion >= requiredMajor;
        }

        @Override
        public String toString() {
            return "Java " + majorVersion + " at " + executable + " (" + source + ")";
        }
    }

    private static final Pattern VERSION_LINE =
            Pattern.compile("version\\s+\"?(?:1\\.)?(\\d+)");

    private final GameDirs dirs;

    public JavaLocator(GameDirs dirs) {
        this.dirs = dirs;
    }

    /**
     * Picks a runtime for {@code requiredMajor}.
     *
     * <p>Order of preference: an explicit per-profile path, then a runtime the
     * launcher manages, then {@code JAVA_HOME}, then the JVM the launcher itself
     * runs on, then anything on {@code PATH}. The first that meets the required
     * major version wins.
     *
     * @param explicitPath a user-configured java executable or java home, or null
     */
    public JavaRuntime locate(String explicitPath, int requiredMajor) throws IOException {
        if (explicitPath != null && !explicitPath.isBlank()) {
            JavaRuntime runtime = probe(normaliseToExecutable(Paths.get(explicitPath)), "profile setting");
            if (runtime == null) {
                throw new IOException("the Java path configured for this profile is not usable: " + explicitPath);
            }
            if (!runtime.satisfies(requiredMajor)) {
                throw new IOException("this profile is set to use " + runtime
                        + ", but this Minecraft version requires Java " + requiredMajor + " or newer");
            }
            return runtime;
        }

        List<JavaRuntime> candidates = discover();
        Optional<JavaRuntime> match = candidates.stream()
                .filter(runtime -> runtime.satisfies(requiredMajor))
                // Prefer the lowest version that still satisfies the requirement:
                // running an old version on a much newer JVM is where the obscure
                // reflection failures live.
                .min((a, b) -> Integer.compare(a.majorVersion(), b.majorVersion()));

        if (match.isPresent()) {
            return match.get();
        }

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
        throw new IOException(message.toString());
    }

    /** Every Java runtime this machine appears to have. */
    public List<JavaRuntime> discover() {
        Set<Path> seen = new LinkedHashSet<>();
        List<JavaRuntime> found = new ArrayList<>();

        // 1. Runtimes the launcher manages.
        Path managed = dirs.javaRuntimes();
        if (Files.isDirectory(managed)) {
            try (var stream = Files.list(managed)) {
                stream.filter(Files::isDirectory).forEach(home -> {
                    Path executable = executableIn(home);
                    if (executable != null && seen.add(executable)) {
                        addIfValid(found, executable, "launcher-managed");
                    }
                });
            } catch (IOException ignored) {
                // Not fatal: fall through to the other sources.
            }
        }

        // 2. JAVA_HOME.
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path executable = executableIn(Paths.get(javaHome));
            if (executable != null && seen.add(executable)) {
                addIfValid(found, executable, "JAVA_HOME");
            }
        }

        // 3. The JVM running the launcher.
        Path ownHome = Paths.get(System.getProperty("java.home", ""));
        Path ownExecutable = executableIn(ownHome);
        if (ownExecutable != null && seen.add(ownExecutable)) {
            addIfValid(found, ownExecutable, "launcher's own JVM");
        }

        // 4. Conventional install roots.
        for (Path root : commonInstallRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.list(root)) {
                stream.filter(Files::isDirectory).forEach(home -> {
                    Path executable = executableIn(home);
                    // macOS bundles nest the runtime inside Contents/Home.
                    if (executable == null) {
                        executable = executableIn(home.resolve("Contents").resolve("Home"));
                    }
                    if (executable != null && seen.add(executable)) {
                        addIfValid(found, executable, root.toString());
                    }
                });
            } catch (IOException ignored) {
                // Unreadable directory: skip it.
            }
        }

        return List.copyOf(found);
    }

    private void addIfValid(List<JavaRuntime> out, Path executable, String source) {
        JavaRuntime runtime = probe(executable, source);
        if (runtime != null) {
            out.add(runtime);
        }
    }

    private static List<Path> commonInstallRoots() {
        return switch (Platform.os()) {
            case WINDOWS -> List.of(
                    Paths.get("C:\\Program Files\\Java"),
                    Paths.get("C:\\Program Files\\Eclipse Adoptium"),
                    Paths.get("C:\\Program Files\\Microsoft"),
                    Paths.get("C:\\Program Files\\Zulu"),
                    Paths.get("C:\\Program Files (x86)\\Java"));
            case OSX -> List.of(
                    Paths.get("/Library/Java/JavaVirtualMachines"),
                    Paths.get(System.getProperty("user.home", "."), "Library", "Java", "JavaVirtualMachines"));
            case LINUX -> List.of(
                    Paths.get("/usr/lib/jvm"),
                    Paths.get("/usr/java"),
                    Paths.get("/opt/java"),
                    Paths.get(System.getProperty("user.home", "."), ".sdkman", "candidates", "java"));
        };
    }

    /** Turns a java home or a java executable path into an executable path. */
    private static Path normaliseToExecutable(Path path) {
        if (Files.isRegularFile(path)) {
            return path;
        }
        Path executable = executableIn(path);
        return executable != null ? executable : path;
    }

    private static Path executableIn(Path javaHome) {
        if (javaHome == null || !Files.isDirectory(javaHome)) {
            return null;
        }
        Path console = javaHome.resolve("bin").resolve(Platform.javaConsoleExecutableName());
        return Files.isRegularFile(console) ? console : null;
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
        Integer major = majorFromReleaseFile(executable);
        if (major == null) {
            major = majorFromVersionOutput(executable);
        }
        return major == null ? null : new JavaRuntime(executable, major, source);
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
        try {
            Process process = new ProcessBuilder(executable.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            Matcher matcher = VERSION_LINE.matcher(output);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
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
