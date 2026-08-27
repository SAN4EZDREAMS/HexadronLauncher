package com.hexadron.launcher.profile;

import com.hexadron.launcher.json.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
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
 * <h2>One order, two interfaces</h2>
 *
 * <p>The launcher shows the same profiles in two forms - a list and a grid of
 * inventory cells - and the requirement that matters most is that they are one
 * thing seen twice, not two things kept in step. So neither view owns an order
 * of its own. This class holds the single arrangement both of them render:
 *
 * <pre>
 * entries := [ profile | group ]*        top level, in the order shown
 * group   := name, colour, collapsed, [ profile ]*
 * </pre>
 *
 * <p>The list draws that as rows and the grid draws it as cells in reading
 * order, with each group starting a fresh row so a group is a visible block.
 * A drag in either view calls the same {@code move*} method here, which is why
 * a reorder made in one is already true in the other - there is nothing to
 * copy across, and therefore nothing that can disagree.
 *
 * <p>Groups do not nest. One level answers what grouping is for - collapsing a
 * dozen instances into one line - and a tree of groups would make "which row
 * does the grid break at" a question with no obvious answer.
 *
 * <h2>Reconciliation</h2>
 *
 * <p>{@code profiles.json} is an editable file, and the arrangement lives in it
 * next to the profiles rather than in a second file that could be restored from
 * a backup on its own. So the arrangement is always treated as a hint:
 * {@link #reconcile} drops ids that no longer exist, removes duplicates, and
 * appends profiles the arrangement has never seen. A layout that is empty, hand
 * broken or missing therefore degrades to the alphabetical order rather than to
 * a launcher with no instances in it.
 */
public final class ProfileLayout {

    /** Cells across in the inventory grid. Nine, as in the game's own inventory. */
    public static final int GRID_COLUMNS = 9;

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
     * <p>A group needs a colour because in the grid a group is identified by the
     * tint of its block rather than by a heading it has no room for. They are
     * assigned rather than asked for: choosing a colour is not a decision worth
     * interrupting "make a group" with, and every one of these is legible
     * against the dark panel behind it.
     */
    private static final List<String> PALETTE = List.of(
            "#3d6ea5", "#8a5a3c", "#6b4a8f", "#2d7d46",
            "#a5843d", "#a53d5c", "#3d8a8a", "#6f7d3d");

    /** A named, collapsible set of profiles. */
    public static final class Group {

        private final String id;
        private String name;
        private String color;
        private boolean collapsed;
        private final List<String> members = new ArrayList<>();

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

        public boolean collapsed() {
            return collapsed;
        }

        public void collapsed(boolean value) {
            this.collapsed = value;
        }

        /** The profile ids in this group, in the order shown. Read-only. */
        public List<String> members() {
            return List.copyOf(members);
        }

        public int size() {
            return members.size();
        }

        Json toJson() {
            Json ids = Json.array();
            members.forEach(ids::add);
            return Json.object()
                    .put("type", "group")
                    .put("id", id)
                    .put("name", name)
                    .put("color", color)
                    .put("collapsed", collapsed)
                    .put("members", ids);
        }
    }

    /**
     * One top-level row: either a bare profile or a group.
     *
     * <p>Both are top level because both can be dragged past the other. A group
     * that could only sit below every loose profile would make "put my three
     * modded packs at the top" impossible.
     */
    public static final class Entry {

        private final String profileId;
        private final Group group;

        private Entry(String profileId, Group group) {
            this.profileId = profileId;
            this.group = group;
        }

        public boolean isGroup() {
            return group != null;
        }

        /** The profile id, or null when this entry is a group. */
        public String profileId() {
            return profileId;
        }

        /** The group, or null when this entry is a profile. */
        public Group group() {
            return group;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private Mode mode = Mode.LIST;

    // ---------------------------------------------------------------- reading

    public Mode mode() {
        return mode;
    }

    public ProfileLayout mode(Mode value) {
        this.mode = value == null ? Mode.LIST : value;
        return this;
    }

    /** The top level, in the order shown. Read-only; use the move methods to change it. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Group> groups() {
        List<Group> found = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.isGroup()) {
                found.add(entry.group);
            }
        }
        return List.copyOf(found);
    }

    public Optional<Group> group(String groupId) {
        if (groupId == null) {
            return Optional.empty();
        }
        for (Entry entry : entries) {
            if (entry.isGroup() && entry.group.id.equals(groupId)) {
                return Optional.of(entry.group);
            }
        }
        return Optional.empty();
    }

    /** The group holding a profile, or empty when it sits at the top level. */
    public Optional<Group> groupOf(String profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        for (Entry entry : entries) {
            if (entry.isGroup() && entry.group.members.contains(profileId)) {
                return Optional.of(entry.group);
            }
        }
        return Optional.empty();
    }

    /**
     * Every profile id in the order both views draw them.
     *
     * <p>Collapsed groups are included: this is the arrangement, not what is on
     * screen. A view that hides collapsed members filters this itself, so that
     * collapsing a group cannot quietly change the order underneath it.
     */
    public List<String> orderedProfileIds() {
        List<String> ids = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.isGroup()) {
                ids.addAll(entry.group.members);
            } else {
                ids.add(entry.profileId);
            }
        }
        return List.copyOf(ids);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // ---------------------------------------------------------------- groups

    /** Adds a group at the end of the top level and returns it. */
    public Group createGroup(String name) {
        String safe = (name == null || name.isBlank()) ? "Group" : name.trim();
        Group group = new Group(newGroupId(), safe, PALETTE.get(groups().size() % PALETTE.size()));
        entries.add(new Entry(null, group));
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
     * Removes a group, keeping its profiles.
     *
     * <p>The members are put back at the top level in the group's own place and
     * in their own order, so dissolving a group leaves the arrangement looking
     * as much like it did as it can. Deleting the profiles as well would make an
     * undoable tidying action into a destructive one.
     */
    public void removeGroup(String groupId) {
        int index = indexOfGroup(groupId);
        if (index < 0) {
            return;
        }
        Group group = entries.get(index).group;
        entries.remove(index);
        List<String> members = new ArrayList<>(group.members);
        for (int i = 0; i < members.size(); i++) {
            entries.add(index + i, new Entry(members.get(i), null));
        }
    }

    public void renameGroup(String groupId, String name) {
        group(groupId).ifPresent(group -> group.name(name));
    }

    public void setCollapsed(String groupId, boolean collapsed) {
        group(groupId).ifPresent(group -> group.collapsed(collapsed));
    }

    // ---------------------------------------------------------------- moving

    /**
     * Puts a profile at {@code index} inside {@code groupId}, or at the top
     * level when {@code groupId} is null.
     *
     * <p>The one method behind every drag in either interface. It detaches the
     * profile first, so a move inside its own container is a reorder rather than
     * a duplicate, and the index is clamped instead of rejected - a drop past
     * the last row means "last", which is what the gesture looks like.
     */
    public void moveProfile(String profileId, String groupId, int index) {
        if (profileId == null) {
            return;
        }
        if (groupId != null && group(groupId).isEmpty()) {
            return;
        }
        detach(profileId);
        if (groupId == null) {
            entries.add(clamp(index, entries.size()), new Entry(profileId, null));
            return;
        }
        Group group = group(groupId).orElseThrow();
        group.members.add(clamp(index, group.members.size()), profileId);
    }

    /** Appends a profile to a group, or to the top level when {@code groupId} is null. */
    public void moveProfileToEnd(String profileId, String groupId) {
        moveProfile(profileId, groupId, Integer.MAX_VALUE);
    }

    /**
     * Drops {@code profileId} immediately before or after {@code targetId},
     * wherever that target currently sits.
     *
     * <p>Expressed against a neighbour rather than an index because that is what
     * a drag actually says: the pointer is over a row, above or below its
     * middle. Working out which container that row is in belongs here, not in
     * two views that would have to agree about it.
     */
    public void moveProfileBeside(String profileId, String targetId, boolean after) {
        if (profileId == null || targetId == null || profileId.equals(targetId)) {
            return;
        }
        Optional<Group> targetGroup = groupOf(targetId);
        if (targetGroup.isPresent()) {
            Group group = targetGroup.get();
            // Detach first, so an index taken before the removal cannot be off
            // by one when the profile was already above the target.
            detach(profileId);
            int at = group.members.indexOf(targetId);
            if (at < 0) {
                group.members.add(profileId);
            } else {
                group.members.add(after ? at + 1 : at, profileId);
            }
            return;
        }
        detach(profileId);
        int at = indexOfProfileEntry(targetId);
        if (at < 0) {
            entries.add(new Entry(profileId, null));
        } else {
            entries.add(after ? at + 1 : at, new Entry(profileId, null));
        }
    }

    /**
     * Moves a whole top-level entry - a loose profile or a group - beside
     * another top-level entry.
     *
     * <p>{@code entryKey} and {@code targetKey} are a group id or a profile id;
     * a group id always wins, because a profile id can never be a group id.
     */
    public void moveEntryBeside(String entryKey, String targetKey, boolean after) {
        if (entryKey == null || targetKey == null || entryKey.equals(targetKey)) {
            return;
        }
        int from = indexOfTopLevel(entryKey);
        if (from < 0) {
            return;
        }
        Entry moved = entries.remove(from);
        int at = indexOfTopLevel(targetKey);
        if (at < 0) {
            entries.add(moved);
            return;
        }
        entries.add(after ? at + 1 : at, moved);
    }

    /** Sorts everything by name: the top level, and the members of every group. */
    public void sortByName(Collection<Profile> profiles) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            names.put(profile.id(), profile.name());
        }
        Comparator<String> byProfileName = Comparator.comparing(
                id -> names.getOrDefault(id, id), String.CASE_INSENSITIVE_ORDER);

        for (Entry entry : entries) {
            if (entry.isGroup()) {
                entry.group.members.sort(byProfileName);
            }
        }
        entries.sort(Comparator.comparing(
                entry -> entry.isGroup() ? entry.group.name : names.getOrDefault(entry.profileId, ""),
                String.CASE_INSENSITIVE_ORDER));
    }

    private void detach(String profileId) {
        entries.removeIf(entry -> !entry.isGroup() && profileId.equals(entry.profileId));
        for (Entry entry : entries) {
            if (entry.isGroup()) {
                entry.group.members.remove(profileId);
            }
        }
    }

    private int indexOfTopLevel(String key) {
        int asGroup = indexOfGroup(key);
        return asGroup >= 0 ? asGroup : indexOfProfileEntry(key);
    }

    private int indexOfGroup(String groupId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.isGroup() && entry.group.id.equals(groupId)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfProfileEntry(String profileId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.isGroup() && entry.profileId.equals(profileId)) {
                return i;
            }
        }
        return -1;
    }

    private static int clamp(int index, int size) {
        if (index < 0) {
            return 0;
        }
        return Math.min(index, size);
    }

    // ---------------------------------------------------------------- reconcile

    /**
     * Brings the arrangement in line with the profiles that actually exist.
     *
     * <p>Called after every load and after every add or remove. Four things go
     * wrong and all four are repaired rather than reported: an id for a profile
     * that is gone, the same id in two places, a profile the arrangement has
     * never seen, and an arrangement that is empty. The last case is the first
     * run and the upgrade from a launcher that had no groups, and it seeds the
     * alphabetical order the interface starts from.
     *
     * @return true when something changed, so the caller knows to save
     */
    public boolean reconcile(Collection<Profile> profiles) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            names.put(profile.id(), profile.name());
        }

        boolean fresh = entries.isEmpty() && !names.isEmpty();
        boolean changed = false;

        Set<String> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (!entry.isGroup()) {
                continue;
            }
            int before = entry.group.members.size();
            entry.group.members.removeIf(id -> !names.containsKey(id) || !seen.add(id));
            changed |= entry.group.members.size() != before;
        }
        int topBefore = entries.size();
        entries.removeIf(entry -> !entry.isGroup()
                && (!names.containsKey(entry.profileId) || !seen.add(entry.profileId)));
        changed |= entries.size() != topBefore;

        // New profiles go to the top level. Alphabetically on a fresh layout,
        // and at the end otherwise: a profile created just now belongs where the
        // user will look for it, which is the bottom of the list they arranged,
        // not somewhere in the middle of it.
        List<String> missing = new ArrayList<>();
        for (String id : names.keySet()) {
            if (!seen.contains(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(Comparator.comparing(
                    id -> names.getOrDefault(id, id), String.CASE_INSENSITIVE_ORDER));
            for (String id : missing) {
                entries.add(new Entry(id, null));
            }
            changed = true;
        }

        if (fresh) {
            sortByName(profiles);
        }
        return changed;
    }

    // ---------------------------------------------------------------- grid

    /**
     * The inventory grid, row by row.
     *
     * <p>Built here rather than in the view because it is the same arrangement
     * again, only wrapped: cells fill left to right, and a group starts a new
     * row and finishes the row it ends on. That row alignment is what makes a
     * group a rectangular block on screen, which is the only way the grid can
     * show grouping at all - it has no room for headings.
     *
     * @param visible ids the caller is willing to show, e.g. after a search;
     *                null means all of them
     */
    public List<Row> grid(Set<String> visible) {
        List<Row> rows = new ArrayList<>();
        List<String> loose = new ArrayList<>();

        for (Entry entry : entries) {
            if (!entry.isGroup()) {
                if (visible == null || visible.contains(entry.profileId)) {
                    loose.add(entry.profileId);
                }
                continue;
            }
            // A group breaks the run of loose profiles, so the run is flushed
            // into whole rows before the group's own block starts.
            flush(rows, loose, null);
            List<String> members = new ArrayList<>();
            for (String id : entry.group.members) {
                if (visible == null || visible.contains(id)) {
                    members.add(id);
                }
            }
            if (members.isEmpty() && visible != null) {
                // Filtered out entirely: showing an empty band for it would be
                // a group the search says has nothing in it.
                continue;
            }
            if (entry.group.collapsed()) {
                rows.add(new Row(entry.group, List.of(), true, entry.group.members.size()));
                continue;
            }
            flush(rows, members, entry.group);
        }
        flush(rows, loose, null);
        return List.copyOf(rows);
    }

    private static void flush(List<Row> rows, List<String> pending, Group group) {
        if (pending.isEmpty()) {
            if (group != null) {
                rows.add(new Row(group, List.of(), false, 0));
            }
            return;
        }
        for (int start = 0; start < pending.size(); start += GRID_COLUMNS) {
            List<String> slice = new ArrayList<>(
                    pending.subList(start, Math.min(start + GRID_COLUMNS, pending.size())));
            rows.add(new Row(group, List.copyOf(slice), false, 0));
        }
        pending.clear();
    }

    /**
     * One row of the grid: up to {@link #GRID_COLUMNS} profiles, and the group
     * the row belongs to, or null for loose profiles.
     */
    public static final class Row {

        private final Group group;
        private final List<String> profileIds;
        private final boolean collapsedGroup;
        private final int hiddenCount;

        private Row(Group group, List<String> profileIds, boolean collapsedGroup, int hiddenCount) {
            this.group = group;
            this.profileIds = profileIds;
            this.collapsedGroup = collapsedGroup;
            this.hiddenCount = hiddenCount;
        }

        public Group group() {
            return group;
        }

        public List<String> profileIds() {
            return profileIds;
        }

        /** True for the single strip that stands in for a collapsed group. */
        public boolean isCollapsedGroup() {
            return collapsedGroup;
        }

        /** How many profiles the collapsed strip is standing in for. */
        public int hiddenCount() {
            return hiddenCount;
        }
    }

    // ---------------------------------------------------------------- persistence

    public Json toJson() {
        Json list = Json.array();
        for (Entry entry : entries) {
            if (entry.isGroup()) {
                list.add(entry.group.toJson());
            } else {
                list.add(Json.object().put("type", "profile").put("id", entry.profileId));
            }
        }
        return Json.object().put("mode", mode.id()).put("entries", list);
    }

    public static ProfileLayout fromJson(Json json) {
        ProfileLayout layout = new ProfileLayout();
        if (json == null || !json.isObject()) {
            return layout;
        }
        layout.mode = Mode.parse(json.get("mode").asString(Mode.LIST.id()));

        Set<String> groupIds = new LinkedHashSet<>();
        for (Json entry : json.get("entries").elements()) {
            String type = entry.get("type").asString("profile");
            if ("group".equalsIgnoreCase(type)) {
                String id = entry.get("id").asString(null);
                if (id == null || !groupIds.add(id)) {
                    continue;
                }
                Group group = new Group(id,
                        entry.get("name").asString(id),
                        PALETTE.get(groupIds.size() % PALETTE.size()));
                group.color(entry.get("color").asString(null));
                group.collapsed(entry.get("collapsed").asBool(false));
                for (Json member : entry.get("members").elements()) {
                    String memberId = member.asString(null);
                    if (memberId != null) {
                        group.members.add(memberId);
                    }
                }
                layout.entries.add(new Entry(null, group));
            } else {
                String id = entry.get("id").asString(null);
                if (id != null) {
                    layout.entries.add(new Entry(id, null));
                }
            }
        }
        return layout;
    }
}
