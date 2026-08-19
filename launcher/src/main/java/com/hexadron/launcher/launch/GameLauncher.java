package com.hexadron.launcher.launch;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.util.Redactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Starts the game process and streams its output.
 *
 * <p>Two things here exist for security rather than for function:
 *
 * <ul>
 *   <li>The session token is handed to the child over standard input, not on the
 *       command line, whenever the launch wrapper is in use. See
 *       {@link com.hexadron.wrapper.GameLaunchWrapper} for why.</li>
 *   <li>Every line the game prints is passed through
 *       {@link Redactor} before it reaches the log pane. Minecraft's own Log4j
 *       configuration has been known to echo launch arguments, and a mod can
 *       print whatever it likes; the launcher's log view is the last point where
 *       that can be caught before a user copies it into a support channel.</li>
 * </ul>
 */
public final class GameLauncher {

    /** A running game, with its output already being pumped to the log consumer. */
    public static final class GameSession {
        private final Process process;
        private final LaunchCommandBuilder.LaunchCommand command;

        GameSession(Process process, LaunchCommandBuilder.LaunchCommand command) {
            this.process = process;
            this.command = command;
        }

        public Process process() {
            return process;
        }

        public LaunchCommandBuilder.LaunchCommand command() {
            return command;
        }

        public boolean isRunning() {
            return process.isAlive();
        }

        public void terminate() {
            process.destroy();
        }

        public void terminateForcibly() {
            process.destroyForcibly();
        }

        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }
    }

    /**
     * Starts the game.
     *
     * @param onOutput receives every stdout/stderr line, in order
     * @param onExit   receives the exit code once the process ends
     */
    public GameSession start(LaunchCommandBuilder.LaunchCommand command,
                             Consumer<String> onOutput,
                             IntConsumer onExit,
                             Progress progress) throws IOException {

        Files.createDirectories(command.workingDirectory());

        progress.stage("Starting Minecraft");
        progress.log("Java: %s", command.javaExecutable());
        progress.log("Main class: %s", command.realMainClass());
        progress.log("Classpath entries: %d", command.classpath().size());
        progress.log(command.usesSecureHandshake()
                ? "Session token: sent over standard input, not on the command line"
                : "Session token: on the command line (offline account or wrapper unavailable)");

        ProcessBuilder builder = new ProcessBuilder(command.command())
                .directory(command.workingDirectory().toFile())
                // One stream keeps stdout and stderr interleaved in the order the
                // game actually produced them, which is what makes a crash log readable.
                .redirectErrorStream(true);

        Process process = builder.start();
        GameSession session = new GameSession(process, command);

        sendSecrets(process, command.secrets());

        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onOutput.accept(Redactor.scrub(line));
                }
            } catch (IOException e) {
                onOutput.accept("[launcher] stopped reading game output: " + Redactor.scrub(e.getMessage()));
            }
        }, "minecraft-output");
        pump.setDaemon(true);
        pump.start();

        Thread waiter = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                // Let the pump drain before reporting the exit code, so the last
                // lines of a crash are not lost to a race.
                pump.join(3000);
                onExit.accept(exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "minecraft-waiter");
        waiter.setDaemon(true);
        waiter.start();

        return session;
    }

    /**
     * Completes the handshake with the launch wrapper.
     *
     * <p>The stream is closed straight afterwards. Leaving it open would give a
     * mod inside the game an inherited pipe back to the launcher, and the
     * wrapper has no reason to read anything after {@code launch}.
     *
     * <p>When there is nothing to send - an offline account, or a build without
     * the wrapper jar - the stream is still closed, so a game that reads stdin
     * sees a clean EOF rather than blocking.
     */
    private static void sendSecrets(Process process, Map<String, String> secrets) throws IOException {
        try (OutputStream stdin = process.getOutputStream()) {
            if (secrets.isEmpty()) {
                return;
            }
            StringBuilder handshake = new StringBuilder();
            for (Map.Entry<String, String> entry : secrets.entrySet()) {
                handshake.append("secret ")
                        .append(entry.getKey())
                        .append(' ')
                        .append(Base64.getEncoder().encodeToString(
                                entry.getValue().getBytes(StandardCharsets.UTF_8)))
                        .append('\n');
            }
            handshake.append("launch\n");
            stdin.write(handshake.toString().getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    /** Human-readable interpretation of a Minecraft exit code. */
    public static String describeExit(int exitCode) {
        return switch (exitCode) {
            case 92 -> "The launcher could not hand the session to Minecraft "
                    + "(launch handshake failed). Try again; if it repeats, turn off "
                    + "the secure launch handshake in settings and report it.";
            case 0 -> "Minecraft closed normally.";
            case 1 -> "Minecraft exited with code 1 - usually a crash during startup. "
                    + "Check the log above for the first exception.";
            case 137, 143 -> "Minecraft was killed (exit " + exitCode
                    + ") - commonly the operating system running out of memory.";
            default -> "Minecraft exited with code " + exitCode + ".";
        };
    }
}
