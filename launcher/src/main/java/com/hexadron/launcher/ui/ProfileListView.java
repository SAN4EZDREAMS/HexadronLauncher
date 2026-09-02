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
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of instances: rows, groups, and drag to rearrange.
 *
 * <h2>The list has no order of its own</h2>
 *
 * <p>What it draws is {@link ProfileLayout#listRows()}: the grid's cells walked
 * in reading order with the empty ones left out, and each group's header placed
 * where its first member sits. So a hole in the grid is nothing here, exactly as
 * specified - two profiles with a free cell between them are two consecutive
 * rows.
 *
 * <p>Dragging a row therefore does not move a cell. It reorders which profile
 * sits in which of the already-occupied cells, so the gaps stay where the user
 * put them and only the contents change. Dragging a group header moves all of
 * its members together, for the same reason.
 *
 * <p>Where a row is dropped also decides its group: a profile dropped among the
 * members of a group joins that group, and one dropped among the loose rows
 * leaves whatever group it was in. That is the reading of the gesture that
 * matches what the row looks like once it lands.
 *
 * <h2>Rows rather than a ListView</h2>
 *
 * <p>A {@code ListView} recycles its cells, and a recycled cell is the wrong
 * thing to hang a drag and a drop indicator on: the cell under the pointer at
 * the end of a drag is not necessarily the one the drag started from, so the
 * highlight lands on whatever row the cell was reused for. This list is tens of
 * rows, not thousands, so it is built as plain rows in a {@link VBox} and drawn
 * in full.
 */
public final class ProfileListView {

    private final ProfileHost host;
    private final VBox rows = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Label empty = new Label();

    /** The row currently showing a drop line, so it can be cleared again. */
    private Region marked;

    /** The band showing a drop line, and the inline style to put back on it. */
    private Region markedBand;
    private String markedBandStyle;

    /**
     * The row of each profile on screen, for moving the selection highlight.
     *
     * <p>Selection must not rebuild the list: it happens on mouse-pressed, which
     * is the press a drag starts from, so a rebuild there would replace the node
     * the drag was about to begin on and dragging would silently stop working.
     */
    private final Map<String, Region> rowsByProfile = new HashMap<>();

    public ProfileListView(ProfileHost host) {
        this.host = host;

        rows.getStyleClass().add("profile-rows");
        rows.setFillWidth(true);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("profile-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        empty.getStyleClass().add("muted");
        empty.setWrapText(true);
    }

    public Region node() {
        return scroll;
    }

    /**
     * Rebuilds every row from the arrangement. Cheap: tens of rows, no network.
     *
     * <p>A group is drawn as one block - a tinted panel with a coloured rail down
     * its left, holding the header and its members - rather than as rows that
     * happen to be indented. Indentation alone left the question the grid answers
     * at a glance unanswered here: where a group ends, and which of two adjacent
     * groups a row belongs to.
     */
    public void rebuild() {
        marked = null;
        rowsByProfile.clear();
        rows.getChildren().clear();

        // Filtered first, so a band knows which of its members are actually
        // going to be drawn inside it.
        List<ProfileLayout.ListRow> drawn = new ArrayList<>();
        for (ProfileLayout.ListRow row : host.layout().listRows()) {
            if (row.isGroup()) {
                // A group whose members are all filtered out is hidden with
                // them, rather than left as a heading the search cannot explain.
                if (host.filter().isEmpty() || visibleMembers(row.group()) > 0) {
                    drawn.add(row);
                }
                continue;
            }
            Profile profile = profile(row.profileId());
            if (profile != null && host.matchesFilter(profile)) {
                drawn.add(row);
            }
        }

        boolean anything = !drawn.isEmpty();
        VBox content = null;
        HBox band = null;

        for (ProfileLayout.ListRow row : drawn) {
            if (row.isGroup()) {
                content = new VBox(2);
                content.getStyleClass().add("profile-band-content");
                // The panel first, so that every row inside it can be told which
                // band it is in - which is what lets a dropped group be shown
                // landing beside the whole block rather than inside it.
                band = bandFor(row.group(), content);
                Node header = groupRow(row.group(), row.memberCount(), band);
                content.getChildren().add(header);
                rows.getChildren().add(band);
                continue;
            }
            Profile profile = profile(row.profileId());
            if (profile == null) {
                continue;
            }
            // A nested row goes into the open band; anything else closes it.
            if (row.isNested() && content != null) {
                content.getChildren().add(profileRow(profile, true, band));
            } else {
                content = null;
                band = null;
                rows.getChildren().add(profileRow(profile, false, null));
            }
        }

        if (!anything) {
            empty.setText(host.filter().isEmpty()
                    ? I18n.t("instance.none.body")
                    : I18n.t("mods.noResults"));
            StackPane holder = new StackPane(empty);
            holder.setPadding(new Insets(18, 8, 18, 8));
            rows.getChildren().add(holder);
        }

        // The tail. Somewhere has to mean "after the last row and in no group",
        // or a profile dragged out of the last group has nowhere to land.
        rows.getChildren().add(tailTarget());
    }

    /** Moves the highlight without rebuilding anything. */
    public void applySelection() {
        Profile selected = host.selected();
        String id = selected == null ? null : selected.id();
        for (Map.Entry<String, Region> entry : rowsByProfile.entrySet()) {
            boolean on = entry.getKey().equals(id);
            var classes = entry.getValue().getStyleClass();
            if (on && !classes.contains("profile-row-selected")) {
                classes.add("profile-row-selected");
            } else if (!on) {
                classes.remove("profile-row-selected");
            }
        }
    }

    // ---------------------------------------------------------------- rows

    private Node profileRow(Profile profile, boolean inGroup, Region band) {
        Node icon = ProfileIcons.node(profile, host.service().dirs(), 22);

        Label name = new Label(profile.name());
        name.getStyleClass().add("instance-name");
        Label subtitle = new Label(profile.minecraftVersion()
                + (profile.loader() == LoaderType.VANILLA
                        ? ""
                        : "  ·  " + profile.loader().displayName()));
        subtitle.getStyleClass().add("instance-subtitle");

        VBox text = new VBox(1, name, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, icon, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("profile-row");
        if (inGroup) {
            row.getStyleClass().add("profile-row-nested");
        }
        if (profile.equals(host.selected())) {
            row.getStyleClass().add("profile-row-selected");
        }
        rowsByProfile.put(profile.id(), row);

        row.setOnMousePressed(event -> {
            host.select(profile);
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                host.play(profile);
            }
        });
        ProfileMenu.install(row, host, profile);

        dragSource(row, ProfileDrag.profile(profile.id()));
        dropTarget(row, band, (payload, after) -> {
            ProfileLayout layout = host.layout();
            String dragged = ProfileDrag.id(payload);
            if (ProfileDrag.isProfile(payload)) {
                // moveProfileBeside does the joining itself, from the target's
                // own row - dropped among a group's members it joins them,
                // dropped among the loose rows it leaves.
                layout.moveProfileBeside(dragged, profile.id(), after);
                return true;
            }
            // By row, so that the layout steps over the target's whole group
            // rather than splitting it - and so the drop line the user was shown
            // is where the group actually lands.
            int targetRow = layout.cellOf(profile.id()).map(cell -> cell[0]).orElse(-1);
            return targetRow >= 0 && layout.moveBandBeside(dragged, targetRow, after);
        });
        return row;
    }

    /**
     * The panel behind a group and its members.
     *
     * <p>The same shape the grid draws: a tint of the group's colour with a solid
     * rail down the left. {@code derive} rather than a translucent fill, because
     * the panel sits over the sidebar and a translucent tint would pick up
     * whatever happened to be behind it - including the band above.
     *
     * <p>The tint is on this panel and not on the rows, so a row's own hover and
     * selection colours still paint over it. An inline background on the rows
     * would have won against the stylesheet and left a selected row in a group
     * with no visible selection at all.
     */
    private HBox bandFor(ProfileLayout.Group group, VBox content) {
        Region rail = new Region();
        rail.getStyleClass().add("profile-band-rail");
        rail.setStyle("-fx-background-color: " + group.color() + ";");
        rail.setMaxHeight(Double.MAX_VALUE);

        HBox band = new HBox(0, rail, content);
        band.getStyleClass().add("profile-band");
        String base = "-fx-background-color: derive(" + group.color() + ", -74%);"
                + " -fx-border-color: derive(" + group.color() + ", -45%);";
        band.setStyle(base);
        band.getProperties().put("hexadron-base-style", base);
        band.getProperties().put("hexadron-group", group.id());
        HBox.setHgrow(content, Priority.ALWAYS);
        return band;
    }

    /**
     * A group's heading.
     *
     * <p>No colour chip on it: the band's own rail and tint already carry the
     * colour, and a third mark of the same colour on the same line was one thing
     * too many. Everything left is white at one opacity or another over the group
     * tint, so the only colour in the block is the group's.
     *
     * <p>The fold control appears only when there is something to fold. A group
     * with no members reads identically folded and unfolded here, so a {@code -}
     * that visibly did nothing was a control reporting itself broken; the grid
     * keeps its own, because there a band has rows to close over even when it is
     * empty.
     */
    private Node groupRow(ProfileLayout.Group group, int memberCount, Region band) {
        Label toggle = new Label();
        if (memberCount == 0) {
            toggle.setText("·");
            toggle.getStyleClass().addAll("group-toggle", "group-toggle-idle");
            Tooltip.install(toggle, new Tooltip(I18n.t("groups.empty.hint")));
        } else {
            toggle.setText(group.collapsed() ? "+" : "−");
            toggle.getStyleClass().add("group-toggle");
            toggle.setOnMouseClicked(event -> {
                host.layout().setCollapsed(group.id(), !group.collapsed());
                host.layoutChanged();
                event.consume();
            });
        }

        Label name = new Label(group.name());
        name.getStyleClass().add("group-name");
        Label count = new Label(I18n.t("groups.count", memberCount));
        count.getStyleClass().add("group-count");

        HBox row = new HBox(8, toggle, name, spacer(), count);
        row.setAlignment(Pos.CENTER_LEFT);
        // Transparent, not the usual panel colour: the header sits on its own
        // band now, and a second background over the tint would hide it.
        row.getStyleClass().addAll("group-row", "group-row-banded");
        Tooltip.install(row, new Tooltip(group.name()));

        row.setOnMouseClicked(event -> {
            if (memberCount > 0 && event.getButton() == MouseButton.PRIMARY
                    && event.getClickCount() == 2) {
                host.layout().setCollapsed(group.id(), !group.collapsed());
                host.layoutChanged();
            }
        });
        ProfileMenu.installForGroup(row, host, group, false);

        dragSource(row, ProfileDrag.group(group.id()));
        dropTarget(row, band, (payload, after) -> {
            ProfileLayout layout = host.layout();
            String dragged = ProfileDrag.id(payload);
            if (!ProfileDrag.isProfile(payload)) {
                if (dragged.equals(group.id())) {
                    return false;
                }
                // By row rather than by member, so that a group with nothing in
                // it is still somewhere another group can be dropped beside.
                List<Integer> theirs = layout.rowsOf(group.id());
                if (theirs.isEmpty()) {
                    return false;
                }
                return layout.moveBandBeside(dragged,
                        after ? theirs.get(theirs.size() - 1) : theirs.get(0), after);
            }
            if (!layout.join(dragged, group.id())) {
                host.hint(I18n.t("grid.noRoom"));
                return false;
            }
            List<String> siblings = new ArrayList<>(layout.membersOf(group.id()));
            siblings.remove(dragged);
            if (!siblings.isEmpty()) {
                // The half of the header the pointer is over decides first or
                // last, which is the only reading that lets a profile be put at
                // the top of a group.
                layout.moveProfileBeside(dragged,
                        after ? siblings.get(siblings.size() - 1) : siblings.get(0), after);
            }
            return true;
        });
        return row;
    }

    /** The strip below the last row: a drop here means last, and in no group. */
    private Node tailTarget() {
        Region tail = new Region();
        tail.getStyleClass().add("profile-tail");
        tail.setMinHeight(28);
        VBox.setVgrow(tail, Priority.ALWAYS);

        tail.setOnDragOver(event -> {
            if (ProfileDrag.key(event.getDragboard()) != null) {
                event.acceptTransferModes(TransferMode.MOVE);
                tail.getStyleClass().add("drop-into");
            }
            event.consume();
        });
        tail.setOnDragExited(event -> tail.getStyleClass().remove("drop-into"));
        tail.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            tail.getStyleClass().remove("drop-into");
            if (payload == null) {
                event.consume();
                return;
            }
            ProfileLayout layout = host.layout();
            List<String> order = layout.sequence();
            if (order.isEmpty()) {
                event.consume();
                return;
            }
            String dragged = ProfileDrag.id(payload);
            if (ProfileDrag.isProfile(payload)) {
                // The anchor has to be an ungrouped profile: anchoring on the
                // last profile overall would put this one back into whatever
                // group that one happens to be in, which is the opposite of what
                // dropping past the end of the list means.
                String anchor = null;
                for (String id : order) {
                    if (layout.groupOf(id).isEmpty()) {
                        anchor = id;
                    }
                }
                if (!layout.join(dragged, null)) {
                    host.hint(I18n.t("grid.noRoom"));
                    event.consume();
                    return;
                }
                if (anchor != null && !anchor.equals(dragged)) {
                    layout.moveProfileBeside(dragged, anchor, true);
                }
            } else {
                layout.moveGroupBeside(dragged, order.get(order.size() - 1), true);
            }
            event.setDropCompleted(true);
            host.layoutChanged();
            event.consume();
        });
        return tail;
    }

    // ---------------------------------------------------------------- dragging

    /** What a drop does. Returns false when the gesture turned out to be a no-op. */
    @FunctionalInterface
    private interface DropAction {
        boolean apply(String payload, boolean after);
    }

    private void dragSource(Region row, String payload) {
        row.setOnDragDetected(event -> {
            Dragboard board = row.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(payload);
            board.setContent(content);
            // The row itself as the drag image, so what is moving is visible
            // during the move rather than only at the end of it.
            board.setDragView(row.snapshot(null, null), event.getX(), event.getY());
            row.getProperties().put("hexadron-key", payload);
            row.getStyleClass().add("drag-source");
            event.consume();
        });
        row.setOnDragDone(event -> {
            row.getStyleClass().remove("drag-source");
            clearMark();
            event.consume();
        });
    }

    /**
     * A row as a place to drop something.
     *
     * <p>What gets the drop line depends on what is being dragged, and that is
     * the whole point of {@code band}. A profile lands between two rows, so the
     * line goes on the row. A group cannot land inside another group - groups do
     * not nest - so when one is dragged over a row that is inside a band, the
     * line goes above or below the whole band. It used to go on the row, which
     * drew an insertion line between two members of somebody else's group and
     * promised a nesting that was never going to happen: the layout stepped over
     * the whole target group, so the group landed somewhere the user had not been
     * shown.
     *
     * @param band the band this row is inside, or null for a row in no group
     */
    private void dropTarget(Region row, Region band, DropAction action) {
        row.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (payload == null || payload.equals(row.getProperties().get("hexadron-key"))) {
                // The row being dragged is not a place to drop it.
                event.consume();
                return;
            }
            boolean movingGroup = ProfileDrag.isGroup(payload);
            if (movingGroup && band != null
                    && ProfileDrag.id(payload).equals(band.getProperties().get("hexadron-group"))) {
                // Its own band. A group cannot be moved inside itself either.
                event.consume();
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            if (movingGroup && band != null) {
                markBand(band, afterBand(event, band));
            } else {
                mark(row, after(event, row));
            }
            event.consume();
        });
        row.setOnDragExited(event -> {
            if (marked == row || markedBand == band) {
                clearMark();
            }
            event.consume();
        });
        row.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            boolean movingGroup = ProfileDrag.isGroup(payload);
            boolean after = movingGroup && band != null
                    ? afterBand(event, band)
                    : after(event, row);
            clearMark();
            if (payload != null && action.apply(payload, after)) {
                event.setDropCompleted(true);
                host.layoutChanged();
            }
            event.consume();
        });
    }

    private static boolean after(DragEvent event, Region row) {
        return event.getY() > row.getHeight() / 2;
    }

    /**
     * Which side of a whole band the pointer is on.
     *
     * <p>In scene coordinates, because the event arrives on a row inside the band
     * and its own y tells us nothing about where the band's middle is - pointing
     * at the top half of the last member is still the bottom half of the band.
     */
    private static boolean afterBand(DragEvent event, Region band) {
        var bounds = band.localToScene(band.getBoundsInLocal());
        return event.getSceneY() > bounds.getMinY() + bounds.getHeight() / 2;
    }

    private void mark(Region row, boolean below) {
        clearMark();
        row.getStyleClass().add(below ? "drop-below" : "drop-above");
        marked = row;
    }

    /**
     * The accent line saying which side of this band the group will land on.
     *
     * <p>Written into the band's inline style rather than added as a style class,
     * because the band already carries an inline border colour for its tint - and
     * in JavaFX an inline style beats the stylesheet, so a class setting the
     * accent border would never have shown.
     */
    private void markBand(Region band, boolean below) {
        clearMark();
        Object base = band.getProperties().get("hexadron-base-style");
        markedBandStyle = base == null ? "" : base.toString();
        markedBand = band;
        band.setStyle(markedBandStyle
                + " -fx-border-color: " + (below
                        ? "transparent transparent -fx-accent-1 transparent;"
                        : "-fx-accent-1 transparent transparent transparent;")
                + " -fx-border-width: " + (below ? "0 0 3 0;" : "3 0 0 0;"));
    }

    private void clearMark() {
        if (marked != null) {
            marked.getStyleClass().removeAll("drop-above", "drop-below", "drop-into");
            marked = null;
        }
        if (markedBand != null) {
            markedBand.setStyle(markedBandStyle);
            markedBand = null;
            markedBandStyle = null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private int visibleMembers(ProfileLayout.Group group) {
        int count = 0;
        for (String id : host.layout().membersOf(group.id())) {
            Profile profile = profile(id);
            if (profile != null && host.matchesFilter(profile)) {
                count++;
            }
        }
        return count;
    }

    private Profile profile(String id) {
        for (Profile profile : host.profiles()) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return null;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
