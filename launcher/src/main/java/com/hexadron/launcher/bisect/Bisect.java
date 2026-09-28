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

package com.hexadron.launcher.bisect;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.json.JsonException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the mod that causes a problem by halving the mod set.
 *
 * <h2>How</h2>
 *
 * <p>The suspects start as every mod that was switched on. Each step switches
 * on one half (and the mods that half needs) and asks whether the problem
 * still occurs. If it does, the culprit is in that half; if not, the other
 * half is tried. About log2(n) launches find one mod among n: eight for two
 * hundred.
 *
 * <p>When neither half shows the problem on its own, two mods conflict, one in
 * each half. The search then keeps one half switched on as context and halves
 * the other until one mod is left, then does the same the other way round to
 * find its partner.
 *
 * <h2>Dependencies</h2>
 *
 * <p>A mod never runs without the mods it requires: every tested set is closed
 * over {@link Graph#deps()}. So a library is only ever off when nothing that is
 * on needs it, and a result names the mod together with the libraries that
 * came with it.
 *
 * <p>Pure and immutable: the launcher saves each state to disk, so a search
 * survives a launcher restart, and the self-check runs whole searches against a
 * simulated culprit.
 */
public final class Bisect {

    /** The format of the saved state. */
    public static final int FORMAT = 1;

    /** Which mods need which, by file name. */
    public record Graph(Map<String, Set<String>> deps) {
        public Graph {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            deps.forEach((file, needs) -> copy.put(file, Set.copyOf(needs)));
            deps = Collections.unmodifiableMap(copy);
        }

        public static Graph none() {
            return new Graph(Map.of());
        }

        /** This graph with more requirements added: the ones a search learned from its crashes. */
        public Graph plus(Map<String, Set<String>> more) {
            Map<String, Set<String>> merged = new LinkedHashMap<>();
            deps.forEach((file, needs) -> merged.put(file, new LinkedHashSet<>(needs)));
            more.forEach((file, needs) -> merged.computeIfAbsent(file, key -> new LinkedHashSet<>()).addAll(needs));
            return new Graph(merged);
        }

        /** The files plus everything they need, directly or not, within {@code allowed}. */
        public Set<String> closure(Collection<String> files, Collection<String> allowed) {
            Set<String> result = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>(files);
            while (!queue.isEmpty()) {
                String file = queue.poll();
                if (!allowed.contains(file) || !result.add(file)) {
                    continue;
                }
                queue.addAll(deps.getOrDefault(file, Set.of()));
            }
            return result;
        }
    }

    /** What the search is looking for right now. */
    public enum Mode {
        /** One mod that causes the problem alone. */
        SINGLE,
        /** The first mod of a conflicting pair, with the other half on as context. */
        PAIR_FIRST,
        /** Its partner, with the first one on as context. */
        PAIR_SECOND,
        /**
         * The mod found needs others, and every launch with it had them on
         * too: one of those may be the cause. They are searched on their own;
         * {@code context} holds the mod found first and then the mods already
         * checked this way, and the first of it is the answer when none of
         * them shows the problem.
         */
        LIBRARY
    }

    /** Which half of the suspects the current launch has on. */
    public enum Half {
        FIRST,
        SECOND
    }

    /**
     * One point in a search.
     *
     * @param original the mods that were on when the search started; restored at the end
     * @param suspects the mods the culprit is still among
     * @param context  mods kept on in every launch (the other half, in a pair search)
     * @param result   empty until found; one file, or two for a pair
     * @param step     the launch number, from 1
     */
    public record State(List<String> original, List<String> suspects, List<String> context,
                        Mode mode, Half half, List<String> result, int step) {

        public State {
            original = List.copyOf(original);
            suspects = List.copyOf(suspects);
            context = List.copyOf(context);
            result = List.copyOf(result);
        }

        public boolean isDone() {
            return !result.isEmpty();
        }

        /** The half of the suspects this launch has on. */
        public List<String> testedHalf() {
            int split = (suspects.size() + 1) / 2;
            return half == Half.FIRST ? suspects.subList(0, split) : suspects.subList(split, suspects.size());
        }

        private List<String> otherHalf() {
            int split = (suspects.size() + 1) / 2;
            return half == Half.FIRST ? suspects.subList(split, suspects.size()) : suspects.subList(0, split);
        }
    }

    private Bisect() {
    }

    /**
     * A new search over the mods that are on.
     *
     * @throws IllegalArgumentException for fewer than two mods, where there is nothing to search
     */
    public static State start(Collection<String> enabled) {
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(enabled));
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        if (sorted.size() < 2) {
            throw new IllegalArgumentException("fewer than two mods");
        }
        return new State(sorted, sorted, List.of(), Mode.SINGLE, Half.FIRST, List.of(), 1);
    }

    /** The mods to switch on for the current launch. Every other original mod is off. */
    public static Set<String> enabledFor(State state, Graph graph) {
        if (state.isDone()) {
            return new LinkedHashSet<>(state.original());
        }
        List<String> on = new ArrayList<>(state.testedHalf());
        if (state.mode() != Mode.LIBRARY) {
            // In a library check the context is the mod found, which has to stay off.
            on.addAll(state.context());
        }
        return graph.closure(on, state.original());
    }

    /** The next state after a launch, given whether the problem occurred, for mods that need nothing. */
    public static State next(State state, boolean problem) {
        return next(state, problem, Graph.none());
    }

    /**
     * The next state after a launch, given whether the problem occurred.
     *
     * @param graph which mod needs which: a mod found is checked against the
     *              mods it needs before it is named, because it never ran
     *              without them
     */
    public static State next(State state, boolean problem, Graph graph) {
        if (state.isDone()) {
            return state;
        }
        if (state.mode() == Mode.LIBRARY) {
            List<String> tested = state.testedHalf();
            int step = state.step() + 1;
            if (problem) {
                return settle(state.original(), tested, state.context(), Mode.LIBRARY, step, graph);
            }
            if (state.half() == Half.FIRST) {
                return new State(state.original(), state.suspects(), state.context(), Mode.LIBRARY, Half.SECOND,
                        List.of(), step);
            }
            // Neither half of what it needs shows the problem: it is the mod itself.
            return new State(state.original(), List.of(state.context().get(0)), state.context(), Mode.LIBRARY,
                    Half.FIRST, List.of(state.context().get(0)), step);
        }
        List<String> tested = state.testedHalf();
        List<String> other = state.otherHalf();
        int step = state.step() + 1;
        if (problem) {
            return settle(state.original(), tested, state.context(), state.mode(), step, graph);
        }
        if (state.mode() == Mode.SINGLE && state.half() == Half.FIRST) {
            // The first half is clean; now the second half on its own.
            return new State(state.original(), state.suspects(), List.of(), Mode.SINGLE, Half.SECOND,
                    List.of(), step);
        }
        if (state.mode() == Mode.SINGLE) {
            // Neither half alone: a pair, one mod in each. Find the one in the
            // first half, with the whole second half on to trigger it.
            List<String> first = state.suspects().subList(0, (state.suspects().size() + 1) / 2);
            List<String> second = state.suspects().subList(first.size(), state.suspects().size());
            return settle(state.original(), first, second, Mode.PAIR_FIRST, step, graph);
        }
        // In a pair search the tested half was clean with the context on, so
        // the mod is in the other half.
        return settle(state.original(), other, state.context(), state.mode(), step, graph);
    }

    /** Narrows to {@code suspects}, finishing or moving to the partner search when one is left. */
    private static State settle(List<String> original, List<String> suspects, List<String> context,
                                Mode mode, int step, Graph graph) {
        if (suspects.size() > 1) {
            return new State(original, suspects, context, mode, Half.FIRST, List.of(), step);
        }
        String found = suspects.get(0);
        return switch (mode) {
            case SINGLE -> checkNeeds(original, found, List.of(), step, graph);
            case LIBRARY -> checkNeeds(original, found, context, step, graph);
            case PAIR_FIRST -> settle(original, context, List.of(found), Mode.PAIR_SECOND, step, graph);
            case PAIR_SECOND -> new State(original, suspects, context, mode, Half.FIRST,
                    List.of(context.get(0), found), step);
        };
    }

    /**
     * A mod was found. When it needs other mods of the search, it never ran
     * without them, and a library it needs is as likely the cause: those are
     * searched next, the mod found kept as the answer should none of them
     * show the problem. A library found that way is checked the same way.
     */
    private static State checkNeeds(List<String> original, String found, List<String> checked, int step,
                                    Graph graph) {
        List<String> needs = new ArrayList<>(graph.closure(List.of(found), original));
        needs.remove(found);
        needs.removeAll(checked);
        if (needs.isEmpty()) {
            return new State(original, List.of(found), checked.isEmpty() ? List.of() : checked,
                    checked.isEmpty() ? Mode.SINGLE : Mode.LIBRARY, Half.FIRST, List.of(found), step);
        }
        needs.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> context = new ArrayList<>();
        context.add(found);
        context.addAll(checked);
        return new State(original, needs, context, Mode.LIBRARY, Half.FIRST, List.of(), step);
    }

    /** About how many more launches the search needs, for the progress line. */
    public static int remaining(State state) {
        if (state.isDone()) {
            return 0;
        }
        int n = state.suspects().size();
        int launches = 32 - Integer.numberOfLeadingZeros(Math.max(1, n - 1));
        if (state.mode() == Mode.LIBRARY) {
            // Both halves of what it needs may have to be tried.
            launches += 1;
        }
        if (state.mode() == Mode.PAIR_FIRST) {
            int partner = state.context().size();
            launches += 32 - Integer.numberOfLeadingZeros(Math.max(1, partner - 1));
        }
        return Math.max(1, launches);
    }

    /** Launches needed for a set of {@code n} mods with a single culprit. */
    public static int estimate(int n) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, n - 1)));
    }

    // ------------------------------------------------------------------ saving

    public static Json toJson(State state, String profileId) {
        Json json = Json.object()
                .put("format", FORMAT)
                .put("profile", profileId)
                .put("mode", state.mode().name())
                .put("half", state.half().name())
                .put("step", state.step());
        json.put("original", array(state.original()));
        json.put("suspects", array(state.suspects()));
        json.put("context", array(state.context()));
        json.put("result", array(state.result()));
        return json;
    }

    /** Reads a saved state; throws on anything inconsistent rather than guessing. */
    public static State fromJson(Json json) {
        if (json.get("format").asInt(-1) != FORMAT) {
            throw new JsonException("unknown search format");
        }
        List<String> original = strings(json.get("original"));
        List<String> suspects = strings(json.get("suspects"));
        List<String> context = strings(json.get("context"));
        List<String> result = strings(json.get("result"));
        if (original.size() < 2 || !original.containsAll(suspects) || !original.containsAll(context)
                || !original.containsAll(result) || (suspects.isEmpty() && result.isEmpty())) {
            throw new JsonException("inconsistent search state");
        }
        Mode mode;
        Half half;
        try {
            mode = Mode.valueOf(json.get("mode").asString(""));
            half = Half.valueOf(json.get("half").asString(""));
        } catch (IllegalArgumentException e) {
            throw new JsonException("bad search mode");
        }
        return new State(original, suspects, context, mode, half, result,
                Math.max(1, json.get("step").asInt(1)));
    }

    private static Json array(List<String> values) {
        Json array = Json.array();
        values.forEach(array::add);
        return array;
    }

    private static List<String> strings(Json node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (Json element : node.elements()) {
                String value = element.asString(null);
                // A file name in the mods folder, nothing that walks out of it.
                if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                        || value.equals("..")) {
                    throw new JsonException("bad file name in search state");
                }
                values.add(value);
            }
        }
        return values;
    }
}
