package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * Which leaf a value is, is read off the value, and where that leaf stands is the set's: one leaf
 * is the first case of one sum and the second of another (issue #77).
 */
class ALeafHasItsPlaceInEachSumItIsInTest {

    private static final String SHARED = """
            module shared

            data A
            data B
            data C

            data AB = A | B
            data BA = B | A
            data ABC = C | A | B

            behavior orderedAB : (xs: List<AB>) -> Set<AB>
            let orderedAB (xs) = Set.fromList(xs)

            behavior orderedBA : (xs: List<BA>) -> Set<BA>
            let orderedBA (xs) = Set.fromList(xs)

            behavior echoABC : (x: ABC) -> ABC
            let echoABC (x) = x

            behavior rank : (x: ABC) -> Int
            let rank (x) = match x with
                | A -> 1
                | B -> 2
                | C -> 3

            behavior merged : (x: AB) -> Int
            let merged (x) = match x with
                | A | B -> 7
            """;

    @Test
    void aLeafSharedByTwoSumsIsPlacedByEach() {
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(SHARED))));

        // A is the first case of one sum and the second of another, and a set of either is written
        // by the names of its members, and is the same leaves in both.
        assertThat(answerOf(module, "shared.orderedAB", "[[\"B\",\"A\",\"B\"]]")).isEqualTo("{\"value\":[\"A\",\"B\"]}");
        assertThat(answerOf(module, "shared.orderedBA", "[[\"A\",\"B\",\"A\"]]")).isEqualTo("{\"value\":[\"A\",\"B\"]}");
        assertThat(answerOf(module, "shared.echoABC", "[\"A\"]")).isEqualTo("{\"value\":\"A\"}");
        assertThat(answerOf(module, "shared.echoABC", "[\"C\"]")).isEqualTo("{\"value\":\"C\"}");
    }

    @Test
    void aMatchTakesTheArmOfEachLeafWhateverItsPlace() {
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(SHARED))));

        assertThat(answerOf(module, "shared.rank", "[\"A\"]")).isEqualTo("{\"value\":1}");
        assertThat(answerOf(module, "shared.rank", "[\"B\"]")).isEqualTo("{\"value\":2}");
        assertThat(answerOf(module, "shared.rank", "[\"C\"]")).isEqualTo("{\"value\":3}");
        assertThat(answerOf(module, "shared.merged", "[\"B\"]")).isEqualTo("{\"value\":7}");
    }

    /** A sum of ninety-six leaves tells the first, a middle one and the last by one lookup. */
    @Test
    void aWideSumIsToldLeafByLeaf() {
        int leaves = 96;
        String names = IntStream.range(0, leaves).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
        String declared = IntStream.range(0, leaves).mapToObj(i -> "data L" + i).collect(Collectors.joining("\n"));
        String arms = IntStream.range(0, leaves).mapToObj(i -> "    | L" + i + " -> " + i)
                .collect(Collectors.joining("\n"));
        String source = "module wide\n\n" + declared + "\n\ndata Wide = " + names + "\n\n"
                + "behavior echo : (xs: List<Wide>) -> List<Wide>\nlet echo (xs) = xs\n\n"
                + "behavior place : (x: Wide) -> Int\nlet place (x) = match x with\n" + arms + "\n";
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));

        for (int leaf : new int[] {0, 1, 47, 94, 95}) {
            assertThat(answerOf(module, "wide.place", "[\"L" + leaf + "\"]"))
                    .isEqualTo("{\"value\":" + leaf + "}");
            assertThat(answerOf(module, "wide.echo", "[[\"L" + leaf + "\"]]"))
                    .isEqualTo("{\"value\":[\"L" + leaf + "\"]}");
        }
    }

    /**
     * An arm over many leaves is told by one lookup in a table of them, and over few by comparing,
     * which a call into the runtime costs more than. Either way each leaf takes the arm it is in.
     */
    @Test
    void anArmOverManyLeavesTakesEachOfThemAndNoOther() {
        int leaves = 48;
        int covered = 40;
        String declared = IntStream.range(0, leaves).mapToObj(i -> "data L" + i).collect(Collectors.joining("\n"));
        String names = IntStream.range(0, leaves).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
        String first = IntStream.range(0, covered).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
        String rest = IntStream.range(covered, leaves).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
        String source = "module many\n\n" + declared + "\n\ndata W = " + names + "\n\n"
                + "behavior arm : (x: W) -> Int\nlet arm (x) = match x with\n    | " + first + " -> 1\n    | "
                + rest + " -> 2\n";
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));

        for (int leaf = 0; leaf < leaves; leaf++) {
            assertThat(answerOf(module, "many.arm", "[\"L" + leaf + "\"]"))
                    .isEqualTo("{\"value\":" + (leaf < covered ? 1 : 2) + "}");
        }
    }

    /** Scalars are told apart by a call each where compared one by one, and by one lookup in a table. */
    @Test
    void anArmOverScalarsTakesEachOfThemAndNoOther() {
        String source = """
                module scalars

                let pick (n: Int): Int | String | Bool =
                    if n == 0 then 5 else if n == 1 then "s" else true

                behavior kind : (n: Int) -> Int

                let kind (n) =
                    match pick(n) with
                        | Int | String -> 1
                        | Bool -> 2
                """;
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));

        assertThat(answerOf(module, "scalars.kind", "[0]")).isEqualTo("{\"value\":1}");
        assertThat(answerOf(module, "scalars.kind", "[1]")).isEqualTo("{\"value\":1}");
        assertThat(answerOf(module, "scalars.kind", "[2]")).isEqualTo("{\"value\":2}");
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
