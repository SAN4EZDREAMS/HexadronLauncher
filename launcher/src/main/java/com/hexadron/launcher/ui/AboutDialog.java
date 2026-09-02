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

package com.hexadron.launcher.ui;

import com.hexadron.launcher.BuildConfig;
import com.hexadron.launcher.about.Credits;
import com.hexadron.launcher.i18n.I18n;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;

/**
 * Who made this, what it is built on, and under what terms.
 *
 * <h2>Three things, in that order</h2>
 *
 * <p>The mark and the version first, because the first question anybody opens
 * this window with is "which build am I running" - and it is the question
 * every bug report needs answered. Then the person, because a project with a
 * name on it is a project somebody can be reached about. Then everything it
 * stands on, because a launcher that fetches a loader's installer, runs
 * somebody else's Java agent and installs somebody else's mods is not a thing
 * that was written from nothing, and saying so is the least of what is owed.
 *
 * <p>The list itself is not in this file - it is a resource, so a project that
 * moves or a licence that changes is an edit rather than a release. This is
 * only how it is laid out.
 *
 * <h2>Links are opened, not shown</h2>
 *
 * <p>Every one goes to the system browser. A launcher is not a browser and
 * should not pretend to be one; a window inside it showing somebody's YouTube
 * channel would be a worse version of the thing the user already has.
 */
final class AboutDialog {

    private static final double LOGO = 72;
    private static final double WIDTH = 620;
    private static final double LIST_HEIGHT = 300;

    void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("about.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        ButtonType close = new ButtonType(I18n.t("dialog.close"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(close);
        dialog.getDialogPane().setContent(build());
        Theme.apply(dialog.getDialogPane());
        dialog.showAndWait();
    }

    private VBox build() {
        Credits credits;
        try {
            credits = Credits.load();
        } catch (IOException | RuntimeException e) {
            // The window is still worth showing: the version and the licence are
            // in the code, and those are the two things somebody came for.
            credits = new Credits(new Credits.Author("SAN4EZDREAMS", java.util.List.of()),
                    null, java.util.List.of());
        }

        VBox root = new VBox(16, banner(credits), new Separator(), author(credits),
                new Separator(), creditsList(credits), licence());
        root.setPadding(new Insets(20, 22, 10, 22));
        root.setPrefWidth(WIDTH);
        return root;
    }

    // ------------------------------------------------------------------ banner

    private HBox banner(Credits credits) {
        Label name = new Label(I18n.t("app.title"));
        name.getStyleClass().add("about-title");

        Label version = new Label(I18n.t("about.version", BuildConfig.version()));
        version.getStyleClass().add("muted");

        Label what = new Label(I18n.t("about.what"));
        what.getStyleClass().add("muted");
        what.setWrapText(true);
        what.setMinHeight(Region.USE_PREF_SIZE);
        what.setMaxWidth(WIDTH - LOGO - 80);

        VBox words = new VBox(3, name, version, what);
        words.setAlignment(Pos.CENTER_LEFT);

        if (credits.repository() != null) {
            words.getChildren().add(new VBox(6,
                    link(I18n.t("about.repository"), credits.repository())));
        }

        HBox row = new HBox(18, logo(), words);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(words, Priority.ALWAYS);
        return row;
    }

    /**
     * The mark, from the same PNG the window and the taskbar use.
     *
     * <p>Not redrawn here: an about window showing a slightly different logo
     * from the one in the title bar is the sort of detail that makes a program
     * look assembled rather than made.
     */
    private Region logo() {
        try {
            ImageView view = new ImageView(new javafx.scene.image.Image(
                    AboutDialog.class.getResourceAsStream(
                            Brand.ICON_RESOURCE.formatted(128))));
            view.setFitWidth(LOGO);
            view.setFitHeight(LOGO);
            view.setSmooth(true);
            VBox holder = new VBox(view);
            holder.getStyleClass().add("about-logo");
            return holder;
        } catch (RuntimeException e) {
            Label mark = new Label("H");
            mark.getStyleClass().add("brand-mark");
            return new VBox(mark);
        }
    }

    // ------------------------------------------------------------------ author

    private VBox author(Credits credits) {
        Label heading = new Label(I18n.t("about.author"));
        heading.getStyleClass().add("section-title");

        Label name = new Label(credits.author().name());
        name.getStyleClass().add("about-author");

        FlowPane links = new FlowPane(8, 6);
        credits.author().links().forEach(
                one -> links.getChildren().add(link(one.name(), one.url())));

        return new VBox(6, heading, name, links);
    }

    // ----------------------------------------------------------------- credits

    private VBox creditsList(Credits credits) {
        Label heading = new Label(I18n.t("about.builtOn"));
        heading.getStyleClass().add("section-title");

        VBox items = new VBox(14);
        items.setPadding(new Insets(2, 12, 6, 0));

        for (Credits.Group group : credits.groups()) {
            Label groupName = new Label(I18n.t(group.heading()));
            groupName.getStyleClass().add("about-group");
            items.getChildren().add(groupName);

            for (Credits.Entry entry : group.entries()) {
                items.getChildren().add(entry(entry));
            }
        }

        ScrollPane scroller = new ScrollPane(items);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPrefViewportHeight(LIST_HEIGHT);
        scroller.setMinHeight(LIST_HEIGHT);
        scroller.setMaxHeight(LIST_HEIGHT);

        VBox box = new VBox(6, heading, scroller);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * One credit: the name as a link, what was taken under it, and the terms.
     *
     * <p>The role line is the point of the list. A wall of project names says
     * only that they exist; "downloaded and attached to the game so skins from
     * a service other than Mojang are shown" says what this launcher would not
     * do without them.
     */
    private VBox entry(Credits.Entry entry) {
        VBox box = new VBox(1, link(entry.name(), entry.url()));
        box.getStyleClass().add("about-entry");

        if (entry.role() != null) {
            box.getChildren().add(note(entry.role()));
        }
        if (entry.licence() != null) {
            Label licence = note(entry.licence());
            licence.getStyleClass().add("about-licence");
            box.getChildren().add(licence);
        }
        return box;
    }

    // ----------------------------------------------------------------- licence

    private Label licence() {
        Label note = new Label(I18n.t("about.licence"));
        note.getStyleClass().addAll("muted", "about-footer");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        return note;
    }

    // ------------------------------------------------------------------- parts

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(WIDTH - 70);
        return label;
    }

    /**
     * A link that opens in the browser.
     *
     * <p>The address is the tooltip rather than the text, so the list reads as
     * names and a person can still see where a name goes before pressing it.
     */
    private static Hyperlink link(String text, String url) {
        Hyperlink hyperlink = new Hyperlink(text);
        hyperlink.getStyleClass().add("about-link");
        Tooltip.install(hyperlink, new Tooltip(url));
        hyperlink.setOnAction(event -> open(url));
        return hyperlink;
    }

    /**
     * Hands a link to the system browser.
     *
     * <p>Checked again here even though {@link Credits} drops anything that is
     * not https. This method is the one that hands a string to the operating
     * system, and the cost of being sure at that point is one comparison.
     */
    private static void open(String url) {
        if (Credits.safe(url) == null) {
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception e) {
            com.hexadron.launcher.core.LauncherLog.warn("Could not open %s: %s", url, e);
        }
    }
}
