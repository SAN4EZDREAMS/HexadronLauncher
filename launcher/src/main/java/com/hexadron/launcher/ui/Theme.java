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

import com.hexadron.launcher.i18n.I18n;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;

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
