package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
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
 * <p>The plate is also the handle for the band. Dragging it moves the whole
 * group above or below another band, and clicking it folds the band to a single
 * strip that still carries the name.
 *
 * <h2>Growing and shrinking it</h2>
 *
 * <p>The grid never reflows, so its size is something the user sets. The two
 * strips are outside the table on the axis each one changes - columns above it,
 * at the right where a column appears, rows below it - and faint until the
 * pointer is in the grid. They used to be a column down the right-hand side,
 * level with whichever band happened to be beside them, which read as belonging
 * to that group.
 *
 * <p>Removing an edge moves the profiles behind it into free cells of their own
 * group and keeps them; when there is no room it does nothing and says so. The
 * row strip only ever takes a row that is empty and in no group: a group's rows
 * are added and removed from the group's own two buttons, at the right end of
 * its band and in its own colour.
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
    private final HBox columnStrip = new HBox(6);
    private final HBox rowStrip = new HBox(6);
    private final ScrollPane scroll = new ScrollPane();
    private final Label empty = new Label();

    private Region marked;

    /** The band showing a drop line, and the inline style to put back on it. */
    private HBox markedBand;
    private String markedBandStyle;

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

        buildColumnStrip();
        buildRowStrip();

        VBox frame = new VBox(0, columnStrip, bands, rowStrip);
        frame.getStyleClass().add("inv-frame");
        frame.setFillWidth(false);
        frame.setAlignment(Pos.TOP_LEFT);

        StackPane centred = new StackPane(frame);
        centred.setAlignment(Pos.TOP_CENTER);
        centred.setPadding(new Insets(6, 14, 24, 14));

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
        clearMark();
        cellsByProfile.clear();
        bands.getChildren().clear();

        ProfileLayout layout = host.layout();
        Set<String> visible = host.filter().isEmpty() ? null : matching();

        for (ProfileLayout.Band band : layout.bands()) {
            bands.getChildren().add(band(band, visible));
        }

        // Both strips are as wide as a band, so the column one can sit over the
        // right-hand edge it changes and the row one under the left.
        double width = PLATE + CELL * layout.columns() + EDGE;
        columnStrip.setMinWidth(width);
        columnStrip.setPrefWidth(width);
        columnStrip.setMaxWidth(width);
        rowStrip.setMinWidth(width);
        rowStrip.setPrefWidth(width);
        rowStrip.setMaxWidth(width);

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
                lines.getChildren().add(cells(index, band.group(), visible));
            }
            row.getChildren().add(lines);
        }
        row.getChildren().add(groupEdge(band, height));

        String base = "";
        if (band.group() != null) {
            // derive() rather than an alpha: the band sits over the window
            // background, and a translucent tint would pick up whatever happened
            // to be behind it - including the band above.
            row.getStyleClass().add("inv-band-group");
            base = "-fx-background-color: derive(" + band.group().color() + ", -74%);"
                    + " -fx-border-color: derive(" + band.group().color() + ", -40%);";
            row.setStyle(base);
        }
        row.getProperties().put("hexadron-base-style", base);

        bandDropTarget(row, band);
        return row;
    }

    /**
     * A band as a place to drop a group.
     *
     * <p>The indicator is written into the band's inline style rather than added
     * as a style class, because the band already carries an inline border colour
     * for its group tint - and in JavaFX an inline style beats the stylesheet, so
     * a class setting the accent border would never have shown.
     */
    private void bandDropTarget(HBox row, ProfileLayout.Band band) {
        row.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isGroup(payload) || ownsBand(band, ProfileDrag.id(payload))) {
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            markBand(row, event.getY() > row.getHeight() / 2);
            event.consume();
        });
        row.setOnDragExited(event -> {
            if (markedBand == row) {
                clearMark();
            }
        });
        row.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            boolean after = event.getY() > row.getHeight() / 2;
            clearMark();
            if (!ProfileDrag.isGroup(payload) || band.rows().isEmpty()) {
                return;
            }
            if (host.layout().moveBandBeside(ProfileDrag.id(payload),
                    band.rows().get(after ? band.rows().size() - 1 : 0), after)) {
                event.setDropCompleted(true);
                host.layoutChanged();
            }
            event.consume();
        });
    }

    private static boolean ownsBand(ProfileLayout.Band band, String groupId) {
        return band.group() != null && band.group().id().equals(groupId);
    }

    /**
     * The plate down the left of a band.
     *
     * <p>Coloured and named for a group, and a gutter of the same width for rows
     * in none - the same width, so that grouped and ungrouped rows line up and
     * the grid still reads as one grid.
     *
     * <p>It does three things, which is what makes it the band's handle: it says
     * which group this is, a click folds the band, and a drag moves the whole
     * group past another band.
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
                + I18n.t("groups.count", band.memberCount())
                + "\n" + I18n.t("groups.plate.hint"));
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(plate, tooltip);

        plate.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                host.layout().setCollapsed(group.id(), !group.collapsed());
                host.layoutChanged();
            }
        });
        ProfileMenu.installForGroup(plate, host, group);

        plate.setOnDragDetected(event -> {
            Dragboard board = plate.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent payload = new ClipboardContent();
            payload.putString(ProfileDrag.group(group.id()));
            board.setContent(payload);
            board.setDragView(plate.snapshot(null, null), event.getX(), event.getY());
            plate.getStyleClass().add("drag-source");
            event.consume();
        });
        plate.setOnDragDone(event -> {
            plate.getStyleClass().remove("drag-source");
            clearMark();
            event.consume();
        });

        // A drop on the plate is the shortest way to say "into this group",
        // wherever there is room in it. A group dropped here is not for the plate
        // to handle - it is left to bubble up to the band.
        plate.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)) {
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            mark(plate, "drop-into");
            event.consume();
        });
        plate.setOnDragExited(event -> {
            if (marked == plate) {
                clearMark();
            }
        });
        plate.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)) {
                return;
            }
            clearMark();
            if (host.layout().join(ProfileDrag.id(payload), group.id())) {
                host.layout().setCollapsed(group.id(), false);
                event.setDropCompleted(true);
                host.layoutChanged();
            } else {
                host.hint(I18n.t("grid.noRoom"));
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

    private Node cells(int row, ProfileLayout.Group group, Set<String> visible) {
        HBox line = new HBox(0);
        line.setFillHeight(false);
        line.getStyleClass().add("inv-line");
        for (int column = 0; column < host.layout().columns(); column++) {
            Optional<String> id = host.layout().at(row, column);
            Profile profile = id.map(this::profile).orElse(null);
            boolean shown = profile != null
                    && (visible == null || visible.contains(profile.id()));
            line.getChildren().add(shown
                    ? cell(profile, group)
                    : emptyCell(row, column, group));
        }
        return line;
    }

    /**
     * Gives a cell its group's colour without taking over its background.
     *
     * <p>The colour goes into a looked-up colour on the cell, and the stylesheet
     * builds every background from that - resting, hover and selected alike. The
     * obvious thing, an inline {@code -fx-background-color} on the cell, would
     * have won against the stylesheet and left a tinted cell with no hover and no
     * visible selection at all.
     */
    private static void tint(Region cell, ProfileLayout.Group group) {
        if (group == null) {
            return;
        }
        cell.getStyleClass().add("inv-cell-tinted");
        cell.setStyle("-fx-group-tint: " + group.color() + ";");
    }

    // ---------------------------------------------------------------- cells

    private Node cell(Profile profile, ProfileLayout.Group group) {
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
        tint(cell, group);
        if (profile.equals(host.selected())) {
            cell.getStyleClass().add("inv-cell-selected");
        }
        cellsByProfile.put(profile.id(), cell);

        String tip = profile.name() + "\n"
                + profile.minecraftVersion() + "  ·  " + profile.loader().displayName();
        if (group != null) {
            tip = tip + "\n" + group.name();
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

        // An occupied cell accepts a profile as an exchange. No before-or-after:
        // the two swap places, so which half of the cell the pointer is over does
        // not change the outcome and must not pretend to. A group is left
        // unconsumed, for the band underneath to deal with.
        cell.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)) {
                return;
            }
            if (profile.id().equals(ProfileDrag.id(payload))) {
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
        });
        cell.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)) {
                return;
            }
            clearMark();
            host.layout().cellOf(profile.id()).ifPresent(place ->
                    host.layout().placeAt(ProfileDrag.id(payload), place[0], place[1]));
            event.setDropCompleted(true);
            host.layoutChanged();
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
     *
     * <p>It has a menu of its own, because the two things somebody wants at an
     * empty cell are a new instance and a group starting on that row, and neither
     * has anywhere else to be asked for.
     */
    private Node emptyCell(int row, int column, ProfileLayout.Group group) {
        StackPane cell = new StackPane();
        cell.getStyleClass().addAll("inv-cell", "inv-cell-empty");
        fix(cell);
        tint(cell, group);

        cell.setOnContextMenuRequested(event -> {
            ContextMenu menu = emptyCellMenu(row);
            menu.show(cell, event.getScreenX(), event.getScreenY());
            Theme.apply(menu.getScene());
            event.consume();
        });

        cell.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)) {
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            mark(cell, "drop-into");
            event.consume();
        });
        cell.setOnDragExited(event -> {
            if (marked == cell) {
                clearMark();
            }
        });
        cell.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)) {
                return;
            }
            clearMark();
            host.layout().placeAt(ProfileDrag.id(payload), row, column);
            event.setDropCompleted(true);
            host.layoutChanged();
            event.consume();
        });
        return cell;
    }

    private ContextMenu emptyCellMenu(int row) {
        ContextMenu menu = new ContextMenu();

        MenuItem newProfile = new MenuItem(I18n.t("profiles.new"));
        newProfile.setOnAction(event -> host.createProfile());

        MenuItem newGroup = new MenuItem(I18n.t("grid.newGroupHere"));
        newGroup.setOnAction(event -> host.createGroupInRow(row));
        // A row that already belongs to a group cannot be taken by a second one,
        // and saying so with a disabled item is clearer than an error afterwards.
        newGroup.setDisable(host.layout().rowGroup(row).isPresent());

        menu.getItems().addAll(newProfile, new SeparatorMenuItem(), newGroup);
        return menu;
    }

    // ---------------------------------------------------------------- the edges

    /**
     * The strip above the grid, at its right-hand end.
     *
     * <p>Above the table and over the edge it changes. It was a column down the
     * right-hand side, vertically centred, which put it level with whichever band
     * happened to be beside it and next to that group's own two buttons - so the
     * one pair that changes the whole table looked like it belonged to one group.
     */
    private void buildColumnStrip() {
        columnStrip.getChildren().setAll(
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
        columnStrip.getStyleClass().add("inv-edge");
        columnStrip.setAlignment(Pos.CENTER_RIGHT);
        columnStrip.setMinHeight(EDGE);
        columnStrip.setPrefHeight(EDGE);
        columnStrip.setMaxHeight(EDGE);
    }

    /**
     * The strip under the grid, at its left-hand end.
     *
     * <p>Removing a row here is a change to the table and never to the groups, so
     * it takes the last row that is empty and in no group. A row belonging to a
     * group is added and removed from that group's own buttons instead.
     */
    private void buildRowStrip() {
        rowStrip.getChildren().setAll(
                edgeButton("+", "grid.addRow", () -> {
                    if (!host.layout().addRow()) {
                        host.hint(I18n.t("grid.atMaximum"));
                        return;
                    }
                    host.layoutChanged();
                }),
                edgeButton("−", "grid.removeRow", () -> {
                    if (!host.layout().removeLastEmptyRow()) {
                        host.hint(I18n.t("grid.noEmptyRow"));
                        return;
                    }
                    host.layoutChanged();
                }));
        rowStrip.getStyleClass().add("inv-edge");
        rowStrip.setAlignment(Pos.CENTER_LEFT);
        rowStrip.setMinHeight(EDGE);
        rowStrip.setPrefHeight(EDGE);
        rowStrip.setMaxHeight(EDGE);
    }

    /**
     * A group's own two row buttons, at the right end of its band.
     *
     * <p>The table's strips change the size of the table; these change the size
     * of one group, and the two are different enough that one pair of buttons
     * cannot mean both. They are in the group's own colour so that with three
     * groups on screen there is no question which one a plus belongs to.
     *
     * <p>Every band reserves the same width, empty for rows in no group, so that
     * the table's own strips stay in one place instead of jumping left and right
     * as the bands change.
     */
    private Node groupEdge(ProfileLayout.Band band, double height) {
        VBox edge = new VBox(4);
        edge.setAlignment(Pos.CENTER);
        edge.setMinSize(EDGE, height);
        edge.setPrefSize(EDGE, height);
        edge.setMaxSize(EDGE, height);

        ProfileLayout.Group group = band.group();
        if (group == null || band.isCollapsed()) {
            // Nothing to add a row to while it is folded, and nothing at all for
            // rows that are in no group.
            edge.getStyleClass().add("inv-plate-empty");
            return edge;
        }

        edge.getStyleClass().add("inv-group-edge");
        edge.getChildren().addAll(
                groupButton("+", "groups.addRow", group, () -> {
                    if (!host.layout().addRowToGroup(group.id())) {
                        host.hint(I18n.t("grid.atMaximum"));
                        return;
                    }
                    host.layoutChanged();
                }),
                groupButton("−", "groups.removeRow", group, () -> {
                    ProfileLayout layout = host.layout();
                    boolean onlyRow = layout.rowsOf(group.id()).size() <= 1;
                    if (!layout.removeRowFromGroup(group.id())) {
                        host.hint(I18n.t(onlyRow ? "grid.lastGroupRow" : "grid.noRoomInGroup"));
                        return;
                    }
                    host.layoutChanged();
                }));
        return edge;
    }

    private Node groupButton(String glyph, String tooltipKey,
                             ProfileLayout.Group group, Runnable action) {
        Label button = new Label(glyph);
        button.getStyleClass().addAll("inv-edge-button", "inv-group-edge-button");
        button.setMinSize(18, 18);
        button.setPrefSize(18, 18);
        button.setAlignment(Pos.CENTER);
        button.setStyle("-fx-background-color: " + group.color() + ";"
                + " -fx-border-color: derive(" + group.color() + ", 25%);");
        Tooltip tooltip = new Tooltip(I18n.t(tooltipKey) + "  ·  " + group.name());
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(button, tooltip);
        button.setOnMouseClicked(event -> {
            action.run();
            event.consume();
        });
        return button;
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

    /** The accent line saying which side of this band the group will land on. */
    private void markBand(HBox row, boolean after) {
        clearMark();
        Object base = row.getProperties().get("hexadron-base-style");
        markedBandStyle = base == null ? "" : base.toString();
        markedBand = row;
        row.setStyle(markedBandStyle
                + " -fx-border-color: " + (after
                        ? "transparent transparent -fx-accent-1 transparent;"
                        : "-fx-accent-1 transparent transparent transparent;")
                + " -fx-border-width: " + (after ? "0 0 3 0;" : "3 0 0 0;"));
    }

    private void clearMark() {
        if (marked != null) {
            marked.getStyleClass().removeAll("drop-into", "drop-swap");
            marked = null;
        }
        if (markedBand != null) {
            markedBand.setStyle(markedBandStyle);
            markedBand = null;
            markedBandStyle = null;
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
