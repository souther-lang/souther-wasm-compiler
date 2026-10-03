package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * How a call's cost grows with how much it was given.
 *
 * <p>Not how long one takes — that is the machine's answer and a different one every time. What is
 * asked here is the shape of the growth, which is the program's: twice as much to read should cost
 * about twice as much, and a step that asks every part about every other part costs four times as
 * much instead. Three of these did, and every test passed while they did, because a test that hands
 * over three of something cannot tell the two apart.
 *
 * <p>So the bound is loose. It is not there to say a call is fast; it is there to say that reading
 * an object, sorting a list and settling a set have not gone back to asking every pair.
 */
class WhatACallCostsGrowsWithWhatItWasGivenTest {

    /**
     * How much more twice as much may cost.
     *
     * <p>Two would be the shape with nothing else in it, and merging runs adds a little on top of
     * that. Four is what asking every pair costs, so anything under it separates the two — and the
     * room between leaves a slow machine, a busy one, and a run that stopped to collect somewhere
     * to be without saying the program changed.
     */
    private static final double NOT_EVERY_PAIR = 3.0;

    /**
     * How much more room four times as much may take, for a call whose cost is the room it takes.
     *
     * <p>Room is counted and not timed, so nothing but the program moves it: a call keeping one copy
     * of what it holds takes four times as much, one keeping a tree of it about four and a half, and
     * one copying all of it at every change sixteen. The bound sits between.
     */
    private static final double ROOM_NOT_EVERY_PAIR = 8.0;

    /** Enough that the difference between the two shapes is larger than the noise. */
    private static final int SMALLER = 400;
    private static final int LARGER = 1600;

