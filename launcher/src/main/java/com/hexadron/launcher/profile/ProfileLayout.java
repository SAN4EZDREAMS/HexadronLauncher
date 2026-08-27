package com.hexadron.launcher.profile;

import com.hexadron.launcher.json.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li><b>a place</b> - every profile sits in one cell of a fixed
 *       {@link #rows()} by {@link #columns()} grid, and stays in it;</li>
 *   <li><b>a group</b> - a profile may belong to one named group, which says
 *       nothing about where it sits.</li>
 * </ul>
 *
 * <p>The grid draws the cells. The list walks the same cells in reading order
 * and ignores the empty ones, so a gap in the grid is nothing in the list. That
 * is the whole mapping, and it is why a move in either view is already true in
 * the other: there is one set of coordinates and both views read it.
 *
 * <h2>Why a fixed grid with gaps</h2>
 *
 * <p>The first version wrapped profiles into as many rows as they needed, and
 * "the end of the list" was a position rather than a place. That made a drop on
 * a free cell mean "append", so dragging a profile onto the empty cells at the
 * end of the top row moved it to the bottom of the grid - it looked like the
 * drag had failed. Cells are absolute now: a drop on a free cell puts the
 * profile in that cell, and a drop on an occupied one exchanges the two. Nothing
 * moves that the user did not move.
 *
 * <p>The grid therefore does not grow by itself. Rows and columns are added and
 * removed deliberately, from the grid's own edges or from the settings window,
 * and {@link #removeRow()} and {@link #removeColumn()} refuse rather than
 * discard: an edge that still has profiles behind it can only go if there are
 * free cells to move them into.
 *
 * <p>The single exception is a profile with nowhere to be - a newly created one
 * on a full grid. It gets a new row, because the alternative is a profile that
 * exists and cannot be seen.
 *
 * <h2>Grouping is not geometry</h2>
 *
 * <p>Groups do not nest, and they do not occupy rows. In the list a group is a
 * header with its members under it, and it can be collapsed. In the grid it is a
 * colour on its members' cells plus a chip on the left rail, because a cell has
 * a fixed place and collapsing cannot move it. Collapsing is therefore a list
 * behaviour only - the grid has nothing to fold.
 *
 * <h2>Reconciliation</h2>
 *
 * <p>{@code profiles.json} is an editable file and the arrangement lives in it,
 * so the arrangement is always treated as a hint. {@link #reconcile} drops ids
 * for profiles that are gone, gives a cell to profiles that have none, moves
 * anything outside the grid back inside, and seeds an alphabetical arrangement
 * when there is none. A layout that is empty, hand-broken or half-restored
 * degrades to the alphabetical order; it never hides a profile.
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
     * <p>A group needs a colour because in the grid the colour is the only thing
     * that says which cells belong to it - there is no room for a heading. They
     * are assigned rather than asked for: picking a colour is not a decision
     * worth interrupting "make a group" with, and each of these is legible
     * against the dark panel behind it.
     */
    private static final List<String> PALETTE = List.of(
            "#3d6ea5", "#8a5a3c", "#6b4a8f", "#2d7d46",
            "#a5843d", "#a53d5c", "#3d8a8a", "#6f7d3d");

    /** A named group. Membership only - it says nothing about where anything sits. */
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

        /** Collapsed in the list. The grid has fixed cells and nothing to fold. */
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

    /** Where a profile sits. Row and column, not an index: see {@link #columns(int)}. */
    private static final class Cell {

        private int row;
        private int column;

        private Cell(int row, int column) {
            this.row = row;
            this.column = column;
        }
    }

    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final Map<String, String> membership = new LinkedHashMap<>();
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

    public boolean addColumn() {
        if (columns >= MAX_COLUMNS) {
            return false;
        }
        columns++;
        return true;
    }

    public boolean addRow() {
        if (rows >= MAX_ROWS) {
            return false;
        }
        rows++;
        return true;
    }

    /**
     * Removes the last column, moving anything in it to a free cell.
     *
     * <p>"Moving to a free cell" and not "shifting everything left", because a
     * shift would move profiles the user never touched. The occupants of the
     * column being removed go to the first free cells in reading order, and
     * everything else stays exactly where it is.
     *
     * @return false when there are not enough free cells, so the caller can say
     *         so instead of losing a profile
     */
    public boolean removeColumn() {
        if (columns <= MIN_COLUMNS) {
            return false;
        }
        return shrink(columns - 1, rows);
    }

    /** Removes the last row, moving anything in it to a free cell. */
    public boolean removeRow() {
        if (rows <= MIN_ROWS) {
            return false;
        }
        return shrink(columns, rows - 1);
    }

    /**
     * Sets the number of columns.
     *
     * <p>Cells hold a row and a column rather than one index, so widening the
     * grid moves nothing: a profile in column three is in column three whatever
     * the width is. Only narrowing has work to do, and it can be refused.
     *
     * @return false when narrowing would leave a profile with no cell
     */
    public boolean columns(int value) {
        int target = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, value));
        if (target == columns) {
            return true;
        }
        if (target > columns) {
            columns = target;
            return true;
        }
        return shrink(target, rows);
    }

    /** Sets the number of rows. Refuses a shrink that would leave a profile with no cell. */
    public boolean rows(int value) {
        int target = Math.max(MIN_ROWS, Math.min(MAX_ROWS, value));
        if (target == rows) {
            return true;
        }
        if (target > rows) {
            rows = target;
            return true;
        }
        return shrink(columns, target);
    }

    private boolean shrink(int newColumns, int newRows) {
        List<String> displaced = new ArrayList<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            Cell cell = entry.getValue();
            if (cell.row >= newRows || cell.column >= newColumns) {
                displaced.add(entry.getKey());
            }
        }
        if (displaced.isEmpty()) {
            columns = newColumns;
            rows = newRows;
            return true;
        }

        // Reading order, so what is moved is moved predictably rather than in
        // whatever order the map happens to hold.
        displaced.sort(Comparator.comparingInt(this::indexOf));

        List<int[]> free = freeCellsWithin(newColumns, newRows, displaced);
        if (free.size() < displaced.size()) {
            return false;
        }
        for (int i = 0; i < displaced.size(); i++) {
            Cell cell = cells.get(displaced.get(i));
            cell.row = free.get(i)[0];
            cell.column = free.get(i)[1];
        }
        columns = newColumns;
        rows = newRows;
        return true;
    }

    /** Free cells inside a candidate grid, treating {@code ignored} as already gone. */
    private List<int[]> freeCellsWithin(int newColumns, int newRows, Collection<String> ignored) {
        Set<Integer> taken = new LinkedHashSet<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            if (ignored.contains(entry.getKey())) {
                continue;
            }
            Cell cell = entry.getValue();
            if (cell.row < newRows && cell.column < newColumns) {
                taken.add(cell.row * newColumns + cell.column);
            }
        }
        List<int[]> free = new ArrayList<>();
        for (int index = 0; index < newColumns * newRows; index++) {
            if (!taken.contains(index)) {
                free.add(new int[]{index / newColumns, index % newColumns});
            }
        }
        return free;
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
     * <p>An occupied cell is an exchange, not an insertion: the two profiles
     * swap places and nothing else moves. That is what dragging one item onto
     * another does in the inventory this view is named after, and it is the only
     * behaviour that keeps every other profile where the user put it.
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

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    // ---------------------------------------------------------------- reordering

    /**
     * Rewrites which profile sits in which occupied cell, from a new order.
     *
     * <p>This is how the list reorders. The list has no cells of its own: it
     * shows the placed profiles in reading order with the gaps left out, so
     * dragging a row to a new position means the same set of cells now holds the
     * profiles in a different order. The gaps stay exactly where they were,
     * which is what makes a gap "nothing" in the list rather than something the
     * list has to represent.
     */
    private void reassign(List<String> order) {
        List<int[]> places = new ArrayList<>();
        for (String id : sequence()) {
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

    /** Moves one profile immediately before or after another, in list order. */
    public void moveProfileBeside(String profileId, String targetId, boolean after) {
        if (profileId == null || targetId == null || profileId.equals(targetId)) {
            return;
        }
        moveBlockBeside(List.of(profileId), targetId, after);
    }

    /**
     * Moves several profiles, keeping their order, to just before or after a
     * target profile in list order.
     *
     * <p>Used for a whole group: dragging a group header in the list moves its
     * members together, which is the only reading of that gesture that does not
     * scatter them.
     */
    public void moveBlockBeside(List<String> block, String targetId, boolean after) {
        if (block == null || block.isEmpty() || targetId == null || block.contains(targetId)) {
            return;
        }
        List<String> order = new ArrayList<>(sequence());
        List<String> moving = new ArrayList<>();
        for (String id : order) {
            if (block.contains(id)) {
                moving.add(id);
            }
        }
        if (moving.isEmpty()) {
            return;
        }
        order.removeAll(moving);
        int at = order.indexOf(targetId);
        if (at < 0) {
            order.addAll(moving);
        } else {
            order.addAll(after ? at + 1 : at, moving);
        }
        reassign(order);
    }

    /** Moves a whole group beside a profile in list order. */
    public void moveGroupBeside(String groupId, String targetId, boolean after) {
        moveBlockBeside(membersOf(groupId), targetId, after);
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

    /** The group holding a profile, or empty when it belongs to none. */
    public Optional<Group> groupOf(String profileId) {
        return group(membership.get(profileId));
    }

    /** The profiles in a group, in list order. */
    public List<String> membersOf(String groupId) {
        if (groupId == null) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        for (String id : sequence()) {
            if (groupId.equals(membership.get(id))) {
                members.add(id);
            }
        }
        return List.copyOf(members);
    }

    public Group createGroup(String name) {
        String safe = (name == null || name.isBlank()) ? "Group" : name.trim();
        Group group = new Group(newGroupId(), safe, PALETTE.get(groups.size() % PALETTE.size()));
        groups.add(group);
        return group;
    }

    private String newGroupId() {
        String id;
        do {
            id = "g-" + UUID.randomUUID().toString().substring(0, 8);
        } while (group(id).isPresent());
        return id;
    }

    /**
     * Deletes a group and keeps its profiles.
     *
     * <p>Nothing moves. A group is membership only, so losing it costs the
     * profiles their colour and their heading, not their cells - which is also
     * why deleting one is a safe thing to offer.
     */
    public void removeGroup(String groupId) {
        if (groupId == null) {
            return;
        }
        groups.removeIf(group -> group.id.equals(groupId));
        membership.entrySet().removeIf(entry -> groupId.equals(entry.getValue()));
    }

    public void renameGroup(String groupId, String name) {
        group(groupId).ifPresent(group -> group.name(name));
    }

    public void setCollapsed(String groupId, boolean collapsed) {
        group(groupId).ifPresent(group -> group.collapsed(collapsed));
    }

    /** Puts a profile in a group, or takes it out when {@code groupId} is null. */
    public void join(String profileId, String groupId) {
        if (profileId == null) {
            return;
        }
        if (groupId == null) {
            membership.remove(profileId);
            return;
        }
        if (group(groupId).isPresent()) {
            membership.put(profileId, groupId);
        }
    }

    // ---------------------------------------------------------------- the list

    /**
     * The list, derived from the cells.
     *
     * <p>Walks the profiles in reading order. A profile with no group is a row of
     * its own; the first member of a group brings the whole group with it - the
     * header at that position, then its members, in the same reading order. So
     * where a group appears in the list is where its first profile sits in the
     * grid, and the two views cannot disagree about the order because only one
     * of them holds it.
     */
    public List<ListRow> listRows() {
        List<ListRow> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();

        for (String id : sequence()) {
            if (done.contains(id)) {
                continue;
            }
            Optional<Group> group = groupOf(id);
            if (group.isEmpty()) {
                out.add(new ListRow(null, id, false, 0));
                done.add(id);
                continue;
            }
            Group owner = group.get();
            List<String> members = membersOf(owner.id());
            out.add(new ListRow(owner, null, false, members.size()));
            for (String member : members) {
                if (!owner.collapsed()) {
                    out.add(new ListRow(null, member, true, 0));
                }
                // Marked done either way: a collapsed group must not have its
                // members reappear further down the list.
                done.add(member);
            }
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- tidying

    /**
     * Sorts by name and closes the gaps.
     *
     * <p>Groups stay together and are placed by their own name among the
     * ungrouped profiles, because a sort that scattered a group would undo the
     * grouping. Unlike every other operation here this one does move profiles
     * the user placed by hand - that is what it is for, and it is only ever
     * reached by pressing the button that says so.
     */
    public void sortByName(Collection<Profile> profiles) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            names.put(profile.id(), profile.name());
        }
        Comparator<String> byName = Comparator.comparing(
                id -> names.getOrDefault(id, id), String.CASE_INSENSITIVE_ORDER);

        // One unit per ungrouped profile and per group, keyed by the name the
        // user sees, so the sort is the sort they asked for.
        List<String> units = new ArrayList<>();
        Map<String, List<String>> contents = new LinkedHashMap<>();
        Map<String, String> unitNames = new LinkedHashMap<>();

        for (Group group : groups) {
            List<String> members = membersOf(group.id());
            if (members.isEmpty()) {
                continue;
            }
            List<String> sorted = new ArrayList<>(members);
            sorted.sort(byName);
            units.add("g:" + group.id());
            contents.put("g:" + group.id(), sorted);
            unitNames.put("g:" + group.id(), group.name());
        }
        for (String id : sequence()) {
            if (membership.containsKey(id) && group(membership.get(id)).isPresent()) {
                continue;
            }
            units.add("p:" + id);
            contents.put("p:" + id, List.of(id));
            unitNames.put("p:" + id, names.getOrDefault(id, id));
        }
        units.sort(Comparator.comparing(unitNames::get, String.CASE_INSENSITIVE_ORDER));

        List<String> order = new ArrayList<>();
        units.forEach(unit -> order.addAll(contents.get(unit)));

        // Filling from the first cell rather than reassigning the occupied ones:
        // sorting is the one action that is also meant to tidy the holes away.
        ensureCapacity(order.size());
        for (int i = 0; i < order.size(); i++) {
            Cell cell = cells.get(order.get(i));
            cell.row = i / columns;
            cell.column = i % columns;
        }
    }

    // ---------------------------------------------------------------- reconcile

    /**
     * Brings the arrangement in line with the profiles that actually exist.
     *
     * <p>Called after every load and after every add or remove. Five things go
     * wrong and all five are repaired rather than reported: an id for a profile
     * that is gone, two profiles in one cell, a cell outside the grid, a profile
     * with no cell at all, and an arrangement that is empty. The last is a first
     * run or an upgrade from a launcher that had no grid, and it seeds the
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
        changed |= membership.keySet().retainAll(names.keySet());
        changed |= membership.values().removeIf(groupId -> group(groupId).isEmpty());

        // One profile per cell, and every cell inside the grid. Both are checked
        // in one pass, because a duplicate and an out-of-range cell are repaired
        // the same way: the later profile is treated as unplaced.
        Set<Integer> taken = new LinkedHashSet<>();
        List<String> unplaced = new ArrayList<>();
        for (String id : new ArrayList<>(cells.keySet())) {
            Cell cell = cells.get(id);
            boolean inside = cell.row >= 0 && cell.column >= 0
                    && cell.row < rows && cell.column < columns;
            if (!inside || !taken.add(cell.row * columns + cell.column)) {
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
            ensureCapacity(cells.size());
            List<int[]> free = freeCellsWithin(columns, rows, unplaced);
            for (int i = 0; i < unplaced.size() && i < free.size(); i++) {
                Cell cell = cells.get(unplaced.get(i));
                cell.row = free.get(i)[0];
                cell.column = free.get(i)[1];
            }
        }

        if (fresh) {
            sortByName(profiles);
        }
        return changed;
    }

    /**
     * Adds rows until the grid can hold {@code needed} profiles.
     *
     * <p>The only place the grid grows on its own, and it is a floor rather than
     * a policy: a profile that exists with no cell to sit in cannot be seen or
     * launched, which is worse than a grid one row taller than the user asked
     * for. Nothing is ever moved to make room.
     */
    private void ensureCapacity(int needed) {
        while (capacity() < needed && rows < MAX_ROWS) {
            rows++;
        }
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

        Json placed = Json.array();
        for (String id : sequence()) {
            Cell cell = cells.get(id);
            Json entry = Json.object()
                    .put("id", id)
                    .put("row", cell.row)
                    .put("column", cell.column);
            String groupId = membership.get(id);
            if (groupId != null) {
                entry.put("group", groupId);
            }
            placed.add(entry);
        }

        return Json.object()
                .put("mode", mode.id())
                .put("columns", columns)
                .put("rows", rows)
                .put("groups", groupList)
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

        for (Json entry : json.get("cells").elements()) {
            String id = entry.get("id").asString(null);
            if (id == null || layout.cells.containsKey(id)) {
                continue;
            }
            layout.cells.put(id, new Cell(entry.get("row").asInt(-1), entry.get("column").asInt(-1)));
            String groupId = entry.get("group").asString(null);
            if (groupId != null) {
                layout.membership.put(id, groupId);
            }
        }

        if (layout.cells.isEmpty()) {
            readOldFormat(layout, json);
        }
        return layout;
    }

    /**
     * Reads the arrangement written by the first version.
     *
     * <p>That one had no grid: it held a list of top-level entries, each either a
     * profile or a group with its members inside. The order is all that can be
     * carried over, so it is read as a sequence and laid into the grid in reading
     * order, and the groups keep their names, colours and collapsed state.
     *
     * <p>Kept rather than dropped because the alternative is an upgrade that
     * silently loses the arrangement somebody has already made, and because the
     * whole of it is twenty lines.
     */
    private static void readOldFormat(ProfileLayout layout, Json json) {
        int index = 0;
        int paletteAt = layout.groups.size();
        for (Json entry : json.get("entries").elements()) {
            String type = entry.get("type").asString("profile");
            if (!"group".equalsIgnoreCase(type)) {
                String id = entry.get("id").asString(null);
                if (id != null && !layout.cells.containsKey(id)) {
                    layout.cells.put(id, new Cell(index / layout.columns, index % layout.columns));
                    index++;
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
                if (id == null || layout.cells.containsKey(id)) {
                    continue;
                }
                layout.cells.put(id, new Cell(index / layout.columns, index % layout.columns));
                layout.membership.put(id, groupId);
                index++;
            }
        }
        if (index > layout.capacity()) {
            layout.ensureCapacity(index);
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
