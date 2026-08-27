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
import javafx.scene.input.DragEvent;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The instances as a player's inventory: one profile per cell.
 *
 * <h2>What it is</h2>
 *
 * <p>Nine cells across, as in the game's own inventory, each with a thick
 * bevelled border, holding the profile's icon with its name underneath - a
 * shortcut, in other words. Double-click launches, right-click opens the same
 * menu the list uses, and a cell can be dragged onto another to rearrange.
 *
 * <p>Nine is fixed rather than fitted to the window, and that is the point:
 * a profile's place in the grid is its place in the one shared arrangement, so
 * resizing the window must not move anything. A grid that reflowed to the width
 * would either move every icon when the window changed, or need a second
 * ordering of its own - and a second ordering is the thing this design exists to
 * avoid.
 *
 * <h2>Groups without headings</h2>
 *
 * <p>A grid has no room for a heading per group, so a group is a band: it starts
 * on a fresh row, its rows carry a tint of the group's colour, and a coloured
 * rail down the left names it on hover. Clicking the rail collapses the band to
 * a single strip; the strip says how many instances it stands for. Dropping a
 * profile on the rail moves it into that group, which is the grid's equivalent
 * of dropping it on a group header in the list.
 *
 * <p>The rows come from {@link ProfileLayout#grid}, not from anything worked out
 * here, so the row breaks and the group blocks are the same arrangement the list
 * draws as rows - the two views cannot disagree about the order because neither
 * of them decides it.
 */
public final class InventoryView {

    /** Outer size of a cell, in pixels. */
    private static final double CELL = 88;

    /** The icon inside a cell. */
    private static final double ICON = 44;

    /** Width of the coloured rail that marks a group. */
    private static final double RAIL = 14;

    private final ProfileHost host;
    private final VBox rows = new VBox(0);
    private final ScrollPane scroll = new ScrollPane();
    private final Label empty = new Label();

    private Region marked;

    /**
     * The cell of each profile on screen, for moving the selection highlight.
     *
     * <p>Same reason as in the list: a click selects, and rebuilding on a click
     * would destroy the cell the drag that click is starting is attached to.
     */
    private final Map<String, Region> cellsByProfile = new HashMap<>();

    public InventoryView(ProfileHost host) {
        this.host = host;

        rows.getStyleClass().add("inv-rows");
        rows.setFillWidth(false);
        rows.setAlignment(Pos.TOP_LEFT);

        StackPane centred = new StackPane(rows);
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

    /** Rebuilds the whole grid from the arrangement. */
    public void rebuild() {
        marked = null;
        cellsByProfile.clear();
        rows.getChildren().clear();

        Set<String> visible = host.filter().isEmpty() ? null : matching();
        List<ProfileLayout.Row> grid = host.layout().grid(visible);

        if (grid.isEmpty()) {
            empty.setText(host.filter().isEmpty()
                    ? I18n.t("instance.none.body")
                    : I18n.t("mods.noResults"));
            StackPane holder = new StackPane(empty);
            holder.setPadding(new Insets(30));
            rows.getChildren().add(holder);
            return;
        }

        ProfileLayout.Group previous = null;
        for (int index = 0; index < grid.size(); index++) {
            ProfileLayout.Row row = grid.get(index);
            ProfileLayout.Group next = index + 1 < grid.size() ? grid.get(index + 1).group() : null;
            rows.getChildren().add(band(row, row.group() != previous,
                    row.group() != next));
            previous = row.group();
        }
    }

    // ---------------------------------------------------------------- rows

    /**
     * One row of the grid, with its group rail and tint.
     *
     * @param first true when this is the first row of its group, so the band
     *              gets its top corners and the rail gets the name
     * @param last  true when it is the last, so the band closes off
     */
    private Node band(ProfileLayout.Row row, boolean first, boolean last) {
        HBox band = new HBox(0);
        band.setAlignment(Pos.CENTER_LEFT);
        band.getStyleClass().add("inv-band");
        band.getChildren().add(rail(row, first));

        if (row.isCollapsedGroup()) {
            band.getChildren().add(collapsedStrip(row));
            band.getStyleClass().add("inv-band-collapsed");
        } else {
            band.getChildren().add(cells(row));
        }

        if (row.group() != null) {
            band.getStyleClass().add("inv-band-group");
            // derive() rather than an alpha, because the band sits over the
            // window background and a translucent tint would pick up whatever
            // happened to be behind it, including another band.
            band.setStyle("-fx-background-color: derive(" + row.group().color() + ", -74%);"
                    + " -fx-border-color: derive(" + row.group().color() + ", -40%);"
                    + " -fx-border-width: " + (first ? 1 : 0) + " 1 " + (last ? 1 : 0) + " 0;");
        }
        return band;
    }

    /**
     * The left-hand marker.
     *
     * <p>Coloured and named for a group, and an empty gutter of the same width
     * for loose profiles - the same width, so that grouped and ungrouped rows
     * line up and the grid still reads as one grid.
     */
    private Node rail(ProfileLayout.Row row, boolean first) {
        Region rail = new Region();
        rail.setMinWidth(RAIL);
        rail.setPrefWidth(RAIL);
        rail.setMaxWidth(RAIL);
        rail.setMinHeight(row.isCollapsedGroup() ? 34 : CELL);

        ProfileLayout.Group group = row.group();
        if (group == null) {
            rail.getStyleClass().add("inv-rail-empty");
            return rail;
        }

        rail.getStyleClass().add("inv-rail");
        rail.setStyle("-fx-background-color: " + group.color() + ";");

        Tooltip tooltip = new Tooltip(group.name() + "  ·  "
                + I18n.t("groups.count", group.size()));
        tooltip.setShowDelay(Duration.millis(250));
        Tooltip.install(rail, tooltip);

        rail.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                host.layout().setCollapsed(group.id(), !group.collapsed());
                host.layoutChanged();
            }
        });
        ProfileMenu.installForGroup(rail, host, group);

        // Dropping on the rail is how a profile joins a group in this view.
        rail.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (ProfileDrag.isProfile(payload)) {
                event.acceptTransferModes(TransferMode.MOVE);
                mark(rail, "drop-into");
            }
            event.consume();
        });
        rail.setOnDragExited(event -> clearMark());
        rail.setOnDragDropped(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            clearMark();
            if (ProfileDrag.isProfile(payload)) {
                host.layout().moveProfileToEnd(ProfileDrag.id(payload), group.id());
                host.layout().setCollapsed(group.id(), false);
                event.setDropCompleted(true);
                host.layoutChanged();
            }
            event.consume();
        });
        if (first) {
            rail.getStyleClass().add("inv-rail-first");
        }
        return rail;
    }

    private Node cells(ProfileLayout.Row row) {
        HBox line = new HBox(0);
        line.getStyleClass().add("inv-line");
        List<String> ids = row.profileIds();
        for (int column = 0; column < ProfileLayout.GRID_COLUMNS; column++) {
            if (column < ids.size()) {
                Profile profile = profile(ids.get(column));
                line.getChildren().add(profile == null
                        ? emptyCell(row.group())
                        : cell(profile));
            } else {
                line.getChildren().add(emptyCell(row.group()));
            }
        }
        return line;
    }

    /** The single strip that stands in for a collapsed group. */
    private Node collapsedStrip(ProfileLayout.Row row) {
        ProfileLayout.Group group = row.group();
        Label plus = new Label("+");
        plus.getStyleClass().add("group-toggle");
        Label name = new Label(group.name());
        name.getStyleClass().add("group-name");
        Label count = new Label(I18n.t("groups.count", row.hiddenCount()));
        count.getStyleClass().add("group-count");

        HBox strip = new HBox(10, plus, name, count);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().add("inv-collapsed");
        strip.setMinHeight(34);
        strip.setPrefWidth(CELL * ProfileLayout.GRID_COLUMNS);
        strip.setOnMouseClicked(event -> {
            host.layout().setCollapsed(group.id(), false);
            host.layoutChanged();
        });
        ProfileMenu.installForGroup(strip, host, group);
        return strip;
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
        // long enough to need three lines is unreadable at this size anyway -
        // the tooltip below carries the whole of it.
        name.setMaxHeight(28);

        VBox content = new VBox(3, icon, name);
        content.setAlignment(Pos.CENTER);

        StackPane cell = new StackPane(content);
        cell.getStyleClass().add("inv-cell");
        cell.setMinSize(CELL, CELL);
        cell.setPrefSize(CELL, CELL);
        cell.setMaxSize(CELL, CELL);
        if (profile.equals(host.selected())) {
            cell.getStyleClass().add("inv-cell-selected");
        }
        cellsByProfile.put(profile.id(), cell);

        Tooltip tooltip = new Tooltip(profile.name() + "\n"
                + profile.minecraftVersion() + "  ·  " + profile.loader().displayName());
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
            ClipboardContent content2 = new ClipboardContent();
            content2.putString(ProfileDrag.profile(profile.id()));
            board.setContent(content2);
            board.setDragView(cell.snapshot(null, null), event.getX(), event.getY());
            cell.getStyleClass().add("drag-source");
            event.consume();
        });
        cell.setOnDragDone(event -> {
            cell.getStyleClass().remove("drag-source");
            clearMark();
            event.consume();
        });

        cell.setOnDragOver(event -> {
            String payload = ProfileDrag.key(event.getDragboard());
            if (!ProfileDrag.isProfile(payload)
                    || profile.id().equals(ProfileDrag.id(payload))) {
                event.consume();
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            mark(cell, rightHalf(event, cell) ? "drop-after" : "drop-before");
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
            boolean after = rightHalf(event, cell);
            clearMark();
            if (ProfileDrag.isProfile(payload)) {
                // Beside the cell under the pointer, which also moves the
                // dragged profile into whatever group that cell belongs to.
                host.layout().moveProfileBeside(ProfileDrag.id(payload), profile.id(), after);
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
     * <p>Not decoration: it is the drop target that means "the end of this
     * group", and for the loose rows, "the end of the top level". Without it the
     * only way to move a profile to the end would be to drop it on the last
     * occupied cell and hope the pointer was on the right half.
     */
    private Node emptyCell(ProfileLayout.Group group) {
        StackPane cell = new StackPane();
        cell.getStyleClass().addAll("inv-cell", "inv-cell-empty");
        cell.setMinSize(CELL, CELL);
        cell.setPrefSize(CELL, CELL);
        cell.setMaxSize(CELL, CELL);

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
                host.layout().moveProfileToEnd(ProfileDrag.id(payload),
                        group == null ? null : group.id());
                event.setDropCompleted(true);
                host.layoutChanged();
            }
            event.consume();
        });
        return cell;
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

    // ---------------------------------------------------------------- helpers

    private static boolean rightHalf(DragEvent event, Region cell) {
        return event.getX() > cell.getWidth() / 2;
    }

    private void mark(Region node, String styleClass) {
        clearMark();
        node.getStyleClass().add(styleClass);
        marked = node;
    }

    private void clearMark() {
        if (marked != null) {
            marked.getStyleClass().removeAll("drop-before", "drop-after", "drop-into");
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

    /** Kept for the toolbar above the grid, so it can size itself to the cells. */
    public static double gridWidth() {
        return RAIL + CELL * ProfileLayout.GRID_COLUMNS;
    }

    static {
        // A guard rather than a comment: the cell size and the column count
        // together decide the narrowest the window can usefully be, and the
        // launcher's own minimum is 1120. If either number is raised past that,
        // the grid gets a horizontal scrollbar at the default window size and
        // the change is worth noticing here rather than on screen.
        if (gridWidth() > 1120) {
            System.err.println("inventory grid is " + gridWidth()
                    + " px wide, which is wider than the default window");
        }
    }
}
