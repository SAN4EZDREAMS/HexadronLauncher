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

    /** Rebuilds every row from the arrangement. Cheap: tens of rows, no network. */
    public void rebuild() {
        marked = null;
        rowsByProfile.clear();
        rows.getChildren().clear();

        boolean anything = false;
        List<ProfileLayout.ListRow> listRows = host.layout().listRows();

        for (int index = 0; index < listRows.size(); index++) {
            ProfileLayout.ListRow row = listRows.get(index);
            if (row.isGroup()) {
                // A group whose members are all filtered out is hidden with
                // them, rather than left as a heading the search cannot explain.
                if (!host.filter().isEmpty() && visibleMembers(row.group()) == 0) {
                    continue;
                }
                anything = true;
                rows.getChildren().add(groupRow(row.group(), row.memberCount()));
                continue;
            }
            Profile profile = profile(row.profileId());
            if (profile == null || !host.matchesFilter(profile)) {
                continue;
            }
            anything = true;
            rows.getChildren().add(profileRow(profile, row.isNested()));
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

    private Node profileRow(Profile profile, boolean inGroup) {
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
        dropTarget(row, (payload, after) -> {
            ProfileLayout layout = host.layout();
            String dragged = ProfileDrag.id(payload);
            if (ProfileDrag.isProfile(payload)) {
                // The group of the row it lands next to. Dropped among a group's
                // members it joins them; dropped among the loose rows it leaves.
                layout.join(dragged, layout.groupOf(profile.id())
                        .map(ProfileLayout.Group::id).orElse(null));
                layout.moveProfileBeside(dragged, profile.id(), after);
                return true;
            }
            // A group dragged onto a row of its own is not a move.
            if (layout.membersOf(dragged).contains(profile.id())) {
                return false;
            }
            layout.moveGroupBeside(dragged, profile.id(), after);
            return true;
        });
        return row;
    }

    private Node groupRow(ProfileLayout.Group group, int memberCount) {
        Label toggle = new Label(group.collapsed() ? "+" : "−");
        toggle.getStyleClass().add("group-toggle");
        toggle.setOnMouseClicked(event -> {
            host.layout().setCollapsed(group.id(), !group.collapsed());
            host.layoutChanged();
            event.consume();
        });

        Region chip = new Region();
        chip.getStyleClass().add("group-chip");
        chip.setStyle("-fx-background-color: " + group.color() + ";");

        Label name = new Label(group.name());
        name.getStyleClass().add("group-name");
        Label count = new Label(I18n.t("groups.count", memberCount));
        count.getStyleClass().add("group-count");

        HBox row = new HBox(8, toggle, chip, name, spacer(), count);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("group-row");
        Tooltip.install(row, new Tooltip(group.name()));

        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                host.layout().setCollapsed(group.id(), !group.collapsed());
                host.layoutChanged();
            }
        });
        ProfileMenu.installForGroup(row, host, group);

        dragSource(row, ProfileDrag.group(group.id()));
        dropTarget(row, (payload, after) -> {
            ProfileLayout layout = host.layout();
            String dragged = ProfileDrag.id(payload);
            if (!ProfileDrag.isProfile(payload)) {
                if (dragged.equals(group.id())) {
                    return false;
                }
                // Beside the group, using its first member as the anchor: the
                // header itself has no cell of its own to be beside.
                List<String> anchors = layout.membersOf(group.id());
                if (anchors.isEmpty()) {
                    return false;
                }
                layout.moveGroupBeside(dragged,
                        after ? anchors.get(anchors.size() - 1) : anchors.get(0), after);
                return true;
            }
            layout.join(dragged, group.id());
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
            String last = order.get(order.size() - 1);
            String dragged = ProfileDrag.id(payload);
            if (ProfileDrag.isProfile(payload)) {
                layout.join(dragged, null);
                layout.moveProfileBeside(dragged, last, true);
            } else {
                layout.moveGroupBeside(dragged, last, true);
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

    private void dropTarget(Region row, DropAction action) {
        row.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (payload == null || payload.equals(row.getProperties().get("hexadron-key"))) {
                // The row being dragged is not a place to drop it.
                event.consume();
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            mark(row, after(event, row));
            event.consume();
        });
        row.setOnDragExited(event -> {
            if (marked == row) {
                clearMark();
            }
            event.consume();
        });
        row.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            boolean after = after(event, row);
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

    private void mark(Region row, boolean below) {
        clearMark();
        row.getStyleClass().add(below ? "drop-below" : "drop-above");
        marked = row;
    }

    private void clearMark() {
        if (marked != null) {
            marked.getStyleClass().removeAll("drop-above", "drop-below", "drop-into");
            marked = null;
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
