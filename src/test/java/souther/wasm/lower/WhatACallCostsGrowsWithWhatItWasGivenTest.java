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

                behavior tallied : (xs: List<String>) -> Int

                let tallied (xs) = Map.size(
                    List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))
                """))));
    }
}
