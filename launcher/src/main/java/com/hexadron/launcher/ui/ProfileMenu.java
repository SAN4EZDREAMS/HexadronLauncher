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

    private ProfileMenu() {
    }

    /** Attaches the profile menu to any node. Replaces an earlier handler. */
    static void install(Node node, ProfileHost host, Profile profile) {
        node.setOnContextMenuRequested(event -> {
            ContextMenu menu = forProfile(host, profile);
            menu.show(node, event.getScreenX(), event.getScreenY());
            // After show, not before: a popup has no scene until it is on
            // screen, and an unstyled menu over a dark window reads as a
            // different program.
            Theme.apply(menu.getScene());
            event.consume();
        });
    }

    /** Attaches the group menu to any node - the list header, or the grid rail. */
    static void installForGroup(Node node, ProfileHost host, ProfileLayout.Group group) {
        node.setOnContextMenuRequested(event -> {
            ContextMenu menu = forGroup(host, group);
            menu.show(node, event.getScreenX(), event.getScreenY());
            Theme.apply(menu.getScene());
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
            layout.join(profile.id(), null);
            host.layoutChanged();
        });
        none.setDisable(currentGroup == null);
        submenu.getItems().add(none);

        for (ProfileLayout.Group group : layout.groups()) {
            MenuItem into = item(group.name(), () -> {
                // Membership only: the profile keeps its cell, because a group
                // is a colour in the grid and not a place.
                layout.join(profile.id(), group.id());
                layout.setCollapsed(group.id(), false);
                host.layoutChanged();
            });
            into.setDisable(group.id().equals(currentGroup));
            submenu.getItems().add(into);
        }
        return submenu;
    }

    private static ContextMenu forGroup(ProfileHost host, ProfileLayout.Group group) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                item(I18n.t(group.collapsed() ? "groups.expand" : "groups.collapse"), () -> {
                    host.layout().setCollapsed(group.id(), !group.collapsed());
                    host.layoutChanged();
                }),
                item(I18n.t("groups.rename"), () -> host.renameGroup(group)),
                new SeparatorMenuItem(),
                item(I18n.t("groups.remove"), () -> host.removeGroup(group)));
        return menu;
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem menuItem = new MenuItem(text);
        menuItem.setOnAction(event -> action.run());
        return menuItem;
    }
}
