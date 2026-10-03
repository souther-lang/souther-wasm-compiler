package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.compiler.abort.AbortKind;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.FailureCause;
import souther.wasm.abi.RuntimeAbi;

/**
 * What a body works out on the way to its answer is not made a cell, and what it writes down is
 * one cell however often it is reached.
 *
 * <p>Arithmetic over whole numbers is worked out on the numbers and a condition on what it decides,
 * so {@code x * 2 + 1 > n} makes nothing. A literal is a cell in static memory. A walk adds what its
 * step wrote as {@code acc ++ [x]} without a list of one around it, and grows a map in place. Each
 * is asked two ways: that the answers are what the operators say, at the edges of the range too,
 * and that what a call takes from the arena does not grow with how many steps made nothing.
 */
class WhatABodyWorksOutOnTheWayIsNoCellTest {

    private static final String PROGRAM = """
            module working

            behavior worked : (a: Int, b: Int, c: Int) -> Int

            let worked (a, b, c) = a * b + c - (a - c) * -b

            behavior decided : (a: Int, b: Int, c: Int) -> Bool

            let decided (a, b, c) = a * 2 + 1 > b && (c <= a || -c == b) || a == b

            behavior chosen : (a: Int, b: Int) -> Int

            let chosen (a, b) = if a < b then a - b else if a == b then 0 else b - a

            behavior kept : (xs: List<Int>) -> List<Int>

            let kept (xs) = List.filter(x -> x * 2 + 1 > 0, xs)

            behavior counted : (xs: List<Int>) -> Int

            let counted (xs) = List.length(xs)

            behavior doubled : (xs: List<Int>) -> List<Int>

            let doubled (xs) = List.map(x -> x * 2, xs)

            behavior same : (xs: List<Int>) -> List<Int>

            let same (xs) = xs

            behavior tallied : (xs: List<Int>) -> Map<String, Int>

            let tallied (xs) = List.fold(
                (acc, x) -> Map.insert(String.fromInt(x), x, acc), Map.empty, xs)
            """;

    @Test
    void answersWhatTheOperatorsSayAcrossTheRange() {
        Running module = compiled();
        Random random = new Random(1003);
        // As far out as the answers stay inside the range: a product of two, and a sum of two of those.
        long[] edges = {0, 1, -1, 2, -2, 1L << 30, -(1L << 30), 1000, -1000};

        for (int round = 0; round < 300; round++) {
            long a = round < 81 ? edges[round % 9] : random.nextInt(2001) - 1000;
            long b = round < 81 ? edges[round / 9] : random.nextInt(2001) - 1000;
            long c = random.nextInt(21) - 10;
            String arguments = "[" + a + "," + b + "," + c + "]";

            assertThat(answerOf(module, "working.worked", arguments)).describedAs(arguments)
                    .isEqualTo(value(Long.toString(a * b + c - (a - c) * -b)));
            assertThat(answerOf(module, "working.decided", arguments)).describedAs(arguments)
                    .isEqualTo(value(Boolean.toString(
                            a * 2 + 1 > b && (c <= a || -c == b) || a == b)));
            assertThat(answerOf(module, "working.chosen", "[" + a + "," + b + "]"))
                    .describedAs(arguments)
                    .isEqualTo(value(Long.toString(a < b ? a - b : a == b ? 0 : b - a)));
        }
    }

    @Test
    void comparesTheEndsOfTheRangeAsSignedNumbers() {
        Running module = compiled();

        // The least and the greatest, which a comparison without sign would put the other way
        // round. Each answer is the one branch that does not leave the range.
        assertThat(answerOf(module, "working.chosen", "[-9223372036854775808,-1]"))
                .isEqualTo(value("-9223372036854775807"));
        assertThat(answerOf(module, "working.chosen", "[9223372036854775807,1]"))
                .isEqualTo(value("-9223372036854775806"));
        assertThat(answerOf(module, "working.decided", "[-4611686018427387904,0,0]"))
                .isEqualTo(value("false"));
    }

