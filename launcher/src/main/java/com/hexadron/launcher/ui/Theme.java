/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.LauncherLog;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.theme.Appearance;
import com.hexadron.launcher.theme.BackdropImage;
import com.hexadron.launcher.theme.Palette;
import com.hexadron.launcher.theme.ThemeCss;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Applies the launcher's stylesheet, in the look the player chose.
 *
 * <p>One stylesheet, applied to the window and to every dialog. A dialog that
 * keeps the platform default look while the window behind it is dark reads as a
 * different program, so dialogs get the same sheet rather than inheriting
 * nothing.
 *
 * <h2>The look is a generated file</h2>
 *
 * <p>Until {@link #init} the sheet is {@code hexadron.css} from the jar. After
 * it, it is a file in {@code <data>/cache/theme} that {@link ThemeCss} wrote
 * from that sheet and the {@link Appearance} in the settings. Every list of
 * stylesheets this class has ever added to is remembered (weakly, so a closed
 * window is not kept alive by it), and a new look replaces the old file in all
 * of them at once - which is what makes the settings window's preview reach
 * every open window, and Cancel put all of them back.
 *
 * <p>Each look gets a new file name. JavaFX keeps a parsed stylesheet for as
 * long as its URL is in use, so the same name with new contents would change
 * nothing on screen.
 */
public final class Theme {

    private static final String STYLESHEET = "/ui/hexadron.css";
    private static final String LIGHT_STYLESHEET = "/ui/hexadron-light.css";

    /** Every stylesheet list a sheet was added to. FX thread only. */
    private static final Set<ObservableList<String>> LISTS =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** Every URL this class has handed out, so an old one can be found and replaced. */
    private static final Set<String> ISSUED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Called on the FX thread after a new look is on screen. */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile String current;
    private static volatile Appearance appearance = Appearance.DEFAULT;
    private static volatile Path dataRoot;
    private static volatile Path preparedBackground;
    private static String baseCss;
    private static String lightCss;

    /** One worker, so looks are prepared in the order they were asked for. */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "hexadron-theme");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Turns the chosen look on, before the first window is built.
     *
     * <p>Synchronous: the main window should open in its colours rather than
     * flash the default ones first. The prepared background picture is
     * normally already in the cache from the last run, so this is one small
     * file write.
     */
    public static void init(Path data, Appearance chosen) {
        dataRoot = data;
        appearance = chosen == null ? Appearance.DEFAULT : chosen;
        removeOldSheets(null);
        String url = generate(appearance, GENERATION.incrementAndGet());
        if (url != null) {
            swap(url);
        }
        tidy();
    }

    /**
     * Deletes prepared background pictures other than the one on screen. The
     * others are kept while the settings window is open, so a slider moved
     * back finds its earlier picture; after that they are only disk space.
     */
    public static void tidy() {
        if (dataRoot == null) {
            return;
        }
        Path keep = appearance.hasBackground() || appearance.showsPattern() ? preparedBackground : null;
        WORKER.execute(() -> BackdropImage.prune(cacheDir(), keep));
    }

    /** The look on screen, or being prepared. */
    public static Appearance appearance() {
        return appearance;
    }

    /** True when the look on screen has a light window colour. */
    public static boolean isLight() {
        return appearance.palette().isLight();
    }

    /**
     * Puts a new look on every open window. Prepared off the interface thread
     * - a blurred picture can take a moment - and when several are asked for
     * quickly, as a slider does, only the last one is shown.
     */
    public static void use(Appearance chosen) {
        Appearance next = chosen == null ? Appearance.DEFAULT : chosen;
        appearance = next;
        long generation = GENERATION.incrementAndGet();
        if (dataRoot == null) {
            return;
        }
        WORKER.execute(() -> {
            if (generation != GENERATION.get()) {
                return;
            }
            String url = generate(next, generation);
            if (url != null) {
                Platform.runLater(() -> {
                    if (generation == GENERATION.get()) {
                        swap(url);
                    }
                });
            }
        });
    }

    /** Runs {@code listener} on the interface thread each time the look changes. */
    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Stops calling a listener given to {@link #onChange}. */
    public static void removeOnChange(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * The inline style of a group's band: its colour, mixed towards the window.
     * Dark under a dark theme, a pale wash under a light one; the text on it
     * is the stylesheet's "ink", which follows the same switch.
     */
    public static String bandStyle(String groupColor, int darkBorder) {
        if (isLight()) {
            return "-fx-background-color: derive(" + groupColor + ", 82%);"
                    + " -fx-border-color: derive(" + groupColor + ", 20%);";
        }
        return "-fx-background-color: derive(" + groupColor + ", -74%);"
                + " -fx-border-color: derive(" + groupColor + ", " + darkBorder + "%);";
    }

    // ---------------------------------------------------------------- applying

    public static void apply(Scene scene) {
        if (scene != null) {
            add(scene.getStylesheets());
        }
    }

    /**
     * A window of the launcher's own: the stylesheet, and the mark that lets
     * the background picture be drawn on it. Not for popups, whose roots stay
     * plain so menus and tooltips are solid over whatever is behind them.
     */
    public static void applyWindow(Scene scene) {
        if (scene == null) {
            return;
        }
        add(scene.getStylesheets());
        if (scene.getRoot() != null) {
            mark(scene.getRoot());
        }
        scene.rootProperty().addListener((observable, previous, root) -> {
            if (root != null) {
                mark(root);
            }
        });
    }

    /**
     * A dialog: the stylesheet, and the words on its buttons.
     *
     * <p>The buttons are the reason this overload exists. JavaFX writes them
     * itself, from its own resource bundle rather than the launcher's, so a
     * dialog whose every sentence was translated still offered "OK" and
     * "Cancel" in English underneath. Only the standard buttons are touched;
     * one a window made for itself already says what that window wanted.
     */
    public static void apply(DialogPane pane) {
        if (pane == null) {
            return;
        }
        apply((Parent) pane);
        // A dialog's pane is the root of its window.
        mark(pane);
        for (ButtonType type : pane.getButtonTypes()) {
            String key = keyOf(type);
            // lookupButton builds the button if it has not been built yet,
            // which is why this works before the dialog is ever shown.
            if (key != null && pane.lookupButton(type) instanceof Labeled button) {
                button.setText(I18n.t(key));
            }
        }
    }

    /**
     * The translation key for one of JavaFX's own buttons.
     *
     * <p>By identity rather than by button data, because two of them share it:
     * Close and Cancel are both {@code CANCEL_CLOSE}, and a window with a Close
     * button on it should not start saying Cancel.
     */
    private static String keyOf(ButtonType type) {
        if (type == ButtonType.OK) {
            return "dialog.ok";
        }
        if (type == ButtonType.CANCEL) {
            return "dialog.cancel";
        }
        if (type == ButtonType.YES) {
            return "dialog.yes";
        }
        if (type == ButtonType.NO) {
            return "dialog.no";
        }
        if (type == ButtonType.CLOSE) {
            return "dialog.close";
        }
        if (type == ButtonType.APPLY) {
            return "dialog.apply";
        }
        if (type == ButtonType.FINISH) {
            return "dialog.finish";
        }
        if (type == ButtonType.NEXT) {
            return "dialog.next";
        }
        if (type == ButtonType.PREVIOUS) {
            return "dialog.previous";
        }
        return null;
    }

    public static void apply(Parent parent) {
        if (parent != null) {
            add(parent.getStylesheets());
        }
    }

    private static void mark(Parent root) {
        if (!root.getStyleClass().contains(ThemeCss.BACKDROP_CLASS)) {
            root.getStyleClass().add(ThemeCss.BACKDROP_CLASS);
        }
    }

    private static void add(ObservableList<String> sheets) {
        String path = currentUrl();
        if (path == null) {
            // Missing stylesheet is a packaging fault, not a reason not to start.
            return;
        }
        LISTS.add(sheets);
        for (String issued : new ArrayList<>(sheets)) {
            if (!issued.equals(path) && ISSUED.contains(issued)) {
                sheets.remove(issued);
            }
        }
        if (!sheets.contains(path)) {
            sheets.add(path);
        }
    }

    private static String currentUrl() {
        if (current != null) {
            return current;
        }
        var url = Theme.class.getResource(STYLESHEET);
        if (url == null) {
            return null;
        }
        current = url.toExternalForm();
        ISSUED.add(current);
        return current;
    }

    // ---------------------------------------------------------------- generating

    private static Path cacheDir() {
        return dataRoot.resolve("cache").resolve("theme");
    }

    /**
     * Writes the stylesheet for a look and returns its URL, or null when it
     * could not be written - in which case the look on screen stays as it is,
     * which is better than a window with no stylesheet at all.
     */
    private static String generate(Appearance look, long generation) {
        try {
            synchronized (Theme.class) {
                if (baseCss == null) {
                    baseCss = resource(STYLESHEET);
                    lightCss = resource(LIGHT_STYLESHEET);
                }
            }
            Path cache = cacheDir();
            Files.createDirectories(cache);
            String backgroundUrl = null;
            Path prepared = null;
            if (look.hasBackground()) {
                Path picture = dataRoot.resolve(look.background());
                if (Files.isRegularFile(picture)) {
                    try {
                        prepared = BackdropImage.prepare(picture, cache,
                                look.palette().get(Palette.Slot.BACKGROUND), look.dim(), look.blur());
                        backgroundUrl = prepared.toUri().toString();
                        preparedBackground = prepared;
                    } catch (IOException | RuntimeException e) {
                        LauncherLog.warn("Theme: background picture not drawn: %s", e.toString());
                    }
                } else {
                    LauncherLog.warn("Theme: background picture is missing: %s", picture);
                }
            }
            Appearance drawn = look;
            if (look.showsPattern()) {
                try {
                    prepared = preparePattern(look, cache);
                    if (prepared != null) {
                        backgroundUrl = prepared.toUri().toString();
                        preparedBackground = prepared;
                        // A pattern is a tile: it is only ever repeated.
                        drawn = look.withFit(Appearance.Fit.TILE);
                    }
                } catch (IOException | RuntimeException e) {
                    LauncherLog.warn("Theme: the theme's pattern not drawn: %s", e.toString());
                }
            }
            String css = ThemeCss.build(baseCss, lightCss, drawn, backgroundUrl);
            Path file = cache.resolve("theme-" + ProcessHandle.current().pid() + "-" + generation + ".css");
            Files.writeString(file, css, StandardCharsets.UTF_8);
            return file.toUri().toString();
        } catch (IOException | RuntimeException e) {
            LauncherLog.warn("Theme: stylesheet not written, keeping the current one: %s", e.toString());
            return null;
        }
    }

    /**
     * The theme's tile, laid on the window colour and faded as a picture
     * would be. Not blurred: a blur reaches past the tile's edge and would
     * show a seam at every repeat.
     */
    private static Path preparePattern(Appearance look, Path cache) throws IOException {
        String name = look.preset().patternResource();
        if (name == null) {
            return null;
        }
        byte[] tile;
        try (InputStream in = Theme.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing from the jar: " + name);
            }
            tile = in.readAllBytes();
        }
        return BackdropImage.prepareTile(tile, look.preset().id(), cache,
                look.palette().get(Palette.Slot.BACKGROUND), patternFade(look.dim()));
    }

    /**
     * How far a pattern is faded, for the Fade slider's value. Never less than
     * {@link #PATTERN_FADE_MIN}: a picture has calm areas where text can sit,
     * but a pattern is busy everywhere, and headings lie straight on it.
     */
    static int patternFade(int dim) {
        return PATTERN_FADE_MIN + dim * (100 - PATTERN_FADE_MIN) / 100;
    }

    /** The least a pattern is faded, in percent, with the Fade slider at zero. */
    static final int PATTERN_FADE_MIN = 45;

    private static String resource(String name) throws IOException {
        try (InputStream in = Theme.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing from the jar: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Replaces the sheet in every list it was added to. FX thread. */
    private static void swap(String url) {
        String previous = current;
        current = url;
        ISSUED.add(url);
        for (ObservableList<String> sheets : new ArrayList<>(LISTS)) {
            int at = -1;
            for (int i = 0; i < sheets.size(); i++) {
                if (ISSUED.contains(sheets.get(i))) {
                    at = i;
                    break;
                }
            }
            sheets.removeIf(sheet -> ISSUED.contains(sheet) && !sheet.equals(url));
            if (!sheets.contains(url)) {
                sheets.add(at < 0 ? sheets.size() : Math.min(at, sheets.size()), url);
            }
        }
        unpinButtonBars();
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LauncherLog.error("Theme: a window did not take the new look", e);
            }
        }
        if (previous != null && previous.startsWith("file:")) {
            WORKER.execute(() -> removeOldSheets(url));
        }
    }

    /**
     * Lets dialog buttons find their width again.
     *
     * <p>A button bar gives its buttons one width, the widest's, and writes it
     * onto each as a fixed preferred width; it then measures them by that same
     * preferred width. So once set it never grows: after a larger font every
     * button kept the width of its old word and showed an ellipsis. Clearing
     * the width makes the next layout measure the words again.
     */
    private static void unpinButtonBars() {
        for (javafx.stage.Window window : new ArrayList<>(javafx.stage.Window.getWindows())) {
            if (window.getScene() == null || window.getScene().getRoot() == null) {
                continue;
            }
            for (javafx.scene.Node node : window.getScene().getRoot().lookupAll(".button-bar")) {
                if (node instanceof javafx.scene.control.ButtonBar bar) {
                    for (javafx.scene.Node button : bar.getButtons()) {
                        if (button instanceof javafx.scene.layout.Region region) {
                            region.setPrefWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
                        }
                    }
                    bar.requestLayout();
                }
            }
        }
    }

    /** Deletes generated sheets of this process other than {@code keep}, and every other process's. */
    private static void removeOldSheets(String keep) {
        if (dataRoot == null) {
            return;
        }
        Path cache = cacheDir();
        if (!Files.isDirectory(cache)) {
            return;
        }
        String own = "theme-" + ProcessHandle.current().pid() + "-";
        try (var files = Files.list(cache)) {
            files.filter(file -> file.getFileName().toString().startsWith("theme-"))
                    .filter(file -> keep == null || !file.toUri().toString().equals(keep))
                    .filter(file -> keep != null || !file.getFileName().toString().startsWith(own))
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                            // Another launcher may still be reading it.
                        }
                    });
        } catch (IOException ignored) {
            // A cache; nothing depends on it being tidy.
        }
    }

    private Theme() {
    }
}
