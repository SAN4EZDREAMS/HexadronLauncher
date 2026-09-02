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

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

/**
 * The right-click menu, built once and used by both interfaces.
 *
 * <h2>Why it is shared</h2>
 *
 * <p>The list and the inventory grid offer the same actions on a profile, and
 * the fastest way for them to stop offering the same actions is for each to
 * build its own menu. One builder means an action added here appears in both,
 * with the same wording and the same order, and a profile cannot be deletable in
 * one view and not the other.
 *
 * <p>The menu is rebuilt on every request rather than cached, because half of it
 * depends on state that changes underneath it: which groups exist, whether this
 * profile has a chosen icon, and which language is active.
 *
 * <h2>Menus on plain nodes</h2>
 *
 * <p>{@code setContextMenu} belongs to {@code Control}, and the rows and cells
 * here are layout panes, which are not controls. So the menu is shown from
 * {@code setOnContextMenuRequested}, which every node has - and which is also
 * the event the platform raises for the keyboard's own menu key, not just for
 * the right button.
 */
final class ProfileMenu {

    /**
     * The menu that is on screen, if any.
     *
     * <p>One field for the whole interface, because a second menu must replace
     * the first rather than join it. Every right-click built a fresh menu and
     * showed it without hiding what was already up, so clicking twice in the same
     * cell left two menus stacked, and a few clicks left a pile of them - each
     * one live, each one over the last.
     *
     * <p>Static, and that is deliberate: the menus come from three different
     * places - a cell, a plate, an empty cell - and "only one menu at a time" is
     * a fact about the screen rather than about any one of them.
     */
    private static ContextMenu showing;

    private ProfileMenu() {
    }

    /**
     * Shows a menu, replacing whatever was up before it.
     *
     * <p>The stylesheet is applied after {@code show} and not before: a popup has
     * no scene until it is on screen, and an unstyled menu over a dark window
     * reads as a different program.
     */
    static void show(ContextMenu menu, Node node, double screenX, double screenY) {
        if (showing != null) {
            showing.hide();
        }
        showing = menu;
        menu.setOnHidden(event -> {
            if (showing == menu) {
                showing = null;
            }
        });
        menu.show(node, screenX, screenY);
        Theme.apply(menu.getScene());
    }

