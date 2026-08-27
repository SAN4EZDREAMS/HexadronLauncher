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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.HashSet;
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
 * the two. Nothing else moves, ever. Empty cells are real places rather than
 * padding, and the list skips them - a hole in the grid is nothing in the list.
 *
 * <h2>Groups are bands</h2>
 *
 * <p>A group owns whole rows, so it is drawn as a band: a tint across its rows
 * and a coloured plate down the left with the group's name along it. Dragging a
 * profile into one of those cells is how it joins the group and dragging it out
 * is how it leaves, because the row it sits in is the only record of membership.
 *
 * <p>Clicking the plate collapses the band to a single strip that still carries
 * the name, and the list folds with it. That is what a group owning rows buys:
 * there is something to close over.
 *
 * <h2>Growing and shrinking it</h2>
 *
 * <p>The grid never reflows, so its size is something the user sets, from the
 * strips on the edges where the change appears - faint until the pointer is in
 * the grid - or from the settings window. Removing an edge moves the profiles
 * behind it into free cells and keeps them; when there are none it does nothing
 * and says so. A row that is the last one its group has will not go either: that
 * would delete a group as a side effect of resizing a table.
 */
public final class InventoryView {

    /** Outer size of a cell, in pixels. */
    private static final double CELL = 88;

    /** The icon inside a cell. */
    private static final double ICON = 44;

    /** Width of the plate down the left of every band. */
    private static final double PLATE = 20;

    /** Width and height of the strips that add and remove a column and a row. */
    private static final double EDGE = 24;

    /** Height of the strip a collapsed group is folded into. */
    private static final double STRIP = 34;

    private final ProfileHost host;
    private final VBox bands = new VBox(0);
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

    public InventoryView(ProfileHost host) {
        this.host = host;

        bands.getStyleClass().add("inv-rows");
        bands.setFillWidth(false);
        bands.setAlignment(Pos.TOP_LEFT);

        HBox withColumnEdge = new HBox(0, bands, columnEdge());
        withColumnEdge.setAlignment(Pos.TOP_LEFT);

        VBox frame = new VBox(0, withColumnEdge, rowEdge());
        frame.getStyleClass().add("inv-frame");
        frame.setFillWidth(false);
        frame.setAlignment(Pos.TOP_LEFT);

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
        bands.getChildren().clear();

        ProfileLayout layout = host.layout();
        Set<String> visible = host.filter().isEmpty() ? null : matching();

        for (ProfileLayout.Band band : layout.bands()) {
            bands.getChildren().add(band(band, visible));
        }

        if (layout.occupied() == 0) {
            empty.setText(I18n.t("instance.none.body"));
            StackPane holder = new StackPane(empty);
            holder.setPadding(new Insets(24, 8, 8, 8));
            bands.getChildren().add(holder);
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

    // ---------------------------------------------------------------- bands

    /**
     * One run of rows: the plate, and either the rows of cells or the strip a
     * collapsed group is folded into.
     */
    private Node band(ProfileLayout.Band band, Set<String> visible) {
        boolean collapsed = band.isCollapsed();
        double height = collapsed ? STRIP : band.rows().size() * CELL;

        HBox row = new HBox(0);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("inv-band");
        row.getChildren().add(plate(band, height));

        if (collapsed) {
            row.getChildren().add(strip(band));
        } else {
            VBox lines = new VBox(0);
            lines.setFillWidth(false);
            for (int index : band.rows()) {
                lines.getChildren().add(cells(index, visible));
            }
            row.getChildren().add(lines);
        }

        if (band.group() != null) {
            // derive() rather than an alpha: the band sits over the window
            // background, and a translucent tint would pick up whatever happened
            // to be behind it - including the band above.
            row.getStyleClass().add("inv-band-group");
            row.setStyle("-fx-background-color: derive(" + band.group().color() + ", -74%);"
                    + " -fx-border-color: derive(" + band.group().color() + ", -40%);");
        }
        return row;
    }

    /**
     * The plate down the left of a band.
     *
     * <p>Coloured and named for a group, and a gutter of the same width for rows
     * in none - the same width, so that grouped and ungrouped rows line up and
     * the grid still reads as one grid.
     *
     * <p>The name is written along the plate rather than above the band, because
     * a heading over a band would cost a row of height for one line of text, and
     * the plate is already there.
     */
    private Node plate(ProfileLayout.Band band, double height) {
        StackPane plate = new StackPane();
        plate.setMinSize(PLATE, height);
        plate.setPrefSize(PLATE, height);
        plate.setMaxSize(PLATE, height);

        ProfileLayout.Group group = band.group();
        if (group == null) {
            plate.getStyleClass().add("inv-plate-empty");
            return plate;
        }

        plate.getStyleClass().add("inv-plate");
        plate.setStyle("-fx-background-color: " + group.color() + ";");

        if (!band.isCollapsed()) {
            Label name = new Label(group.name());
            name.getStyleClass().add("inv-plate-name");
            name.setRotate(-90);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            name.setMaxWidth(Math.max(24, height - 10));
            // A Group wrapper, because a rotated node's layout bounds are only
            // taken from the rotation inside one - without it the label is laid
            // out at its unrotated width and the plate is asked to be 200 wide.
            plate.getChildren().add(new javafx.scene.Group(name));
        }

        Tooltip tooltip = new Tooltip(group.name() + "  ·  "
                + I18n.t("groups.count", band.memberCount()));
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(plate, tooltip);

        plate.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                host.layout().setCollapsed(group.id(), !group.collapsed());
                host.layoutChanged();
            }
        });
        ProfileMenu.installForGroup(plate, host, group);

