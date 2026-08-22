package com.hexadron.launcher;

import com.hexadron.launcher.cli.HexadronCli;

import javafx.application.Application;

/**
 * Entry point for packaged builds.
 *
 * <p>It exists for one reason, and the reason is a rule in the JDK's own
 * launcher rather than anything about this program: when the class named on the
 * command line <em>extends</em> {@link Application}, the launcher checks that
 * {@code javafx.graphics} is a resolved module and refuses to start otherwise,
 * with
 *
 * <pre>Error: JavaFX runtime components are missing, and are required to run this application</pre>
 *
 * <p>A packaged application built with {@code jpackage} carries JavaFX as plain
 * jars on the class path, not as modules on the module path - so {@link Launcher}
 * cannot be the class named on the command line. This one is not an
 * {@code Application}, so the check does not apply, and
 * {@code Application.launch(Launcher.class, ...)} starts the toolkit itself.
 *
 * <p>{@link Launcher} keeps its own {@code main} for {@code ./gradlew run},
 * where the JavaFX Gradle plugin does put the modules on the module path. Both
 * routes end in the same place.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // Arguments mean headless use. Checked before the toolkit is touched, so
        // the command-line mode works on a machine with no display at all.
        if (args.length > 0) {
            HexadronCli.main(args);
            return;
        }
        Application.launch(Launcher.class, args);
    }
}