    @Test
    void readsAnObjectByAboutAsMuchAgainForTwiceAsManyMembers() {
        Running module = compiled();

        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.members",
                object(SMALLER), object(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    @Test
    void sortsAListByAboutAsMuchAgainForTwiceAsManyElements() {
        Running module = compiled();

        // Descending, which is the order a sort that moves one element at a time is worst at.
        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.ordered",
                descending(SMALLER), descending(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    @Test
    void settlesASetByAboutAsMuchAgainForTwiceAsManyMembers() {
        Running module = compiled();

        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.members2",
                descending(SMALLER), descending(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    @Test
    void makesASetOutOfAListByAboutAsMuchAgainForTwiceAsManyElements() {
        Running module = compiled();

        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.gathered",
                descending(SMALLER), descending(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    @Test
    void putsTwoSetsTogetherByAboutAsMuchAgainForTwiceAsManyMembers() {
        Running module = compiled();

        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.together",
                twoSets(SMALLER), twoSets(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    @Test
    void makesAMapOutOfPairsByAboutAsMuchAgainForTwiceAsManyPairs() {
        Running module = compiled();

        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.keyed",
                descending(SMALLER), descending(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    @Test
    void growsAMapOutOfKeysInDescendingOrderByAboutAsMuchAgainForTwiceAsMany() {
        Running module = compiled();

        // Each key comes before every key already put in, which is the order a map kept in its own
        // order while it grows is worst at: every entry moves for every one put in.
        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.tallied",
                descendingKeys(SMALLER), descendingKeys(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    @Test
    void growsAMapKeyedByMapsByAboutAsMuchAgainForTwiceAsMany() {
        Running module = compiled();

        // Every key is a map of one entry and every one holds as many entries as the others, so a
        // hash that read how many a map holds and not what it holds would be one hash for all of
        // them, and every key put in would be compared with every key before it.
        assertThat(howMuchMoreForFourTimesAsMuch(module, "growing.nested",
                descending(SMALLER), descending(LARGER)))
                .isLessThan(NOT_EVERY_PAIR * NOT_EVERY_PAIR);
    }

    /**
     * A collection a walk changes one member at a time where the compiler cannot see that nothing
     * else holds it — inside a pair, as `List.drop`, `List.distinct` and `List.partition` hold
     * theirs, or taken out — is a new collection each time, and the one it was made from
     * is still there. Each of these copied every member each time, and `List.drop` over sixty-four
     * thousand elements ran out of memory where the JVM answers.
     */
    @Test
    void changesACollectionOneMemberAtATimeInAboutAsMuchRoomAgainForTwiceAsMany() {
        Running module = compiled();

        // One for each way a member goes in or out: a list joined on inside a pair, a set's member
        // put in and taken out, a map's entry put in outside a walk that grows only the map, and
        // taken out. What copying every member costs is room, so room is what is measured: the
        // same on every run and every machine, where a clock is not. Copying came to fourteen to
        // fifteen times the room for four times as many, and these come to four to under five.
        for (String export : List.of("growing.dropped", "growing.inserted", "growing.removed",
                "growing.paired", "growing.unkeyed")) {
            assertThat(howMuchMoreRoomForFourTimesAsMuch(module, export,
                    descending(SMALLER), descending(LARGER)))
                    .describedAs(export)
                    .isLessThan(ROOM_NOT_EVERY_PAIR);
        }
    }

    /**
     * How many times as long the larger of two takes.
     *
     * <p>The quickest of several runs rather than the average of them: what is wanted is what the
     * work costs, and anything above the quickest is something else the machine was doing.
     */
    private static double howMuchMoreForFourTimesAsMuch(
            Running module, String export, String smaller, String larger) {
        double first = quickest(module, export, smaller);
        double then = quickest(module, export, larger);
        return then / first;
    }

    /**
     * How many times as much room the larger of two takes: what the arena handed out over the call,
     * which is the same every run and on every machine.
     */
    private static double howMuchMoreRoomForFourTimesAsMuch(
            Running module, String export, String smaller, String larger) {
        return (double) room(module, export, larger) / room(module, export, smaller);
    }

    private static long room(Running module, String export, String arguments) {
        byte[] utf8 = arguments.getBytes(StandardCharsets.UTF_8);
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int staged = module.staged(arguments);
        int before = module.call(RuntimeAbi.ALLOC_MARK);
        module.callWithString(export, staged, utf8.length);
        int after = module.call(RuntimeAbi.ALLOC_MARK);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return Integer.toUnsignedLong(after) - Integer.toUnsignedLong(before);
    }

    private static double quickest(Running module, String export, String arguments) {
        byte[] utf8 = arguments.getBytes(StandardCharsets.UTF_8);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            int mark = module.call(RuntimeAbi.ALLOC_MARK);
            long began = System.nanoTime();
            long[] answer = module.callWithString(export, module.staged(arguments), utf8.length);
            module.read((int) answer[0], (int) answer[1]);
            best = Math.min(best, System.nanoTime() - began);
            module.call(RuntimeAbi.ALLOC_RESET, mark);
        }
        return best;
    }

    private static String object(int held) {
        List<String> members = new ArrayList<>();
        for (int i = held; i > 0; i--) {
            members.add("\"k" + i + "\":" + i);
        }
        return "[{" + String.join(",", members) + "}]";
    }

    /** Two sets, of the even numbers and of the odd ones, so no member of one is in the other. */
    private static String twoSets(int held) {
        return "[[" + IntStream.range(0, held).map(i -> 2 * i)
                .mapToObj(String::valueOf).collect(Collectors.joining(",")) + "],["
                + IntStream.range(0, held).map(i -> 2 * i + 1)
                .mapToObj(String::valueOf).collect(Collectors.joining(",")) + "]]";
    }

    private static String descendingKeys(int held) {
        return "[[" + IntStream.range(0, held).mapToObj(i -> "\"k" + String.format("%06d", held - i) + "\"")
                .collect(Collectors.joining(",")) + "]]";
    }

    private static String descending(int held) {
        return "[[" + IntStream.range(0, held).map(i -> held - i)
                .mapToObj(String::valueOf).collect(Collectors.joining(",")) + "]]";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module growing

                behavior members : (m: Map<String, Int>) -> Int

                let members (m) = Map.size(m)

                behavior ordered : (xs: List<Int>) -> Int

                let ordered (xs) = Option.withDefault(0, List.get(0, List.sort(xs)))

                behavior members2 : (xs: Set<Int>) -> Int

                let members2 (xs) = Set.size(xs)

                behavior gathered : (xs: List<Int>) -> Int

                let gathered (xs) = Set.size(Set.fromList(xs))

                behavior together : (a: Set<Int>, b: Set<Int>) -> Int

                let together (a, b) = Set.size(Set.union(a, b))

                behavior keyed : (xs: List<Int>) -> Int

                let keyed (xs) = Map.size(Map.fromList(List.map(x -> (String.fromInt(x), x), xs)))

                behavior nested : (xs: List<Int>) -> Int

                let nested (xs) = Map.size(
                    List.fold((acc, x) -> Map.insert(Map.singleton(x, x), x, acc), Map.empty, xs))

                behavior tallied : (xs: List<String>) -> Int

                let tallied (xs) = Map.size(
                    List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

                behavior dropped : (xs: List<Int>) -> Int

                let dropped (xs) = List.length(List.drop(1, xs))

                behavior paired : (xs: List<Int>) -> Int

                let paired (xs) = {
                    let (n, m) = List.fold((acc, x) -> {
                        let (i, held) = acc
                        (i + 1, Map.insert(x, i, held))
                    }, (0, Map.empty), xs)
                    n + Map.size(m)
                }

                behavior inserted : (xs: List<Int>) -> Int

                let inserted (xs) = Set.size(List.fold((s, x) -> Set.insert(x, s), Set.empty, xs))

                behavior removed : (xs: List<Int>) -> Int

                let removed (xs) = Set.size(
                    List.fold((s, x) -> Set.remove(x, s), Set.fromList(xs), xs))

                behavior unkeyed : (xs: List<Int>) -> Int

                let unkeyed (xs) = Map.size(List.fold((m, x) -> Map.remove(x, m),
                    Map.fromList(List.map(x -> (x, x), xs)), xs))
                """))));
    }
}
