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

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.LauncherLog;
import com.hexadron.launcher.i18n.I18n;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What to put in a bug report, and where to send it.
 *
 * <h2>Why a window and not a link</h2>
 *
 * <p>The button could open the issue tracker directly, and most of the reports
 * that arrived would be one sentence: "it crashes". That is not a report - it
 * is a request for the questions to be asked instead, one exchange at a time,
 * across whatever days two people are awake at different hours. This window
 * asks them once, before anything is sent, at the only moment the reporter has
 * the fault in front of them and still remembers what they did.
 *
 * <h2>The log is the point</h2>
 *
 * <p>Of everything here, the log is the part a reporter cannot reconstruct
 * later and the part that most often decides whether a fault can be found at
 * all - and it is also the part they will not attach, because they do not know
 * the launcher keeps one or where. So it is not described, it is named: the
 * file, the folder it is in, and a link that opens that folder with it
 * selected. What is left to do is drag it into the issue.
 *
 * <p>The newest file rather than the current one. They are almost always the
 * same file, and the exception is the case that matters most: a run that ended
 * badly enough that the next one has already rotated it to
 * {@code launcher-1.log}, or one where logging never started at all and the
 * newest thing on disk is the run before.
 *
 * <h2>Nothing is sent from here</h2>
 *
 * <p>The button opens the issue form in the user's own browser. A launcher that
 * posted a report by itself would be a launcher that uploaded a log the user
 * had not read, from a machine whose paths, account names and mod list are in
 * it. They send it, having seen it, or they do not.
 */
final class ReportBugDialog {

    /** Where reports go: the repository's own new-issue form. */
    static final String ISSUES_URL =
            "https://github.com/SAN4EZDREAMS/HexadronLauncher/issues/new";

    private static final double WIDTH = 560;

    private final GameDirs dirs;

    ReportBugDialog(GameDirs dirs) {
        this.dirs = dirs;
    }

    void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("bug.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        ButtonType send = new ButtonType(I18n.t("bug.send"), ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType(I18n.t("dialog.close"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(send, close);
        dialog.getDialogPane().setContent(build());
        Theme.apply(dialog.getDialogPane());
        dialog.getDialogPane().lookupButton(send).getStyleClass().add("primary");

        if (dialog.showAndWait().filter(send::equals).isPresent()) {
            SystemBrowser.open(ISSUES_URL);
        }
    }

    private VBox build() {
        Label heading = new Label(I18n.t("bug.heading"));
        heading.getStyleClass().add("section-title");

        VBox root = new VBox(14,
                heading,
                wrapped(I18n.t("bug.capture")),
                questions(),
                new Separator(),
                logSection(),
                new Separator(),
                whereSection());
        root.setPadding(new Insets(18, 22, 8, 22));
        root.setPrefWidth(WIDTH);
        return root;
    }

    /**
     * The three questions, as three lines rather than a paragraph.
     *
     * <p>A paragraph asking for three things gets two of them. A list is read
     * as a list of things to do, and a reporter can see which one they have not
     * answered yet.
     */
    private VBox questions() {
        VBox list = new VBox(4);
        list.setPadding(new Insets(0, 0, 0, 6));
        for (String key : new String[]{"bug.what", "bug.how", "bug.repeat"}) {
            Label line = new Label("•  " + I18n.t(key));
            line.setWrapText(true);
            line.setMinHeight(Region.USE_PREF_SIZE);
            line.setMaxWidth(WIDTH - 60);
            list.getChildren().add(line);
        }
        return list;
    }

    // ------------------------------------------------------------------- log

    private VBox logSection() {
        Path log = newestLog();
        VBox section = new VBox(6, wrapped(I18n.t("bug.attachLog")));

        if (log == null) {
            // No file, so no link that would do nothing when clicked. The folder
            // is still worth naming: it is where one will be next time.
            Label none = new Label(I18n.t("bug.noLog", dirs.logs().toAbsolutePath()));
            none.getStyleClass().add("muted");
            none.setWrapText(true);
            none.setMinHeight(Region.USE_PREF_SIZE);
            none.setMaxWidth(WIDTH - 48);
            section.getChildren().add(none);
            return section;
        }

        Hyperlink link = new Hyperlink(log.getFileName().toString());
        link.getStyleClass().add("about-link");
        Tooltip.install(link, new Tooltip(I18n.t("bug.openFolder")));
        link.setOnAction(event -> SystemFiles.reveal(log));

        Label folder = new Label(log.toAbsolutePath().getParent().toString());
        folder.getStyleClass().add("muted");
        folder.setWrapText(true);
        folder.setMinHeight(Region.USE_PREF_SIZE);
        folder.setMaxWidth(WIDTH - 170);

        HBox row = new HBox(8, link, folder);
        row.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().add(row);
        return section;
    }

    /**
     * The newest log, whichever run wrote it.
     *
     * <p>{@link LauncherLog#file()} is the fallback rather than the first
     * answer: it is null on a run where logging could not start, and a run where
     * logging could not start is a run with something worth reporting.
     */
    private Path newestLog() {
        Path newest = SystemFiles.newestLog(dirs.logs());
        if (newest != null) {
            return newest;
        }
        Path current = LauncherLog.file();
        return current != null && Files.isRegularFile(current) ? current : null;
    }

    // ----------------------------------------------------------------- where

    private VBox whereSection() {
        Hyperlink address = new Hyperlink(ISSUES_URL);
        address.getStyleClass().add("about-link");
        Tooltip.install(address, new Tooltip(ISSUES_URL));
        address.setOnAction(event -> SystemBrowser.open(ISSUES_URL));

        // The address in full as well as on the button. Somebody reporting from
        // a machine with no desktop integration - which is one of the things
        // worth reporting - needs to be able to read it and type it somewhere
        // else, and a button is not something you can copy out of.
        return new VBox(4, wrapped(I18n.t("bug.where")), address);
    }

    // ---------------------------------------------------------------- shared

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(WIDTH - 48);
        return label;
    }
}