    @Test
    void endsTheCallWhereAPartOfTheWayIsNotANumberAnIntHolds() {
        Running module = compiled();

        // The whole would fit if the part in the middle had wrapped, and it does not wrap.
        assertThat(abortOf(module, "working.worked", "[4611686018427387904,4,0]"))
                .contains(new FailureCause.Language(AbortKind.REQUIRED_FORM_HAS_NO_PLACE));
        assertThat(abortOf(module, "working.decided", "[9223372036854775807,0,0]"))
                .contains(new FailureCause.Language(AbortKind.REQUIRED_FORM_HAS_NO_PLACE));
    }

    @Test
    void decidesWhichElementsToKeepWithoutMakingACellPerElement() {
        Running module = compiled();

        // Every element is refused, so the walk keeps nothing and writes the same answer whatever
        // it was given. What a call takes from the arena past reading what it was given is then
        // what deciding took: a cell for the doubled number, one for the literal, one for the
        // answer of the comparison — or none of them.
        String fewer = negatives(500);
        String more = negatives(2000);
        long overFewer = taken(module, "working.kept", fewer) - taken(module, "working.counted", fewer);
        long overMore = taken(module, "working.kept", more) - taken(module, "working.counted", more);

        assertThat(overMore - overFewer).isLessThan(64);
    }

    @Test
    void addsWhatAStepWritesWithoutAListOfOneAroundIt() {
        Running module = compiled();

        // What doubling takes past handing the list back is the doubled numbers, the list they are
        // built in and the answer. A list of one per element on top of that is sixteen bytes more
        // per element.
        int many = 2000;
        String given = "[[" + IntStream.range(0, many).mapToObj(String::valueOf)
                .collect(Collectors.joining(",")) + "]]";
        long over = taken(module, "working.doubled", given) - taken(module, "working.same", given);

        // A cell per doubled number, and the builder and the list it seals into, which hold a
        // pointer per element and at worst twice that as the builder grows.
        assertThat(over).isLessThan((16L + 4 * 3 + 8) * many);
    }

    @Test
    void growsAMapInPlaceRatherThanACopyPerEntry() {
        Running module = compiled();

        String fewer = "[[" + IntStream.range(0, 300).mapToObj(String::valueOf)
                .collect(Collectors.joining(",")) + "]]";
        String more = "[[" + IntStream.range(0, 1200).mapToObj(String::valueOf)
                .collect(Collectors.joining(",")) + "]]";

        // Four times as many entries is about four times the room for a map grown in place, and
        // sixteen times for a copy per entry.
        double grew = (double) taken(module, "working.tallied", more)
                / taken(module, "working.tallied", fewer);
        assertThat(grew).isLessThan(6.0);

        assertThat(answerOf(module, "working.tallied", "[[3,1,2,3,10]]"))
                .isEqualTo(value("{\"1\":1,\"10\":10,\"2\":2,\"3\":3}"));
    }

    private static String negatives(int many) {
        return "[[" + IntStream.range(0, many).mapToObj(i -> String.valueOf(-1 - i))
                .collect(Collectors.joining(",")) + "]]";
    }

    /** How much of the arena a call took, the arguments it was handed included. */
    private static long taken(Running module, String export, String arguments) {
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(arguments);
        module.callWithString(export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        long used = module.call(RuntimeAbi.ALLOC_MARK) - (long) mark;
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return used;
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(PROGRAM))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }

    private static Optional<FailureCause> abortOf(Running module, String export, String arguments) {
        int snapshot = module.call(RuntimeAbi.FAILURE_GENERATION);
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        try {
            answerOf(module, export, arguments);
            throw new AssertionError(export + " answered " + arguments + " rather than ending");
        } catch (ChicoryException trapped) {
            var record = module.failureRecord();
            module.call(RuntimeAbi.ALLOC_RESET, mark);
            return record.describesTrapAfter(snapshot) ? record.cause() : Optional.empty();
        }
    }
}
