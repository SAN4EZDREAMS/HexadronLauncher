package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The window shown while the launcher is starting.
 *
 * <p>It exists because start-up is not free and pretending otherwise is worse
 * than showing it. The JavaFX toolkit has to come up, settings, profiles and
 * accounts have to be read, and on a first run the data folder has to be
 * created. Before this, all of that happened with nothing on screen: the user
 * double-clicked and watched their desktop for a second or two, which reads as
 * a program that did not start.
 *
 * <p>So the splash appears as early as anything can - it is the first thing
 * {@code Launcher.start} does - and the real work then runs on a background
 * thread, reporting each stage as it begins. The list of stages is not
 * decoration: every line is a step that actually ran, and each keeps the time it
 * actually took. The same screen that reassures a user is therefore also the
 * first thing to look at when someone reports a slow start.
 *
 * <p>There is a floor on how long it stays up. A splash that flashes for eighty
 * milliseconds is a glitch rather than a splash, and on a warm start that is
 * exactly what would happen.
 *
 * <p>Every method except the constructor and {@link #show()} is safe to call
 * from any thread.
 */
public final class SplashScreen {

    /**
     * How long the splash stays up when nothing says otherwise.
     *
     * <p>Used only until {@code launcher.json} has been read, which happens a
     * few milliseconds in - the stored value replaces it through
     * {@link #minimumVisible(long)} before {@link #done} is ever called. It
     * cannot come from settings at construction, because reading settings is
     * itself one of the stages this window is showing.
     */
    private static final long DEFAULT_MINIMUM_VISIBLE_MILLIS = 3000;

    private static final Duration FADE_IN = Duration.millis(180);
    private static final Duration FADE_OUT = Duration.millis(260);

    /** How many stage lines are on screen at once. */
    private static final int VISIBLE_LINES = 6;

    private static final double LINE_HEIGHT = 18;

    /**
     * The stage lines are monospaced so that the times line up in a column as
     * they are stamped on.
     *
     * <p>{@code Monospaced} is one of JavaFX's logical families: it resolves to
     * a real monospaced font on every platform, which a named font such as
     * Consolas or Menlo does not.
     */
    private static final String LINE_FONT_STYLE =
            "-fx-font-family: 'Monospaced'; -fx-font-size: 11.5px;";
    private static final double SIZE = 420;
    private static final double RING_RADIUS = 52;

    /** Skips the splash entirely. For development, and for a stubborn desktop. */
    public static final String DISABLE_PROPERTY = "hexadron.nosplash";

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final VBox lines = new VBox(2);
    private final ProgressBar bar = new ProgressBar(0);
    private final Label versionLabel = new Label();
    private final Label skipHint = new Label();

    private final List<Animation> animations = new ArrayList<>();

    /** A stage of start-up: when it began, and the line standing for it. */
    private record Timing(String name, long startedNanos, Label label) {
    }

    private final List<Timing> timings = new ArrayList<>();
    private final List<String> completed = new ArrayList<>();

    private final long startedNanos = System.nanoTime();
    private final int expectedSteps;

    private Timing current;
    private boolean closed;

    private long minimumVisibleMillis = DEFAULT_MINIMUM_VISIBLE_MILLIS;

    /**
     * The wait between the last stage and the fade.
     *
     * <p>Held so that a click can stop it. Null except during that wait.
     */
    private PauseTransition hold;

    /** Set when the user asked to skip before {@link #done} was called. */
    private boolean skipped;

    /** True from the start of the fade, so a second click cannot start another. */
    private boolean leaving;

    /** What to run once the window has gone. Handed over by {@link #done}. */
    private Runnable afterClose;

    /**
     * The summary, frozen when {@link #done} is called.
     *
     * <p>Frozen rather than computed on demand, because the total has to mean
     * "how long start-up took" and not "how long the window has been on screen".
     * Those differ by the minimum display time and the fade, and only the first
     * one is worth logging.
     */
    private String finalSummary;

    /**
     * @param version       shown under the wordmark
     * @param expectedSteps how many calls to {@link #step} to expect, for the bar
     */
    public SplashScreen(String version, int expectedSteps) {
        this.expectedSteps = Math.max(1, expectedSteps);
        versionLabel.setText(version);
        build();
    }

    /** True when the splash has been switched off for this run. */
    public static boolean isDisabled() {
        return Boolean.parseBoolean(System.getProperty(DISABLE_PROPERTY, "false"));
    }

    // ------------------------------------------------------------------ build

    private void build() {
        Label title = new Label("HexadronLauncher");
        title.getStyleClass().add("splash-title");

        versionLabel.getStyleClass().add("splash-version");

        lines.setAlignment(Pos.TOP_LEFT);
        lines.setPrefHeight(VISIBLE_LINES * LINE_HEIGHT);
        lines.setMinHeight(VISIBLE_LINES * LINE_HEIGHT);
        lines.setMaxHeight(VISIBLE_LINES * LINE_HEIGHT);
        lines.getStyleClass().add("splash-lines");

        bar.getStyleClass().add("splash-bar");
        bar.setPrefWidth(300);
        bar.setMaxWidth(300);

        skipHint.setText(I18n.t("splash.skip"));
        skipHint.getStyleClass().add("splash-skip");
        skipHint.setOpacity(0);

        VBox content = new VBox(12, mark(), title, versionLabel, lines, bar, skipHint);
        content.setAlignment(Pos.TOP_CENTER);
        content.setPadding(new Insets(34, 30, 28, 30));
        VBox.setMargin(lines, new Insets(8, 0, 4, 0));
        VBox.setMargin(skipHint, new Insets(2, 0, 0, 0));

        StackPane card = new StackPane(content);
        card.getStyleClass().add("splash-card");

        StackPane root = new StackPane(card);
        root.setPrefSize(SIZE, SIZE);
        root.setPadding(new Insets(14));
        // Inline, because the theme's .root rule paints the scene root opaque -
        // which on a transparent window is a dark square where the rounded
        // corners and the drop shadow should be. An inline style outranks a
        // stylesheet, so this is the one place it is the right tool.
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, SIZE, SIZE, Color.TRANSPARENT);
        Theme.apply(scene);

        stage.setScene(scene);
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.setTitle("HexadronLauncher");
        stage.getIcons().setAll(Brand.windowIcons());

        // A minimum display time is a promise to the user that they get to read
        // the thing; it is not a licence to hold their launcher hostage. Anyone
        // who has seen it can click or press a key and be done.
        scene.setOnMouseClicked(event -> skip());
        scene.setOnKeyPressed(event -> skip());
        root.setFocusTraversable(true);
        root.requestFocus();
    }

    /**
     * The animated mark: a hexagon turning one way, a sweep turning the other,
     * and the launcher's own square breathing in the middle.
     *
     * <p>Rotation rather than a filling gauge, because the thing it stands for
     * genuinely has no percentage - reading a settings file has either happened
     * or it has not. The bar underneath counts stages, which is a number that
     * means something.
     *
     * <p>The sweep is a dashed circle rather than an {@code Arc}, and that is a
     * layout detail worth writing down: an arc's bounds cover only the part of
     * the circle it draws, so a {@code StackPane} centres it by that lopsided
     * box and it drifts off the middle. A full circle whose stroke is mostly
     * dashed away has symmetric bounds and sits exactly where the hexagon does.
     */
    private StackPane mark() {
        Polygon hexagon = new Polygon();
        for (int corner = 0; corner < 6; corner++) {
            double angle = Math.toRadians(60.0 * corner - 90);
            hexagon.getPoints().addAll(
                    RING_RADIUS * Math.cos(angle), RING_RADIUS * Math.sin(angle));
        }
        hexagon.setFill(Color.TRANSPARENT);
        hexagon.setStroke(Brand.LINE);
        hexagon.setStrokeWidth(2);

        double sweepRadius = RING_RADIUS - 8;
        double circumference = 2 * Math.PI * sweepRadius;
        Circle sweep = new Circle(sweepRadius);
        sweep.setFill(Color.TRANSPARENT);
        sweep.setStroke(Brand.ACCENT_LIGHT);
        sweep.setStrokeWidth(3);
        sweep.setStrokeLineCap(StrokeLineCap.ROUND);
        sweep.getStrokeDashArray().addAll(circumference * 0.2, circumference);

        Rectangle plate = new Rectangle(54, 54, Brand.ACCENT);
        plate.setArcWidth(28);
        plate.setArcHeight(28);

        Text letter = new Text("H");
        letter.setFill(Color.WHITE);
        letter.setFont(Font.font(Brand.FONT_FAMILY, FontWeight.BOLD, 32));

        StackPane centre = new StackPane(plate, letter);
        centre.setMaxSize(54, 54);

        StackPane pane = new StackPane(hexagon, sweep, centre);
        double box = RING_RADIUS * 2 + 10;
        pane.setPrefSize(box, box);
        pane.setMinSize(box, box);
        pane.setMaxSize(box, box);

        animations.add(spin(hexagon, 6000, 360));
        animations.add(spin(sweep, 1400, -360));

        ScaleTransition breathe = new ScaleTransition(Duration.millis(1400), centre);
        breathe.setFromX(1);
        breathe.setFromY(1);
        breathe.setToX(1.07);
        breathe.setToY(1.07);
        breathe.setAutoReverse(true);
        breathe.setCycleCount(Animation.INDEFINITE);
        breathe.setInterpolator(Interpolator.EASE_BOTH);
        animations.add(breathe);

        return pane;
    }

    private static RotateTransition spin(Node node, int millis, double degrees) {
        RotateTransition rotate = new RotateTransition(Duration.millis(millis), node);
        rotate.setByAngle(degrees);
        rotate.setCycleCount(Animation.INDEFINITE);
        rotate.setInterpolator(Interpolator.LINEAR);
        return rotate;
    }

    // ------------------------------------------------------------------- api

    /** Shows the window and starts the animation. Call on the JavaFX thread. */
    public void show() {
        Node root = stage.getScene().getRoot();
        root.setOpacity(0);
        stage.show();
        stage.centerOnScreen();
        animations.forEach(Animation::play);

        FadeTransition fade = new FadeTransition(FADE_IN, root);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        // Late, and only if the splash is still up by then. Offering a way out
        // of a window that closed itself half a second ago is noise.
        PauseTransition delay = new PauseTransition(Duration.millis(1100));
        delay.setOnFinished(event -> {
            if (closed) {
                return;
            }
            FadeTransition hint = new FadeTransition(Duration.millis(400), skipHint);
            hint.setFromValue(0);
            hint.setToValue(1);
            hint.play();
        });
        delay.play();
    }

    /**
     * Sets how long the window stays up at minimum.
     *
     * <p>Called once the settings have been read. Zero means no floor at all,
     * which on a warm start is a window that appears and vanishes - offered
     * because it is somebody's preference, not because it looks good.
     */
    public void minimumVisible(long millis) {
        long value = Math.max(0, millis);
        if (Platform.isFxApplicationThread()) {
            minimumVisibleMillis = value;
        } else {
            Platform.runLater(() -> minimumVisibleMillis = value);
        }
    }

    /**
     * Cuts the wait short.
     *
     * <p>Two cases, because the click can land on either side of the last stage
     * finishing: if the wait has already started, stop it and let it finish now;
     * if it has not, remember that it should not happen.
     */
    private void skip() {
        if (closed || leaving) {
            return;
        }
        skipped = true;
        PauseTransition running = hold;
        if (running == null) {
            // The last stage has not finished yet. Nothing to cut short; done()
            // will see the flag and not wait at all.
            return;
        }
        hold = null;
        running.stop();
        fadeOutAndClose();
    }

    /**
     * Ends the stage that was running and begins this one.
     *
     * @param key one of {@code LauncherService.STARTUP_STEPS}, or any other
     *            identifier; shown through the {@code splash.step.<key>} string
     */
    public void step(String key) {
        long now = System.nanoTime();
        Platform.runLater(() -> {
            if (closed) {
                return;
            }
            finishCurrent(now);

            Label label = new Label("·  " + I18n.t("splash.step." + key));
            label.getStyleClass().add("splash-line");
            // Inline, and only for the font. The stylesheet sets a font size on
            // .root, fonts are inherited in JavaFX CSS, and an inherited value
            // beats one set with setFont - so a stage line would quietly come
            // out in the interface font at the interface size. An inline style
            // outranks both. Colour stays in the stylesheet, because that is
            // what changes when the stage finishes.
            label.setStyle(LINE_FONT_STYLE);
            label.setOpacity(0);
            lines.getChildren().add(label);

            FadeTransition fade = new FadeTransition(Duration.millis(140), label);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.play();

            while (lines.getChildren().size() > VISIBLE_LINES) {
                lines.getChildren().remove(0);
            }

            current = new Timing(key, now, label);
            timings.add(current);
            bar.setProgress(Math.min(1.0, (double) timings.size() / expectedSteps));
        });
    }

    /** Dims the running stage's line to grey and stamps it with its duration. */
    private void finishCurrent(long now) {
        if (current == null) {
            return;
        }
        long millis = Math.max(0, (now - current.startedNanos()) / 1_000_000);
        String name = I18n.t("splash.step." + current.name());
        current.label().setText("·  " + name + "   " + millis + " ms");
        current.label().getStyleClass().add("splash-line-done");
        completed.add(name + " " + millis + " ms");
        current = null;
    }

    /**
     * Closes the splash, not before it has been visible long enough to be seen.
     *
     * @param then run on the JavaFX thread once the window has gone
     */
    public void done(Runnable then) {
        Platform.runLater(() -> {
            if (closed) {
                then.run();
                return;
            }
            finishCurrent(System.nanoTime());
            bar.setProgress(1);
            finalSummary = String.join(", ", completed) + "; total " + elapsedMillis() + " ms";

            afterClose = then;

            long shown = (System.nanoTime() - startedNanos) / 1_000_000;
            long wait = skipped ? 0 : Math.max(0, minimumVisibleMillis - shown);

            hold = new PauseTransition(Duration.millis(wait));
            hold.setOnFinished(event -> {
                hold = null;
                fadeOutAndClose();
            });
            hold.play();
        });
    }

    /**
     * Fades the window out, closes it, and runs whatever {@link #done} was
     * given.
     *
     * <p>Reached from two places - the wait ending by itself, and a click
     * cutting it short - and it must do its work exactly once whichever gets
     * there first, which is what {@code leaving} is for.
     */
    private void fadeOutAndClose() {
        if (closed || leaving) {
            return;
        }
        leaving = true;
        Node root = stage.getScene().getRoot();
        FadeTransition fade = new FadeTransition(FADE_OUT, root);
        fade.setFromValue(root.getOpacity());
        fade.setToValue(0);
        fade.setOnFinished(finished -> {
            close();
            Runnable after = afterClose;
            afterClose = null;
            if (after != null) {
                after.run();
            }
        });
        fade.play();
    }

    /** Closes at once, without waiting or fading. For a failed start-up. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        animations.forEach(Animation::stop);
        animations.clear();
        stage.close();
    }

    /** Time from construction to now, in milliseconds. */
    public long elapsedMillis() {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * One line naming every stage and what it cost.
     *
     * <p>Written into the launcher's log once the window is up, so that "it
     * takes ages to start" can be answered with numbers rather than guesses.
     */
    public String summary() {
        String frozen = finalSummary;
        return frozen != null
                ? frozen
                : String.join(", ", completed) + "; total " + elapsedMillis() + " ms";
    }
}
