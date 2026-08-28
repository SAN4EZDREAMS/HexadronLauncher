package com.hexadron.launcher.profile;

import com.hexadron.launcher.json.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * How the profiles are arranged, independent of which interface draws them.
 *
 * <h2>One arrangement, two interfaces</h2>
 *
 * <p>The launcher shows the same profiles as a list and as a grid of inventory
 * cells, and they have to be one thing seen twice rather than two things kept in
 * step. So neither view owns an order. This class holds the single arrangement
 * both of them render, and it has exactly two parts:
 *
 * <ul>
 *   <li><b>a cell</b> - every profile sits in one cell of a fixed
 *       {@link #rows()} by {@link #columns()} grid, and stays in it;</li>
 *   <li><b>a row's group</b> - a whole row may belong to a named group.</li>
 * </ul>
 *
 * <p>A profile is in a group when its row is. Nothing else records membership,
 * and that is the point: "which group is this in" and "where is this" are the
 * same question, so they cannot give different answers.
 *
 * <h2>Why a group takes rows</h2>
 *
 * <p>The previous attempt made membership a property of the profile rather than
 * of its place, so a group was a colour scattered across the grid - and
 * collapsing it had nothing to close over, because every cell had a fixed place
 * of its own. The control was there and did nothing.
 *
 * <p>A group owning its rows fixes that: collapsing hides those rows in both
 * views, dragging a profile into one of them is how it joins, and dragging it
 * out is how it leaves. The grid draws a group as a band, which is also what it
 * looked like when it was easiest to read.
 *
 * <h2>The grid does not reflow</h2>
 *
 * <p>Cells are absolute. A drop on a free cell puts the profile in that cell; a
 * drop on an occupied one exchanges the two. Nothing moves that was not dragged,
 * and empty cells are real places - the list simply skips them, so a gap in the
 * grid is nothing in the list.
 *
 * <p>Rows and columns are therefore added and removed deliberately.
 * {@link #removeColumn()} and {@link #removeRowAt(int)} move the profiles behind
 * the edge into free cells and refuse when there are none, rather than dropping
 * one off the end. Removing a row is a change to the grid and never to the
 * groups: the last row of a group will not go, because that would delete the
 * group as a side effect of resizing a table.
 *
 * <p>The one thing that grows by itself is a grid with no cell for a profile
 * that exists - a newly created one on a full grid. It gets a row, because a
 * profile that cannot be seen cannot be launched.
 */
public final class ProfileLayout {

    /** Columns the grid starts with: nine, as in the game's own inventory. */
    public static final int DEFAULT_COLUMNS = 9;

    /** Rows the grid starts with. */
    public static final int DEFAULT_ROWS = 3;

    public static final int MIN_COLUMNS = 2;
    public static final int MAX_COLUMNS = 24;
    public static final int MIN_ROWS = 1;
    public static final int MAX_ROWS = 60;

    /** Which interface is showing. Persisted, so the launcher reopens as it was left. */
    public enum Mode {

        LIST("list"),
        INVENTORY("inventory");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public Mode other() {
            return this == LIST ? INVENTORY : LIST;
        }

        public static Mode parse(String value) {
            if (value != null && INVENTORY.id.equalsIgnoreCase(value.trim())) {
                return INVENTORY;
            }
            return LIST;
        }
    }

    /**
     * Colours handed to new groups, in order.
     *
     * <p>Assigned rather than asked for: picking a colour is not a decision worth
     * interrupting "make a group" with, and each of these is legible as a band
     * behind the cells and as a plate down the side of one.
     */
    private static final List<String> PALETTE = List.of(
            "#3d6ea5", "#8a5a3c", "#6b4a8f", "#2d7d46",
            "#a5843d", "#a53d5c", "#3d8a8a", "#6f7d3d");

    /** A named group. It owns rows; the profiles in those rows are its members. */
    public static final class Group {

        private final String id;
        private String name;
        private String color;
        private boolean collapsed;

        private Group(String id, String name, String color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public void name(String value) {
            if (value != null && !value.isBlank()) {
                this.name = value.trim();
            }
        }

        public String color() {
            return color;
        }

        public void color(String value) {
            if (value != null && value.matches("#[0-9a-fA-F]{6}")) {
                this.color = value.toLowerCase(Locale.ROOT);
            }
        }

        /** Folded away in both views: the group owns rows, so there is something to fold. */
        public boolean collapsed() {
            return collapsed;
        }

        public void collapsed(boolean value) {
            this.collapsed = value;
        }
    }

    /** One row of the list: a group header, or a profile. */
    public static final class ListRow {

        private final Group group;
        private final String profileId;
        private final boolean nested;
        private final int memberCount;

        private ListRow(Group group, String profileId, boolean nested, int memberCount) {
            this.group = group;
            this.profileId = profileId;
            this.nested = nested;
            this.memberCount = memberCount;
        }

        public boolean isGroup() {
            return group != null;
        }

        public Group group() {
            return group;
        }

        public String profileId() {
            return profileId;
        }

        /** True for a profile shown inside a group, so the list can indent it. */
        public boolean isNested() {
            return nested;
        }

        /** How many profiles the group holds. Only meaningful on a header. */
        public int memberCount() {
            return memberCount;
        }
    }

    /**
     * A run of consecutive rows with the same group - what the grid draws as one
     * band, with one plate down its side.
     *
     * <p>Built here rather than in the view because it is a fact about the
     * arrangement: which rows belong together is not a drawing decision.
     */
    public static final class Band {

        private final Group group;
        private final List<Integer> rows;
        private final int memberCount;

        private Band(Group group, List<Integer> rows, int memberCount) {
            this.group = group;
            this.rows = List.copyOf(rows);
            this.memberCount = memberCount;
        }

        /** The group this run belongs to, or null for rows in no group. */
        public Group group() {
            return group;
        }

        public List<Integer> rows() {
            return rows;
        }

        /** True when the band is drawn as a single strip instead of its rows. */
        public boolean isCollapsed() {
            return group != null && group.collapsed();
        }

        /** Profiles in the whole group, for the strip to report. */
        public int memberCount() {
            return memberCount;
        }
    }

    /** Where a profile sits. Row and column, not one index: see {@link #columns(int)}. */
    private static final class Cell {

        private int row;
        private int column;

        private Cell(int row, int column) {
            this.row = row;
            this.column = column;
        }
    }

    private final Map<String, Cell> cells = new LinkedHashMap<>();

    /** Row index to group id. A row not in here belongs to no group. */
    private final Map<Integer, String> rowGroups = new LinkedHashMap<>();

    private final List<Group> groups = new ArrayList<>();

    private int columns = DEFAULT_COLUMNS;
    private int rows = DEFAULT_ROWS;
    private Mode mode = Mode.LIST;

    // ---------------------------------------------------------------- geometry

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int capacity() {
        return columns * rows;
    }

    public int occupied() {
        return cells.size();
    }

    public int freeCells() {
        return capacity() - cells.size();
    }

    public Mode mode() {
        return mode;
    }

    public ProfileLayout mode(Mode value) {
        this.mode = value == null ? Mode.LIST : value;
        return this;
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public boolean addColumn() {
        if (columns >= MAX_COLUMNS) {
            return false;
        }
        columns++;
        return true;
    }

    /**
     * Removes the last column, moving anything in it to a free cell.
     *
     * <p>A column crosses every row, so this takes cells away from the groups as
     * well - which is what it is for. A profile displaced from a group's row is
     * offered a free cell in the same group first, so that resizing the table
     * does not quietly move it out of its group; if the group is full it goes
     * wherever there is room, and if there is no room anywhere the column stays.
     */
    public boolean removeColumn() {
        if (columns <= MIN_COLUMNS) {
            return false;
        }
        int last = columns - 1;
        List<String> displaced = new ArrayList<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            if (entry.getValue().column == last) {
                displaced.add(entry.getKey());
            }
        }
        displaced.sort(Comparator.comparingInt(this::indexOf));
        if (!relocate(displaced, cell -> cell.column < last)) {
            return false;
        }
        columns--;
        return true;
    }

    public boolean addRow() {
        return insertRowAt(rows, null);
    }

    /** Removes the last row exactly. Used where a particular height is asked for. */
    public boolean removeRow() {
        return removeRowAt(rows - 1);
    }

    /**
     * Removes the last row that is empty and in no group - the table's own minus.
     *
     * <p>Not simply the last row. A group at the bottom of the grid would make
     * the button refuse while there was still an empty row above it doing
     * nothing, which is a refusal the user can see is wrong. So the button takes
     * away the last row that is nobody's and holds nothing, wherever it is.
     *
     * <p>It never moves a profile and never touches a group. A row with
     * instances in it is emptied by dragging them somewhere, and a row that
     * belongs to a group is taken off that group from the group's own controls -
     * both are things to say deliberately rather than side effects of making the
     * table smaller.
     *
     * @return false when every row is either in a group or has something in it
     */
    public boolean removeLastEmptyRow() {
        if (rows <= MIN_ROWS) {
            return false;
        }
        for (int row = rows - 1; row >= 0; row--) {
            if (rowGroups.get(row) == null && rowIsEmpty(row)) {
                return removeRowAt(row);
            }
        }
        return false;
    }

    /**
     * Removes one row.
     *
     * <p>Refuses when the row is the last one its group has. Removing a row is a
     * change to the table; deleting a group is a change to the arrangement, and
     * one must not happen as a side effect of the other - so the row stays and
     * the caller says why.
     */
    public boolean removeRowAt(int at) {
        if (rows <= MIN_ROWS || at < 0 || at >= rows) {
            return false;
        }
        String groupId = rowGroups.get(at);
        if (groupId != null && rowsOf(groupId).size() <= 1) {
            return false;
        }

        List<String> displaced = new ArrayList<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            if (entry.getValue().row == at) {
                displaced.add(entry.getKey());
            }
        }
        displaced.sort(Comparator.comparingInt(this::indexOf));
        int removed = at;
        if (!relocate(displaced, cell -> cell.row != removed)) {
            return false;
        }

        for (Cell cell : cells.values()) {
            if (cell.row > at) {
                cell.row--;
            }
        }
        Map<Integer, String> shifted = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : rowGroups.entrySet()) {
            int row = entry.getKey();
            if (row == at) {
                continue;
            }
            shifted.put(row > at ? row - 1 : row, entry.getValue());
        }
        rowGroups.clear();
        rowGroups.putAll(shifted);
        rows--;
        return true;
    }

    /**
     * Inserts a row, pushing everything below it down.
     *
     * <p>Cells hold a row and a column rather than one index, so the push is one
     * increment per cell and nothing has to be re-derived.
     */
    public boolean insertRowAt(int at, String groupId) {
        if (rows >= MAX_ROWS || at < 0 || at > rows) {
            return false;
        }
        for (Cell cell : cells.values()) {
            if (cell.row >= at) {
                cell.row++;
            }
        }
        Map<Integer, String> shifted = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : rowGroups.entrySet()) {
            int row = entry.getKey();
            shifted.put(row >= at ? row + 1 : row, entry.getValue());
        }
        rowGroups.clear();
        rowGroups.putAll(shifted);
        rows++;
        if (groupId != null && group(groupId).isPresent()) {
            rowGroups.put(at, groupId);
        }
        return true;
    }

    /**
     * Sets the number of columns.
     *
     * <p>Widening moves nothing: a profile in column three is in column three
     * whatever the width is. Narrowing goes one column at a time and stops at the
     * first one it cannot empty.
     *
     * @return false when the grid could not reach the requested width
     */
    public boolean columns(int value) {
        int target = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, value));
        while (columns < target) {
            if (!addColumn()) {
                return false;
            }
        }
        while (columns > target) {
            if (!removeColumn()) {
                return false;
            }
        }
        return true;
    }

    /** Sets the number of rows, one row at a time, stopping at the first refusal. */
    public boolean rows(int value) {
        int target = Math.max(MIN_ROWS, Math.min(MAX_ROWS, value));
        while (rows < target) {
            if (!addRow()) {
                return false;
            }
        }
        while (rows > target) {
            if (!removeRow()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds a new cell for each displaced profile, in the group it is already in.
     *
     * <p>Strictly in that group, with no fallback anywhere else - and the same
     * for a profile in no group, which may only land in another ungrouped cell.
     * Removing a column is a change to the shape of the table, and a change to
     * the shape of the table must not change what anything belongs to. When the
     * group has no room the answer is that the column stays, which the caller
     * says out loud.
     *
     * @param allowed which of the surviving cells may be used
     * @return false when there are not enough, in which case nothing has moved
     */
    private boolean relocate(List<String> displaced, java.util.function.Predicate<Cell> allowed) {
        if (displaced.isEmpty()) {
            return true;
        }
        Set<Long> taken = new LinkedHashSet<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            if (displaced.contains(entry.getKey())) {
                continue;
            }
            taken.add(key(entry.getValue().row, entry.getValue().column));
        }

        Map<String, int[]> chosen = new LinkedHashMap<>();
        Set<Long> used = new LinkedHashSet<>();
        for (String id : displaced) {
            String container = rowGroups.get(cells.get(id).row);
            int[] found = null;
            for (int row = 0; row < rows && found == null; row++) {
                if (!Objects.equals(rowGroups.get(row), container)) {
                    continue;
                }
                for (int column = 0; column < columns; column++) {
                    if (!allowed.test(new Cell(row, column))
                            || taken.contains(key(row, column))
                            || used.contains(key(row, column))) {
                        continue;
                    }
                    found = new int[]{row, column};
                    break;
                }
            }
            if (found == null) {
                return false;
            }
            chosen.put(id, found);
            used.add(key(found[0], found[1]));
        }

        for (Map.Entry<String, int[]> entry : chosen.entrySet()) {
            Cell cell = cells.get(entry.getKey());
            cell.row = entry.getValue()[0];
            cell.column = entry.getValue()[1];
        }
        return true;
    }

    private static long key(int row, int column) {
        return ((long) row << 32) | column;
    }

    // ---------------------------------------------------------------- placement

    /** The cell of a profile as {@code {row, column}}, or empty when it has none. */
    public Optional<int[]> cellOf(String profileId) {
        Cell cell = profileId == null ? null : cells.get(profileId);
        return cell == null ? Optional.empty() : Optional.of(new int[]{cell.row, cell.column});
    }

    /** Which profile is in a cell, or empty when the cell is free. */
    public Optional<String> at(int row, int column) {
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            Cell cell = entry.getValue();
            if (cell.row == row && cell.column == column) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Puts a profile in a cell.
     *
     * <p>An occupied cell is an exchange, not an insertion: the two profiles swap
     * places and nothing else moves. That is what dragging one item onto another
     * does in the inventory this view is named after, and it is the only
     * behaviour that keeps every other profile where the user put it.
     *
     * <p>It is also how a profile joins or leaves a group, because the row it
     * lands in is what decides that.
     *
     * @return false when the cell is outside the grid
     */
    public boolean placeAt(String profileId, int row, int column) {
        if (profileId == null || !cells.containsKey(profileId)) {
            return false;
        }
        if (row < 0 || column < 0 || row >= rows || column >= columns) {
            return false;
        }
        Cell moving = cells.get(profileId);
        Optional<String> sitting = at(row, column);
        if (sitting.isPresent()) {
            if (sitting.get().equals(profileId)) {
                return true;
            }
            Cell other = cells.get(sitting.get());
            other.row = moving.row;
            other.column = moving.column;
        }
        moving.row = row;
        moving.column = column;
        return true;
    }

    /** Every placed profile in reading order - the order the list draws. */
    public List<String> sequence() {
        List<String> ids = new ArrayList<>(cells.keySet());
        ids.sort(Comparator.comparingInt(this::indexOf));
        return List.copyOf(ids);
    }

    private int indexOf(String profileId) {
        Cell cell = cells.get(profileId);
        return cell == null ? Integer.MAX_VALUE : cell.row * columns + cell.column;
    }

    // ---------------------------------------------------------------- groups

    public List<Group> groups() {
        return List.copyOf(groups);
    }

    public Optional<Group> group(String groupId) {
        if (groupId == null) {
            return Optional.empty();
        }
        return groups.stream().filter(group -> group.id.equals(groupId)).findFirst();
    }

    /** The group that owns a row, or empty when the row belongs to none. */
    public Optional<Group> rowGroup(int row) {
        return group(rowGroups.get(row));
    }

    /** The group of a profile - which is the group of its row, and nothing else. */
    public Optional<Group> groupOf(String profileId) {
        Cell cell = profileId == null ? null : cells.get(profileId);
        return cell == null ? Optional.empty() : rowGroup(cell.row);
    }

    /** The rows a group owns, in order. */
    public List<Integer> rowsOf(String groupId) {
        if (groupId == null) {
            return List.of();
        }
        List<Integer> found = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : rowGroups.entrySet()) {
            if (groupId.equals(entry.getValue())) {
                found.add(entry.getKey());
            }
        }
        found.sort(Comparator.naturalOrder());
        return List.copyOf(found);
    }

    /** The profiles in a group, in reading order. */
    public List<String> membersOf(String groupId) {
        return occupantsOf(groupId);
    }

    /** The profiles whose row belongs to {@code groupId} - null for the ungrouped rows. */
    private List<String> occupantsOf(String groupId) {
        List<String> found = new ArrayList<>();
        for (String id : sequence()) {
            if (Objects.equals(rowGroups.get(cells.get(id).row), groupId)) {
                found.add(id);
            }
        }
        return found;
    }

    /**
     * Makes a group and gives it a row.
     *
     * <p>It takes the first row that is empty and in no group, and appends one
     * when there is none - so making a group never displaces a profile, and the
     * new group is somewhere with room in it to drag things into.
     */
    public Group createGroup(String name) {
        String safe = (name == null || name.isBlank()) ? "Group" : name.trim();
        Group group = new Group(newGroupId(), safe, PALETTE.get(groups.size() % PALETTE.size()));
        groups.add(group);

        for (int row = 0; row < rows; row++) {
            if (rowGroups.get(row) == null && rowIsEmpty(row)) {
                rowGroups.put(row, group.id);
                return group;
            }
        }
        if (!insertRowAt(rows, group.id)) {
            // The grid is at its maximum height. The group exists with no row,
            // which reconcile will not fix on its own - so take the last
            // ungrouped row instead of leaving a group that cannot be seen.
            for (int row = rows - 1; row >= 0; row--) {
                if (rowGroups.get(row) == null) {
                    rowGroups.put(row, group.id);
                    break;
                }
            }
        }
        return group;
    }

    private boolean rowIsEmpty(int row) {
        for (Cell cell : cells.values()) {
            if (cell.row == row) {
                return false;
            }
        }
        return true;
    }

    private String newGroupId() {
        String id;
        do {
            id = "g-" + UUID.randomUUID().toString().substring(0, 8);
        } while (group(id).isPresent());
        return id;
    }

    /**
     * Deletes a group. Its rows stay, and so does everything in them.
     *
     * <p>The rows simply stop belonging to it, so no profile moves and no cell
     * changes - which is what makes deleting a group a safe thing to offer.
     */
    public void removeGroup(String groupId) {
        if (groupId == null) {
            return;
        }
        groups.removeIf(group -> group.id.equals(groupId));
        rowGroups.values().removeIf(groupId::equals);
    }

    public void renameGroup(String groupId, String name) {
        group(groupId).ifPresent(group -> group.name(name));
    }

    public void setCollapsed(String groupId, boolean collapsed) {
        group(groupId).ifPresent(group -> group.collapsed(collapsed));
    }

    /** Gives a group one more row, directly under the rows it already has. */
    public boolean addRowToGroup(String groupId) {
        List<Integer> owned = rowsOf(groupId);
        if (owned.isEmpty()) {
            return false;
        }
        return insertRowAt(owned.get(owned.size() - 1) + 1, groupId);
    }

    /** Takes the last row off a group. Refuses the last one it has. */
    public boolean removeRowFromGroup(String groupId) {
        List<Integer> owned = rowsOf(groupId);
        if (owned.size() <= 1) {
            return false;
        }
        return removeRowAt(owned.get(owned.size() - 1));
    }

    /**
     * Moves a profile into a group, or out of every group when {@code groupId}
     * is null.
     *
     * <p>Membership is the row, so this is a move: the profile goes to a free
     * cell in one of the group's rows. When the group is full it gets another
     * row, because the alternative is refusing a menu item the user has already
     * chosen.
     *
     * @return false only when there is nowhere at all to put it
     */
    public boolean join(String profileId, String groupId) {
        Cell cell = profileId == null ? null : cells.get(profileId);
        if (cell == null) {
            return false;
        }
        if (groupId != null && group(groupId).isEmpty()) {
            return false;
        }
        if (Objects.equals(rowGroups.get(cell.row), groupId)) {
            return true;
        }
        int[] free = firstFreeCellIn(groupId);
        if (free == null) {
            boolean grew = groupId == null ? addRow() : addRowToGroup(groupId);
            if (!grew) {
                return false;
            }
            free = firstFreeCellIn(groupId);
        }
        return free != null && placeAt(profileId, free[0], free[1]);
    }

    private int[] firstFreeCellIn(String groupId) {
        for (int row = 0; row < rows; row++) {
            if (!Objects.equals(rowGroups.get(row), groupId)) {
                continue;
            }
            for (int column = 0; column < columns; column++) {
                if (at(row, column).isEmpty()) {
                    return new int[]{row, column};
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- reordering

    /**
     * Rewrites which profile sits in which cell, within one group only.
     *
     * <p>This is how the list reorders. It is confined to a single group -
     * including the ungrouped rows, taken as a container of their own - because a
     * reorder that spilled across a group boundary would push the profile at the
     * end of the group out of it, changing the membership of something nobody
     * dragged.
     */
    private void reorderWithin(String groupId, List<String> order) {
        List<int[]> places = new ArrayList<>();
        for (String id : occupantsOf(groupId)) {
            Cell cell = cells.get(id);
            places.add(new int[]{cell.row, cell.column});
        }
        if (places.size() != order.size()) {
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            Cell cell = cells.get(order.get(i));
            if (cell == null) {
                return;
            }
            cell.row = places.get(i)[0];
            cell.column = places.get(i)[1];
        }
    }

    /**
     * Moves one profile immediately before or after another, in list order.
     *
     * <p>The target's group decides the dragged profile's group first - dropped
     * among a group's members it joins them, dropped among the loose rows it
     * leaves - and then the two are reordered inside that one container.
     */
    public void moveProfileBeside(String profileId, String targetId, boolean after) {
        if (profileId == null || targetId == null || profileId.equals(targetId)) {
            return;
        }
        Cell target = cells.get(targetId);
        if (target == null || !cells.containsKey(profileId)) {
            return;
        }
        String container = rowGroups.get(target.row);
        if (!join(profileId, container)) {
            return;
        }
        List<String> order = occupantsOf(container);
        order.remove(profileId);
        int at = order.indexOf(targetId);
        if (at < 0) {
            order.add(profileId);
        } else {
            order.add(after ? at + 1 : at, profileId);
        }
        reorderWithin(container, order);
    }

    /**
     * Moves a group's rows above or below another row, keeping them together.
     *
     * <p>A group is its rows, so moving a group in the list is moving those rows
     * in the grid. When the destination is inside another group the whole of that
     * group is stepped over rather than split, because a group with somebody
     * else's row in the middle of it is not a group any more.
     */
    public void moveGroupBeside(String groupId, String targetProfileId, boolean after) {
        List<Integer> block = rowsOf(groupId);
        Cell target = targetProfileId == null ? null : cells.get(targetProfileId);
        if (block.isEmpty() || target == null || block.contains(target.row)) {
            return;
        }
        int destination = target.row;
        String targetGroup = rowGroups.get(destination);
        if (targetGroup != null) {
            List<Integer> theirs = rowsOf(targetGroup);
            destination = after ? theirs.get(theirs.size() - 1) : theirs.get(0);
        }
        moveRowsBeside(block, destination, after);
    }

    /** Moves a block of rows next to another row, keeping every cell with its row. */
    public void moveRowsBeside(List<Integer> block, int destination, boolean after) {
        if (block == null || block.isEmpty() || block.contains(destination)
                || destination < 0 || destination >= rows) {
            return;
        }
        List<Integer> order = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            order.add(row);
        }
        List<Integer> moving = new ArrayList<>(block);
        moving.sort(Comparator.naturalOrder());
        order.removeAll(moving);
        int at = order.indexOf(destination);
        if (at < 0) {
            order.addAll(moving);
        } else {
            order.addAll(after ? at + 1 : at, moving);
        }
        applyRowOrder(order);
    }

    private void applyRowOrder(List<Integer> order) {
        Map<Integer, Integer> moved = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            moved.put(order.get(i), i);
        }
        for (Cell cell : cells.values()) {
            Integer to = moved.get(cell.row);
            if (to != null) {
                cell.row = to;
            }
        }
        Map<Integer, String> shifted = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : rowGroups.entrySet()) {
            Integer to = moved.get(entry.getKey());
            if (to != null) {
                shifted.put(to, entry.getValue());
            }
        }
        rowGroups.clear();
        rowGroups.putAll(shifted);
    }

    // ---------------------------------------------------------------- the list

    /**
     * The list, derived from the grid.
     *
     * <p>Walks the rows in order. A row in no group contributes its profiles as
     * rows of their own; a row in a group brings the group's header the first
     * time that group is reached, and its members underneath - or nothing but the
     * header when it is collapsed. Empty cells contribute nothing, which is the
     * rule the interface is specified to follow: a gap in the grid is not a row
     * in the list.
     */
    public List<ListRow> listRows() {
        List<ListRow> out = new ArrayList<>();
        Set<String> seenGroups = new LinkedHashSet<>();

        for (int row = 0; row < rows; row++) {
            String groupId = rowGroups.get(row);
            if (groupId == null) {
                for (int column = 0; column < columns; column++) {
                    at(row, column).ifPresent(id -> out.add(new ListRow(null, id, false, 0)));
                }
                continue;
            }
            Group group = group(groupId).orElse(null);
            if (group == null) {
                continue;
            }
            if (seenGroups.add(groupId)) {
                out.add(new ListRow(group, null, false, occupantsOf(groupId).size()));
            }
            if (group.collapsed()) {
                continue;
            }
            for (int column = 0; column < columns; column++) {
                at(row, column).ifPresent(id -> out.add(new ListRow(null, id, true, 0)));
            }
        }
        return List.copyOf(out);
    }

    /** The grid, as runs of consecutive rows sharing a group. */
    public List<Band> bands() {
        List<Band> out = new ArrayList<>();
        int row = 0;
        while (row < rows) {
            String groupId = rowGroups.get(row);
            int end = row;
            while (end + 1 < rows && Objects.equals(rowGroups.get(end + 1), groupId)) {
                end++;
            }
            List<Integer> block = new ArrayList<>();
            for (int index = row; index <= end; index++) {
                block.add(index);
            }
            Group group = group(groupId).orElse(null);
            out.add(new Band(group, block,
                    group == null ? 0 : occupantsOf(group.id).size()));
            row = end + 1;
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- tidying

    /**
     * Sorts by name inside every group, and inside the ungrouped rows.
     *
     * <p>Groups do not move: they are rows, and a sort that shuffled the rows
     * would rearrange the grid rather than tidy it. Unlike everything else here
     * this does move profiles the user placed by hand - that is what it is for,
     * and it is only ever reached from the button that says so.
     */
    public void sortByName(Collection<Profile> profiles) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            names.put(profile.id(), profile.name());
        }
        Comparator<String> byName = Comparator.comparing(
                id -> names.getOrDefault(id, id), String.CASE_INSENSITIVE_ORDER);

        List<String> containers = new ArrayList<>();
        containers.add(null);
        groups.forEach(group -> containers.add(group.id));
        for (String container : containers) {
            List<String> order = occupantsOf(container);
            order.sort(byName);
            reorderWithin(container, order);
        }
    }

    // ---------------------------------------------------------------- reconcile

    /**
     * Brings the arrangement in line with the profiles that actually exist.
     *
     * <p>Called after every load and after every add or remove. Six things go
     * wrong and all six are repaired rather than reported: an id for a profile
     * that is gone, a row assigned to a group that is gone, two profiles in one
     * cell, a cell outside the grid, a profile with no cell at all, and an
     * arrangement that is empty. The last is a first run, and it seeds the
     * alphabetical arrangement the interface starts from.
     *
     * @return true when something changed, so the caller knows to save
     */
    public boolean reconcile(Collection<Profile> profiles) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            names.put(profile.id(), profile.name());
        }

        boolean fresh = cells.isEmpty() && !names.isEmpty();
        boolean changed = cells.keySet().retainAll(names.keySet());
        changed |= rowGroups.values().removeIf(groupId -> group(groupId).isEmpty());
        changed |= rowGroups.keySet().removeIf(row -> row < 0 || row >= rows);

        // A group with no rows cannot be seen or dragged into, so it is given
        // one rather than left as an entry nothing renders.
        for (Group group : new ArrayList<>(groups)) {
            if (rowsOf(group.id).isEmpty()) {
                changed = true;
                boolean placed = false;
                for (int row = 0; row < rows; row++) {
                    if (rowGroups.get(row) == null && rowIsEmpty(row)) {
                        rowGroups.put(row, group.id);
                        placed = true;
                        break;
                    }
                }
                if (!placed && !insertRowAt(rows, group.id)) {
                    groups.remove(group);
                }
            }
        }

        Set<Long> taken = new LinkedHashSet<>();
        List<String> unplaced = new ArrayList<>();
        for (String id : new ArrayList<>(cells.keySet())) {
            Cell cell = cells.get(id);
            boolean inside = cell.row >= 0 && cell.column >= 0
                    && cell.row < rows && cell.column < columns;
            if (!inside || !taken.add(key(cell.row, cell.column))) {
                unplaced.add(id);
                changed = true;
            }
        }
        for (String id : names.keySet()) {
            if (!cells.containsKey(id)) {
                cells.put(id, new Cell(-1, -1));
                unplaced.add(id);
                changed = true;
            }
        }

        if (!unplaced.isEmpty()) {
            unplaced.sort(Comparator.comparing(
                    id -> names.getOrDefault(id, id), String.CASE_INSENSITIVE_ORDER));
            place(unplaced, taken);
        }

        if (fresh) {
            sortByName(profiles);
        }
        return changed;
    }

    /**
     * Finds cells for profiles that have none.
     *
     * <p>Ungrouped rows first. A profile the launcher is placing on the user's
     * behalf - a newly created one, or one whose cell was unreadable - must not
     * quietly land in somebody's group.
     */
    private void place(List<String> unplaced, Set<Long> taken) {
        for (String id : unplaced) {
            int[] cell = firstFree(taken, true);
            if (cell == null) {
                cell = firstFree(taken, false);
            }
            if (cell == null) {
                // The one case the grid grows by itself: a profile with no cell
                // can be neither seen nor launched.
                if (!addRow()) {
                    continue;
                }
                cell = firstFree(taken, true);
            }
            if (cell == null) {
                continue;
            }
            Cell target = cells.get(id);
            target.row = cell[0];
            target.column = cell[1];
            taken.add(key(cell[0], cell[1]));
        }
    }

    private int[] firstFree(Set<Long> taken, boolean ungroupedOnly) {
        for (int row = 0; row < rows; row++) {
            if (ungroupedOnly && rowGroups.get(row) != null) {
                continue;
            }
            for (int column = 0; column < columns; column++) {
                if (!taken.contains(key(row, column))) {
                    return new int[]{row, column};
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- persistence

    public Json toJson() {
        Json groupList = Json.array();
        for (Group group : groups) {
            groupList.add(Json.object()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("color", group.color)
                    .put("collapsed", group.collapsed));
        }

        Json rowList = Json.array();
        List<Integer> assigned = new ArrayList<>(rowGroups.keySet());
        assigned.sort(Comparator.naturalOrder());
        for (int row : assigned) {
            rowList.add(Json.object().put("row", row).put("group", rowGroups.get(row)));
        }

        Json placed = Json.array();
        for (String id : sequence()) {
            Cell cell = cells.get(id);
            placed.add(Json.object()
                    .put("id", id)
                    .put("row", cell.row)
                    .put("column", cell.column));
        }

        return Json.object()
                .put("mode", mode.id())
                .put("columns", columns)
                .put("rows", rows)
                .put("groups", groupList)
                .put("rowGroups", rowList)
                .put("cells", placed);
    }

    public static ProfileLayout fromJson(Json json) {
        ProfileLayout layout = new ProfileLayout();
        if (json == null || !json.isObject()) {
            return layout;
        }
        layout.mode = Mode.parse(json.get("mode").asString(Mode.LIST.id()));
        layout.columns = clamp(json.get("columns").asInt(DEFAULT_COLUMNS), MIN_COLUMNS, MAX_COLUMNS);
        layout.rows = clamp(json.get("rows").asInt(DEFAULT_ROWS), MIN_ROWS, MAX_ROWS);

        Set<String> seen = new LinkedHashSet<>();
        for (Json entry : json.get("groups").elements()) {
            String id = entry.get("id").asString(null);
            if (id == null || !seen.add(id)) {
                continue;
            }
            Group group = new Group(id, entry.get("name").asString(id),
                    PALETTE.get((seen.size() - 1) % PALETTE.size()));
            group.color(entry.get("color").asString(null));
            group.collapsed(entry.get("collapsed").asBool(false));
            layout.groups.add(group);
        }

        for (Json entry : json.get("rowGroups").elements()) {
            int row = entry.get("row").asInt(-1);
            String groupId = entry.get("group").asString(null);
            if (row >= 0 && groupId != null && layout.group(groupId).isPresent()) {
                layout.rowGroups.put(row, groupId);
            }
        }

        // The cells, and then the two earlier shapes of this file. Both of those
        // recorded an order and a membership but no grid, so both are read the
        // same way and laid out again.
        Map<String, String> legacyMembership = new LinkedHashMap<>();
        List<String> legacyOrder = new ArrayList<>();
        boolean legacy = layout.rowGroups.isEmpty() && json.get("rowGroups").elements().isEmpty();

        for (Json entry : json.get("cells").elements()) {
            String id = entry.get("id").asString(null);
            if (id == null || layout.cells.containsKey(id)) {
                continue;
            }
            layout.cells.put(id, new Cell(entry.get("row").asInt(-1), entry.get("column").asInt(-1)));
            legacyOrder.add(id);
            String groupId = entry.get("group").asString(null);
            if (groupId != null) {
                legacyMembership.put(id, groupId);
            }
        }

        if (!legacyMembership.isEmpty() && legacy) {
            // The version that kept membership on the profile. Rebuild it as rows.
            layout.cells.clear();
            layout.layOut(legacyOrder, legacyMembership);
        } else if (layout.cells.isEmpty()) {
            readFirstFormat(layout, json);
        }
        return layout;
    }

    /**
     * Reads the arrangement written by the first version.
     *
     * <p>That one had no grid at all: a list of top-level entries, each either a
     * profile or a group with its members inside. The order and the grouping are
     * all there is to carry over, so they are read and laid out again - which is
     * better than an upgrade that silently discards an arrangement somebody has
     * already made.
     */
    private static void readFirstFormat(ProfileLayout layout, Json json) {
        List<String> order = new ArrayList<>();
        Map<String, String> membership = new LinkedHashMap<>();
        int paletteAt = layout.groups.size();

        for (Json entry : json.get("entries").elements()) {
            String type = entry.get("type").asString("profile");
            if (!"group".equalsIgnoreCase(type)) {
                String id = entry.get("id").asString(null);
                if (id != null && !order.contains(id)) {
                    order.add(id);
                }
                continue;
            }
            String groupId = entry.get("id").asString(null);
            if (groupId == null || layout.group(groupId).isPresent()) {
                continue;
            }
            Group group = new Group(groupId, entry.get("name").asString(groupId),
                    PALETTE.get(paletteAt++ % PALETTE.size()));
            group.color(entry.get("color").asString(null));
            group.collapsed(entry.get("collapsed").asBool(false));
            layout.groups.add(group);
            for (Json member : entry.get("members").elements()) {
                String id = member.asString(null);
                if (id != null && !order.contains(id)) {
                    order.add(id);
                    membership.put(id, groupId);
                }
            }
        }
        layout.layOut(order, membership);
    }

    /**
     * Lays a sequence with a membership into rows.
     *
     * <p>Ungrouped profiles fill the ungrouped rows in order; each group gets
     * rows of its own, holding its members, in the order the groups are first
     * met. That is the shape this class holds now, so a file from either earlier
     * version comes out as a grid with the same reading order it had as a list.
     */
    private void layOut(List<String> order, Map<String, String> membership) {
        cells.clear();
        rowGroups.clear();
        rows = 1;

        int row = 0;
        int column = 0;
        Set<String> done = new LinkedHashSet<>();

        for (String id : order) {
            if (done.contains(id)) {
                continue;
            }
            String groupId = membership.get(id);
            if (groupId == null) {
                if (column >= columns) {
                    row++;
                    column = 0;
                }
                while (rows <= row) {
                    rows++;
                }
                cells.put(id, new Cell(row, column++));
                done.add(id);
                continue;
            }
            // A group starts a row of its own and keeps it.
            if (column > 0 || rowGroups.get(row) != null) {
                row++;
                column = 0;
            }
            while (rows <= row) {
                rows++;
            }
            rowGroups.put(row, groupId);
            int groupColumn = 0;
            for (String member : order) {
                if (!groupId.equals(membership.get(member)) || done.contains(member)) {
                    continue;
                }
                if (groupColumn >= columns) {
                    row++;
                    groupColumn = 0;
                    while (rows <= row) {
                        rows++;
                    }
                    rowGroups.put(row, groupId);
                }
                cells.put(member, new Cell(row, groupColumn++));
                done.add(member);
            }
            row++;
            column = 0;
        }
        if (rows < DEFAULT_ROWS) {
            rows = DEFAULT_ROWS;
        }
        rows = Math.min(rows, MAX_ROWS);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
