package com.hexadron.launcher.launch;

import com.hexadron.launcher.core.Progress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Starts the game process and streams its output. */
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
        progress.log("Main class: %s", command.mainClass());
        progress.log("Classpath entries: %d", command.classpath().size());

        ProcessBuilder builder = new ProcessBuilder(command.command())
                .directory(command.workingDirectory().toFile())
                // One stream keeps stdout and stderr interleaved in the order the
                // game actually produced them, which is what makes a crash log readable.
                .redirectErrorStream(true);

        Process process = builder.start();
        GameSession session = new GameSession(process, command);

        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onOutput.accept(line);
                }
            } catch (IOException e) {
                onOutput.accept("[launcher] stopped reading game output: " + e.getMessage());
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

    /** Human-readable interpretation of a Minecraft exit code. */
    public static String describeExit(int exitCode) {
        return switch (exitCode) {
            case 0 -> "Minecraft closed normally.";
            case 1 -> "Minecraft exited with code 1 - usually a crash during startup. "
                    + "Check the log above for the first exception.";
            case 137, 143 -> "Minecraft was killed (exit " + exitCode
                    + ") - commonly the operating system running out of memory.";
            default -> "Minecraft exited with code " + exitCode + ".";
        };
    }
}