    /** Attaches the profile menu to any node. Replaces an earlier handler. */
    static void install(Node node, ProfileHost host, Profile profile) {
        node.setOnContextMenuRequested(event -> {
            show(forProfile(host, profile), node, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    /**
     * Attaches the group menu to any node - the list header, or the grid plate.
     *
     * @param rows whether the two row items belong in this menu. Rows are a fact
     *             about the grid: they are the cells a group occupies there, and
     *             they are visible, draggable and countable only in that view.
     *             The list draws a group as a heading and its instances beneath
     *             it, with no rows on screen at all, so "add a row" there is an
     *             item that changes something the user cannot see.
     */
    static void installForGroup(Node node, ProfileHost host, ProfileLayout.Group group,
            boolean rows) {
        node.setOnContextMenuRequested(event -> {
            show(forGroup(host, group, rows), node, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private static ContextMenu forProfile(ProfileHost host, Profile profile) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                item(I18n.t("action.play"), () -> host.play(profile)),
                item(I18n.t("action.edit"), () -> host.edit(profile)),
                item(I18n.t("action.install"), () -> host.install(profile)),
                item(I18n.t("action.mods"), () -> host.openMods(profile)),
                item(I18n.t("action.openFolder"), () -> host.openFolder(profile)),
                new SeparatorMenuItem(),
                item(I18n.t("icon.choose"), () -> host.chooseIcon(profile)));

        MenuItem clearIcon = item(I18n.t("icon.clear"), () -> host.clearIcon(profile));
        clearIcon.setDisable(!profile.hasCustomIcon());
        menu.getItems().add(clearIcon);

        menu.getItems().addAll(new SeparatorMenuItem(), groupSubmenu(host, profile));
        menu.getItems().addAll(new SeparatorMenuItem(),
                item(I18n.t("profiles.remove"), () -> host.remove(profile)));
        return menu;
    }

    /**
     * The "put this in a group" submenu.
     *
     * <p>Present because dragging is not the only way anyone works. A drag needs
     * both the profile and its destination on screen at once, which stops being
     * true at twenty instances with the destination group collapsed at the
     * bottom - and it is not available at all to somebody driving the launcher
     * from the keyboard.
     */
    private static Menu groupSubmenu(ProfileHost host, Profile profile) {
        Menu submenu = new Menu(I18n.t("groups.moveTo"));
        ProfileLayout layout = host.layout();
        String currentGroup = layout.groupOf(profile.id())
                .map(ProfileLayout.Group::id).orElse(null);

        MenuItem newGroup = item(I18n.t("groups.new"), () -> host.createGroup(profile));
        submenu.getItems().addAll(newGroup, new SeparatorMenuItem());

        MenuItem none = item(I18n.t("groups.none"), () -> {
            if (!layout.join(profile.id(), null)) {
                host.hint(I18n.t("grid.noRoom"));
                return;
            }
            host.layoutChanged();
        });
        none.setDisable(currentGroup == null);
        submenu.getItems().add(none);

        for (ProfileLayout.Group group : layout.groups()) {
            MenuItem into = item(group.name(), () -> {
                // A move, because membership is the row: the profile goes to a
                // free cell in one of the group's rows.
                if (!layout.join(profile.id(), group.id())) {
                    host.hint(I18n.t("grid.noRoom"));
                    return;
                }
                layout.setCollapsed(group.id(), false);
                host.layoutChanged();
            });
            into.setDisable(group.id().equals(currentGroup));
            submenu.getItems().add(into);
        }
        return submenu;
    }

    /**
     * The group menu.
     *
     * <p>A group owns rows, so the two row items belong here rather than on the
     * grid's edges: the strips there change the size of the whole table, and
     * "one more row in this group" is a different thing that has to be said
     * about a particular group. They are in this menu only when it is opened
     * from the grid - see {@code rows}.
     */
    private static ContextMenu forGroup(ProfileHost host, ProfileLayout.Group group,
            boolean rows) {
        ContextMenu menu = new ContextMenu();
        ProfileLayout layout = host.layout();

        menu.getItems().addAll(
                item(I18n.t(group.collapsed() ? "groups.expand" : "groups.collapse"), () -> {
                    layout.setCollapsed(group.id(), !group.collapsed());
                    host.layoutChanged();
                }),
                item(I18n.t("groups.settings"), () -> host.editGroup(group)),
                new SeparatorMenuItem(),
                // The menu's way of doing what dragging the plate does. A drag
                // needs both ends on screen at once, and is not available at all
                // from a keyboard.
                item(I18n.t("groups.moveUp"), () -> {
                    if (!layout.moveGroupBy(group.id(), true)) {
                        host.hint(I18n.t("groups.moveFailed"));
                        return;
                    }
                    host.layoutChanged();
                }),
                item(I18n.t("groups.moveDown"), () -> {
                    if (!layout.moveGroupBy(group.id(), false)) {
                        host.hint(I18n.t("groups.moveFailed"));
                        return;
                    }
                    host.layoutChanged();
                }),
                new SeparatorMenuItem());

        if (rows) {
            MenuItem removeRow = item(I18n.t("groups.removeRow"), () -> {
                if (!layout.removeRowFromGroup(group.id())) {
                    // Either it is the group's only row, or the profiles in it
                    // have nowhere to go. Both are worth saying rather than
                    // doing nothing.
                    host.hint(I18n.t(layout.rowsOf(group.id()).size() <= 1
                            ? "grid.lastGroupRow" : "grid.noRoom"));
                    return;
                }
                host.layoutChanged();
            });
            removeRow.setDisable(layout.rowsOf(group.id()).size() <= 1);

            menu.getItems().addAll(
                    item(I18n.t("groups.addRow"), () -> {
                        if (!layout.addRowToGroup(group.id())) {
                            host.hint(I18n.t("grid.atMaximum"));
                            return;
                        }
                        host.layoutChanged();
                    }),
                    removeRow,
                    new SeparatorMenuItem());
        }

        menu.getItems().add(item(I18n.t("groups.remove"), () -> host.removeGroup(group)));
        return menu;
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem menuItem = new MenuItem(text);
        menuItem.setOnAction(event -> action.run());
        return menuItem;
    }
}
