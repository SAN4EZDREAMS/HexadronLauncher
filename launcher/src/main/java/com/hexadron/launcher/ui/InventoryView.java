package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The instances as a player's inventory: one profile per cell, and it stays there.
 *
 * <h2>Absolute cells</h2>
 *
 * <p>The grid is a fixed field of {@link ProfileLayout#rows()} by
 * {@link ProfileLayout#columns()} cells and every profile has one. A drop on a
 * free cell puts the profile in that cell; a drop on an occupied one exchanges
 * the two. Nothing else moves, ever - which is the difference from the first
 * version, where a free cell meant "the end of the list" and dragging a profile
 * onto the empty cells at the end of the top row sent it to the bottom of the
 * grid.
 *
 * <p>Empty cells are therefore real places, not padding, and the list simply
 * skips them: a hole in the grid is nothing in the list.
 *
 * <h2>Growing and shrinking it</h2>
 *
 * <p>The grid never reflows by itself, so its size is something the user sets.
 * The controls for it are on the edges where the change happens - a strip to the
 * right of the last column and one under the last row, each with a {@code +} and
 * a {@code -}, faint until the pointer is in the grid. The same two numbers are
 * in the settings window for anybody who would rather type them.
 *
 * <p>Removing an edge that still has profiles behind it moves them to free cells
 * and keeps them; if there are not enough free cells it does nothing and says
 * so, rather than dropping a profile off the end.
 *
 * <h2>Groups</h2>
 *
 * <p>A group is a colour here, not a place: its members' cells are outlined in
 * it, and a chip on the left rail names it on hover and lights those cells up.
 * Dropping a profile on a chip moves it into that group without moving the
 * profile. Collapsing is a list behaviour and has no meaning here - a cell has a
 * fixed place, so there is nothing for a fold to close over.
 */
public final class InventoryView {

    /** Outer size of a cell, in pixels. */
    private static final double CELL = 88;

    /** The icon inside a cell. */
    private static final double ICON = 44;

    /** Width of the group rail on the left, and of the edge strips. */
    private static final double EDGE = 24;

    private final ProfileHost host;
    private final GridPane frame = new GridPane();
    private final GridPane grid = new GridPane();
    private final VBox rail = new VBox(4);
    private final ScrollPane scroll = new ScrollPane();
    private final Label empty = new Label();

    private Region marked;

    /**
     * The cell of each profile on screen, for moving the selection highlight.
     *
     * <p>Needed because selecting must not rebuild the grid: selection happens on
     * mouse-pressed, which is the press a drag starts from, and rebuilding there
     * would replace the very cell the drag was about to begin on.
     */
    private final Map<String, Region> cellsByProfile = new HashMap<>();

    /** The cells of each group, so hovering its chip can light them up. */
    private final Map<String, List<Region>> cellsByGroup = new HashMap<>();

    public InventoryView(ProfileHost host) {
        this.host = host;

        grid.getStyleClass().add("inv-grid");
        rail.getStyleClass().add("inv-rail-column");
        rail.setMinWidth(EDGE);
        rail.setPrefWidth(EDGE);
        rail.setMaxWidth(EDGE);
        rail.setAlignment(Pos.TOP_CENTER);

        frame.getStyleClass().add("inv-frame");
        frame.add(rail, 0, 0);
        frame.add(grid, 1, 0);
        frame.add(columnEdge(), 2, 0);
        frame.add(rowEdge(), 1, 1);

        StackPane centred = new StackPane(frame);
        centred.setAlignment(Pos.TOP_CENTER);
        centred.setPadding(new Insets(14, 14, 24, 14));

        scroll.setContent(centred);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("inv-scroll");

        empty.getStyleClass().add("muted");
        empty.setWrapText(true);
    }

    public Region node() {
        return scroll;
    }

    // ---------------------------------------------------------------- building

    /** Rebuilds the whole grid from the arrangement. */
    public void rebuild() {
        marked = null;
        cellsByProfile.clear();
        cellsByGroup.clear();
        grid.getChildren().clear();
        rail.getChildren().clear();

        ProfileLayout layout = host.layout();
        Set<String> visible = host.filter().isEmpty() ? null : matching();

        for (int row = 0; row < layout.rows(); row++) {
            for (int column = 0; column < layout.columns(); column++) {
                Optional<String> id = layout.at(row, column);
                Profile profile = id.map(this::profile).orElse(null);
                boolean shown = profile != null
                        && (visible == null || visible.contains(profile.id()));
                grid.add(shown ? cell(profile) : emptyCell(row, column), column, row);
            }
        }

        for (ProfileLayout.Group group : layout.groups()) {
            rail.getChildren().add(chip(group));
        }

        if (layout.occupied() == 0) {
            empty.setText(I18n.t("instance.none.body"));
            grid.add(empty, 0, layout.rows());
            GridPane.setColumnSpan(empty, Math.max(1, layout.columns()));
        }
    }

    /** Moves the highlight without rebuilding the grid. */
    public void applySelection() {
        Profile selected = host.selected();
        String id = selected == null ? null : selected.id();
        for (Map.Entry<String, Region> entry : cellsByProfile.entrySet()) {
            boolean on = entry.getKey().equals(id);
            var classes = entry.getValue().getStyleClass();
            if (on && !classes.contains("inv-cell-selected")) {
                classes.add("inv-cell-selected");
            } else if (!on) {
                classes.remove("inv-cell-selected");
            }
        }
    }

    // ---------------------------------------------------------------- cells

    private Node cell(Profile profile) {
        Node icon = ProfileIcons.node(profile, host.service().dirs(), ICON);

        Label name = new Label(profile.name());
        name.getStyleClass().add("inv-name");
        name.setWrapText(true);
        name.setTextAlignment(TextAlignment.CENTER);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(CELL - 12);
        // Two lines. A third would push the icon out of the cell, and a name
        // that long is unreadable here anyway - the tooltip carries all of it.
        name.setMaxHeight(28);

        VBox content = new VBox(3, icon, name);
        content.setAlignment(Pos.CENTER);

        StackPane cell = new StackPane(content);
        cell.getStyleClass().add("inv-cell");
        fix(cell);
        if (profile.equals(host.selected())) {
            cell.getStyleClass().add("inv-cell-selected");
        }
        cellsByProfile.put(profile.id(), cell);

        Optional<ProfileLayout.Group> group = host.layout().groupOf(profile.id());
        String tip = profile.name() + "\n"
                + profile.minecraftVersion() + "  ·  " + profile.loader().displayName();
        if (group.isPresent()) {
            // The group's colour on the cell's own border: in a grid of fixed
            // places, a colour is the only thing that can say "these belong
            // together" without moving anything.
            cell.getStyleClass().add("inv-cell-grouped");
            cell.setStyle("-fx-border-color: " + group.get().color() + ";");
            cellsByGroup.computeIfAbsent(group.get().id(), key -> new ArrayList<>()).add(cell);
            tip = tip + "\n" + group.get().name();
        }

        Tooltip tooltip = new Tooltip(tip);
        tooltip.setShowDelay(Duration.millis(400));
        Tooltip.install(cell, tooltip);

        cell.setOnMousePressed(event -> {
            host.select(profile);
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                host.play(profile);
            }
        });
        ProfileMenu.install(cell, host, profile);

        cell.setOnDragDetected(event -> {
            Dragboard board = cell.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent payload = new ClipboardContent();
            payload.putString(ProfileDrag.profile(profile.id()));
            board.setContent(payload);
            board.setDragView(cell.snapshot(null, null), event.getX(), event.getY());
            cell.getStyleClass().add("drag-source");
            event.consume();
        });
        cell.setOnDragDone(event -> {
            cell.getStyleClass().remove("drag-source");
            clearMark();
            event.consume();
        });

        // An occupied cell accepts a drop as an exchange. No before-or-after:
        // the two profiles swap places, so which half of the cell the pointer is
        // over does not change the outcome and must not pretend to.
        cell.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)
                    || profile.id().equals(ProfileDrag.id(payload))) {
                event.consume();
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            mark(cell, "drop-swap");
            event.consume();
        });
        cell.setOnDragExited(event -> {
            if (marked == cell) {
                clearMark();
            }
            event.consume();
        });
        cell.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            clearMark();
            if (ProfileDrag.isProfile(payload)) {
                host.layout().cellOf(profile.id()).ifPresent(place ->
                        host.layout().placeAt(ProfileDrag.id(payload), place[0], place[1]));
                event.setDropCompleted(true);
                host.layoutChanged();
            }
            event.consume();
        });
        return cell;
    }

    /**
     * A free cell.
     *
     * <p>A place, not padding. Dropping here puts the profile in this exact cell
     * and leaves it there, which is the behaviour the grid is for; the list will
     * simply not show the hole it came from.
     */
    private Node emptyCell(int row, int column) {
        StackPane cell = new StackPane();
        cell.getStyleClass().addAll("inv-cell", "inv-cell-empty");
        fix(cell);

        cell.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (ProfileDrag.isProfile(payload)) {
                event.acceptTransferModes(TransferMode.MOVE);
                mark(cell, "drop-into");
            }
            event.consume();
        });
        cell.setOnDragExited(event -> {
            if (marked == cell) {
                clearMark();
            }
            event.consume();
        });
        cell.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            clearMark();
            if (ProfileDrag.isProfile(payload)) {
                host.layout().placeAt(ProfileDrag.id(payload), row, column);
                event.setDropCompleted(true);
                host.layoutChanged();
            }
            event.consume();
        });
        return cell;
    }

    // ---------------------------------------------------------------- the rail

    /**
     * One group's chip.
     *
     * <p>A legend rather than a heading: it names the group on hover, lights its
     * cells so the group can be seen at a glance, and takes a drop so a profile
     * can join without being moved. The cells it lights are wherever they are -
     * membership here is not a position.
     */
    private Node chip(ProfileLayout.Group group) {
        Region mark = new Region();
        mark.getStyleClass().add("inv-chip");
        mark.setStyle("-fx-background-color: " + group.color() + ";");
        mark.setMinSize(14, 22);
        mark.setPrefSize(14, 22);
        mark.setMaxSize(14, 22);

        int members = host.layout().membersOf(group.id()).size();
        Tooltip tooltip = new Tooltip(group.name() + "  ·  " + I18n.t("groups.count", members));
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(mark, tooltip);

        mark.setOnMouseEntered(event -> highlight(group.id(), true));
        mark.setOnMouseExited(event -> highlight(group.id(), false));
        ProfileMenu.installForGroup(mark, host, group);

        mark.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (ProfileDrag.isProfile(payload)) {
                event.acceptTransferModes(TransferMode.MOVE);
                mark.getStyleClass().add("drop-into");
            }
            event.consume();
        });
        mark.setOnDragExited(event -> mark.getStyleClass().remove("drop-into"));
        mark.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            mark.getStyleClass().remove("drop-into");
            if (ProfileDrag.isProfile(payload)) {
                host.layout().join(ProfileDrag.id(payload), group.id());
                event.setDropCompleted(true);
                host.layoutChanged();
            }
            event.consume();
        });
        return mark;
    }

    private void highlight(String groupId, boolean on) {
        for (Region cell : cellsByGroup.getOrDefault(groupId, List.of())) {
            if (on && !cell.getStyleClass().contains("inv-cell-highlight")) {
                cell.getStyleClass().add("inv-cell-highlight");
            } else if (!on) {
                cell.getStyleClass().remove("inv-cell-highlight");
            }
        }
    }

    // ---------------------------------------------------------------- the edges

    /**
     * The strip to the right of the last column.
     *
     * <p>On the edge it changes, because that is where the pointer already is
     * when somebody wants another column, and because a control that sits where
     * its effect appears needs no label to explain it. Faint until the pointer is
     * in the grid, so it is not four buttons competing with the instances.
     */
    private Node columnEdge() {
        VBox edge = new VBox(6, edgeButton("+", "grid.addColumn", () -> {
            if (!host.layout().addColumn()) {
                host.hint(I18n.t("grid.atMaximum"));
                return;
            }
            host.layoutChanged();
        }), edgeButton("−", "grid.removeColumn", () -> {
            if (!host.layout().removeColumn()) {
                host.hint(I18n.t("grid.noRoom"));
                return;
            }
            host.layoutChanged();
        }));
        edge.getStyleClass().add("inv-edge");
        edge.setAlignment(Pos.CENTER);
        edge.setMinWidth(EDGE);
        edge.setPrefWidth(EDGE);
        edge.setMaxWidth(EDGE);
        return edge;
    }

    /** The strip under the last row. */
    private Node rowEdge() {
        HBox edge = new HBox(6, edgeButton("+", "grid.addRow", () -> {
            if (!host.layout().addRow()) {
                host.hint(I18n.t("grid.atMaximum"));
                return;
            }
            host.layoutChanged();
        }), edgeButton("−", "grid.removeRow", () -> {
            if (!host.layout().removeRow()) {
                host.hint(I18n.t("grid.noRoom"));
                return;
            }
            host.layoutChanged();
        }));
        edge.getStyleClass().add("inv-edge");
        edge.setAlignment(Pos.CENTER);
        edge.setMinHeight(EDGE);
        edge.setPrefHeight(EDGE);
        edge.setMaxHeight(EDGE);
        return edge;
    }

    private Node edgeButton(String glyph, String tooltipKey, Runnable action) {
        Label button = new Label(glyph);
        button.getStyleClass().add("inv-edge-button");
        button.setMinSize(18, 18);
        button.setPrefSize(18, 18);
        button.setAlignment(Pos.CENTER);
        Tooltip tooltip = new Tooltip(I18n.t(tooltipKey));
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(button, tooltip);
        button.setOnMouseClicked(event -> {
            action.run();
            event.consume();
        });
        return button;
    }

    // ---------------------------------------------------------------- helpers

    private static void fix(Region region) {
        region.setMinSize(CELL, CELL);
        region.setPrefSize(CELL, CELL);
        region.setMaxSize(CELL, CELL);
    }

    private void mark(Region node, String styleClass) {
        clearMark();
        node.getStyleClass().add(styleClass);
        marked = node;
    }

    private void clearMark() {
        if (marked != null) {
            marked.getStyleClass().removeAll("drop-into", "drop-swap");
            marked = null;
        }
    }

    private Set<String> matching() {
        Set<String> ids = new HashSet<>();
        for (Profile profile : host.profiles()) {
            if (host.matchesFilter(profile)) {
                ids.add(profile.id());
            }
        }
        return ids;
    }

    private Profile profile(String id) {
        for (Profile profile : host.profiles()) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return null;
    }
}