        // A drop on the plate is the shortest way to say "into this group",
        // wherever there is room in it.
        plate.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (ProfileDrag.isProfile(payload)) {
                event.acceptTransferModes(TransferMode.MOVE);
                mark(plate, "drop-into");
            }
            event.consume();
        });
        plate.setOnDragExited(event -> {
            if (marked == plate) {
                clearMark();
            }
        });
        plate.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            clearMark();
            if (ProfileDrag.isProfile(payload)) {
                if (host.layout().join(ProfileDrag.id(payload), group.id())) {
                    host.layout().setCollapsed(group.id(), false);
                    event.setDropCompleted(true);
                    host.layoutChanged();
                } else {
                    host.hint(I18n.t("grid.noRoom"));
                }
            }
            event.consume();
        });
        return plate;
    }

    /** The single strip a collapsed group is folded into. It still says which group. */
    private Node strip(ProfileLayout.Band band) {
        ProfileLayout.Group group = band.group();

        Label plus = new Label("+");
        plus.getStyleClass().add("group-toggle");
        Label name = new Label(group.name());
        name.getStyleClass().add("group-name");
        Label count = new Label(I18n.t("groups.count", band.memberCount()));
        count.getStyleClass().add("group-count");
        Label folded = new Label(I18n.t("groups.folded", band.rows().size()));
        folded.getStyleClass().add("group-count");

        HBox strip = new HBox(10, plus, name, count, folded);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().add("inv-collapsed");
        strip.setMinHeight(STRIP);
        strip.setPrefHeight(STRIP);
        strip.setPrefWidth(CELL * host.layout().columns());

        strip.setOnMouseClicked(event -> {
            host.layout().setCollapsed(group.id(), false);
            host.layoutChanged();
        });
        ProfileMenu.installForGroup(strip, host, group);
        return strip;
    }

    private Node cells(int row, Set<String> visible) {
        HBox line = new HBox(0);
        line.setFillHeight(false);
        line.getStyleClass().add("inv-line");
        for (int column = 0; column < host.layout().columns(); column++) {
            Optional<String> id = host.layout().at(row, column);
            Profile profile = id.map(this::profile).orElse(null);
            boolean shown = profile != null
                    && (visible == null || visible.contains(profile.id()));
            line.getChildren().add(shown ? cell(profile) : emptyCell(row, column));
        }
        return line;
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

        String tip = profile.name() + "\n"
                + profile.minecraftVersion() + "  ·  " + profile.loader().displayName();
        Optional<ProfileLayout.Group> group = host.layout().groupOf(profile.id());
        if (group.isPresent()) {
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

        // An occupied cell accepts a drop as an exchange. No before-or-after: the
        // two profiles swap places, so which half of the cell the pointer is over
        // does not change the outcome and must not pretend to.
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
     * and leaves it there - and, since the row decides the group, this is also
     * how a profile joins or leaves one.
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

    // ---------------------------------------------------------------- the edges

    /**
     * The strip to the right of the last column.
     *
     * <p>On the edge it changes, because that is where the pointer already is
     * when somebody wants another column, and a control that sits where its
     * effect appears needs no label. Faint until the pointer is in the grid, so
     * it is not four buttons competing with the instances.
     */
    private Node columnEdge() {
        VBox edge = new VBox(6,
                edgeButton("+", "grid.addColumn", () -> {
                    if (!host.layout().addColumn()) {
                        host.hint(I18n.t("grid.atMaximum"));
                        return;
                    }
                    host.layoutChanged();
                }),
                edgeButton("−", "grid.removeColumn", () -> {
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
        VBox.setVgrow(edge, Priority.NEVER);
        return edge;
    }

    /**
     * The strip under the last row.
     *
     * <p>Removing a row is a change to the table and never to the groups, so the
     * last row of a group is refused with a reason rather than taking the group
     * down with it.
     */
    private Node rowEdge() {
        HBox edge = new HBox(6,
                edgeButton("+", "grid.addRow", () -> {
                    if (!host.layout().addRow()) {
                        host.hint(I18n.t("grid.atMaximum"));
                        return;
                    }
                    host.layoutChanged();
                }),
                edgeButton("−", "grid.removeRow", () -> {
                    ProfileLayout layout = host.layout();
                    int last = layout.rows() - 1;
                    boolean lastOfGroup = layout.rowGroup(last)
                            .map(group -> layout.rowsOf(group.id()).size() <= 1)
                            .orElse(false);
                    if (!layout.removeRow()) {
                        host.hint(I18n.t(lastOfGroup ? "grid.lastGroupRow" : "grid.noRoom"));
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

    /** Kept so a caller can size a container to the grid without guessing. */
    public static double gridWidth(int columns) {
        return PLATE + CELL * columns + EDGE;
    }
}
