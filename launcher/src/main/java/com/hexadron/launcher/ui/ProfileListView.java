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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of instances: rows, groups, and drag to rearrange.
 *
 * <h2>Rows rather than a ListView</h2>
 *
 * <p>A {@code ListView} recycles its cells, and a recycled cell is the wrong
 * thing to hang a drag gesture and a drop indicator on: the cell under the
 * pointer at the end of a drag is not necessarily the cell the drag started
 * from, and the highlight ends up on whatever row the cell was reused for. The
 * list here is tens of rows, not thousands, so it is built as plain rows in a
 * {@link VBox} and drawn in full. Every row is its own node for as long as it is
 * on screen, which is what makes the drop line land where the pointer is.
 *
 * <p>A {@code TreeView} would give collapsing for free and take the same problem
 * back on, with the added cost that its disclosure arrow is not the {@code -}
 * and {@code +} this interface is specified to show.
 *
 * <h2>No state of its own</h2>
 *
 * <p>Everything drawn comes from {@link ProfileHost}: the profiles, the
 * arrangement, the selection, the search. Every drag ends in a call to
 * {@link ProfileLayout} through the host and then {@link ProfileHost#layoutChanged()},
 * which saves and rebuilds. That is why the grid is already correct when it is
 * switched to - it is reading the same arrangement this view just changed, not a
 * copy of it.
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
     * <p>Needed because selecting must not rebuild the list. Selection happens
     * on mouse-pressed, which is also the press a drag starts from - so
     * rebuilding there would replace the very node the drag was about to begin
     * on, and the drag would never start. So the highlight is moved by changing
     * a style class on rows that already exist.
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

        ProfileLayout layout = host.layout();
        boolean anything = false;

        for (ProfileLayout.Entry entry : layout.entries()) {
            if (entry.isGroup()) {
                ProfileLayout.Group group = entry.group();
                List<Profile> members = visible(group.members());
                // A group with nothing matching the search is hidden with its
                // members, rather than left as an empty heading the search
                // cannot explain.
                if (members.isEmpty() && !host.filter().isEmpty()) {
                    continue;
                }
                anything = true;
                rows.getChildren().add(groupRow(group, members.size()));
                if (!group.collapsed()) {
                    for (Profile profile : members) {
                        rows.getChildren().add(profileRow(profile, true));
                    }
                }
            } else {
                Profile profile = profile(entry.profileId());
                if (profile == null || !host.matchesFilter(profile)) {
                    continue;
                }
                anything = true;
                rows.getChildren().add(profileRow(profile, false));
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

        // The tail. Somewhere has to mean "past the last row, at the top level",
        // or a profile dragged out of the last group has nowhere to land.
        rows.getChildren().add(tailTarget());
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
        dropTarget(row, payload -> {
            if (ProfileDrag.isProfile(payload)) {
                return dropped -> host.layout().moveProfileBeside(
                        dropped.id(), profile.id(), dropped.after());
            }
            // A group dropped onto a profile row goes beside whatever top-level
            // entry that row belongs to: the group it is in, or the row itself.
            return dropped -> {
                String target = host.layout().groupOf(profile.id())
                        .map(ProfileLayout.Group::id).orElse(profile.id());
                host.layout().moveEntryBeside(dropped.id(), target, dropped.after());
            };
        });
        return row;
    }

    private Node groupRow(ProfileLayout.Group group, int shown) {
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
        Label count = new Label(I18n.t("groups.count", shown));
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
        dropTarget(row, payload -> {
            if (ProfileDrag.isProfile(payload)) {
                // Into the group. The half of the header the pointer is over
                // decides first or last, which is the only reading of the
                // gesture that lets a profile be put at the top of a group.
                return dropped -> host.layout().moveProfile(dropped.id(), group.id(),
                        dropped.after() ? Integer.MAX_VALUE : 0);
            }
            return dropped -> host.layout().moveEntryBeside(
                    dropped.id(), group.id(), dropped.after());
        });
        return row;
    }

    /** The strip below the last row: a drop here means the end of the top level. */
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
                return;
            }
            if (ProfileDrag.isProfile(payload)) {
                host.layout().moveProfileToEnd(ProfileDrag.id(payload), null);
            } else {
                List<ProfileLayout.Entry> entries = host.layout().entries();
                if (!entries.isEmpty()) {
                    ProfileLayout.Entry last = entries.get(entries.size() - 1);
                    String target = last.isGroup() ? last.group().id() : last.profileId();
                    host.layout().moveEntryBeside(ProfileDrag.id(payload), target, true);
                }
            }
            event.setDropCompleted(true);
            host.layoutChanged();
            event.consume();
        });
        return tail;
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

    // ---------------------------------------------------------------- dragging

    /** What a drop worked out: which id was dragged, and whether it goes after. */
    private record Dropped(String id, boolean after) {
    }

    @FunctionalInterface
    private interface DropAction {
        void apply(Dropped dropped);
    }

    /** Chooses what a drop does, from the kind of thing on the dragboard. */
    @FunctionalInterface
    private interface DropPlan {
        DropAction forPayload(String payload);
    }

    private void dragSource(Region row, String payload) {
        row.setOnDragDetected(event -> {
            Dragboard board = row.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(payload);
            board.setContent(content);
            row.getProperties().put("hexadron-key", payload);
            // The row itself as the drag image, so what is being moved is
            // visible during the move rather than only at the end of it.
            board.setDragView(row.snapshot(null, null), event.getX(), event.getY());
            row.getStyleClass().add("drag-source");
            event.consume();
        });
        row.setOnDragDone(event -> {
            row.getStyleClass().remove("drag-source");
            clearMark();
            event.consume();
        });
    }

    private void dropTarget(Region row, DropPlan plan) {
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
            clearMark();
            if (payload == null) {
                event.consume();
                return;
            }
            DropAction action = plan.forPayload(payload);
            if (action != null) {
                action.apply(new Dropped(ProfileDrag.id(payload), after(event, row)));
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

    private List<Profile> visible(List<String> ids) {
        List<Profile> found = new java.util.ArrayList<>();
        for (String id : ids) {
            Profile profile = profile(id);
            if (profile != null && host.matchesFilter(profile)) {
                found.add(profile);
            }
        }
        return found;
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
