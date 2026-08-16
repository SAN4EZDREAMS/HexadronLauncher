package com.hexadron.launcher.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * Applies the launcher's stylesheet.
 *
 * <p>One stylesheet, applied to the window and to every dialog. A dialog that
 * keeps the platform default look while the window behind it is dark reads as a
 * different program, so dialogs get the same sheet rather than inheriting
 * nothing.
 */
public final class Theme {

    private static final String STYLESHEET = "/ui/hexadron.css";

    public static void apply(Scene scene) {
        if (scene != null) {
            add(scene.getStylesheets());
        }
    }

    public static void apply(Parent parent) {
        if (parent != null) {
            add(parent.getStylesheets());
        }
    }

    private static void add(javafx.collections.ObservableList<String> sheets) {
        var url = Theme.class.getResource(STYLESHEET);
        if (url == null) {
            // Missing stylesheet is a packaging fault, not a reason not to start.
            return;
        }
        String path = url.toExternalForm();
        if (!sheets.contains(path)) {
            sheets.add(path);
        }
    }

    private Theme() {
    }
}
