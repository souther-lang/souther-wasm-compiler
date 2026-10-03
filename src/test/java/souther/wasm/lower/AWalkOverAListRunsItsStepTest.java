package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * The combinators a program writes over a list, which are folds over what the library declares.
 *
 * <p>A block written where a value goes leaves the body it was written in, so what it reads from
 * there travels with it. A fold that grows a list is one walk with one answer, and is written as
 * one rather than as a list built and thrown away per element.
 */
class AWalkOverAListRunsItsStepTest {

    @Test
    void answersWhatTheStepMadeOfEachElement() {
        Running module = compiled("""
                module counting

                behavior doubled : (xs: List<Int>) -> List<Int>

                let doubled (xs) = List.map(x -> x + x, xs)
                """);

        assertThat(answerOf(module, "counting.doubled", "[[1,2,3]]"))
                .isEqualTo("{\"value\":[2,4,6]}");
        assertThat(answerOf(module, "counting.doubled", "[[]]")).isEqualTo("{\"value\":[]}");
    }

    @Test
    void walksAListLongerThanTheStackIsDeep() {
        // List.fold is written as a recursion over the list, which would take a frame per element
        // if each step were a call. A call that answers for its caller goes back to the top of the
        // body instead, so the walk runs in one frame however long the list is.
        Running module = compiled("""
                module counting

                behavior total : (xs: List<Int>) -> Int

                let total (xs) = List.fold((acc, x) -> acc + x, 0, xs)
                """);
        int many = 20_000;
        String xs = java.util.stream.IntStream.range(0, many)
                .mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(",", "[[", "]]"));

        assertThat(answerOf(module, "counting.total", xs))
                .isEqualTo("{\"value\":" + ((long) many * (many - 1) / 2) + "}");
    }

    @Test
    void goesBackToTheTopOfARecursionAProgramWrote() {
        Running module = compiled("""
                module counting

                behavior down : (n: Int, acc: Int) -> Int

                partial let countDown (n: Int, acc: Int): Int =
                    if n == 0 then acc else countDown(n - 1, acc + n)

                let down (n, acc) = countDown(n, acc)
                """);

        assertThat(answerOf(module, "counting.down", "[20000,0]"))
                .isEqualTo("{\"value\":200010000}");
        // The arguments are all worked out before any is put back: the second reads the first.
        assertThat(answerOf(module, "counting.down", "[3,0]")).isEqualTo("{\"value\":6}");
    }

    @Test
    void goesBackToTheTopFromEveryWayAnAttemptedConstructionTakes() {
        // Each way an attempt goes on is the answer of the recursion it is in, so a recursion
        // through any of them goes back to the top like one through a condition does.
        Running module = compiled("""
                module counting

                data Positive = { n: Int }
                    invariant kept = n > 0

                data Small = { n: Int }
                    invariant small = n < 10

                behavior down : (n: Int, acc: Int) -> Int constructs Positive

                partial let summed (n: Int, acc: Int): Int =
                    if Positive { n = n } as p then summed(p.n - 1, acc + p.n) else acc

                let down (n, acc) = summed(n, acc)

                behavior named : (n: Int, steps: Int) -> Int constructs Small

                partial let shrunk (n: Int, steps: Int): Int =
                    if Small { n = n } as s then steps
                    else | small -> shrunk(n - 1, steps + 1)

                let named (n, steps) = shrunk(n, steps)

                behavior any : (n: Int, steps: Int) -> Int constructs Small

                partial let lowered (n: Int, steps: Int): Int =
                    if Small { n = n } as s then steps else lowered(n - 1, steps + 1)

                let any (n, steps) = lowered(n, steps)
                """);

        assertThat(answerOf(module, "counting.down", "[20000,0]"))
                .isEqualTo("{\"value\":200010000}");
        assertThat(answerOf(module, "counting.named", "[20009,0]"))
                .isEqualTo("{\"value\":20000}");
        assertThat(answerOf(module, "counting.any", "[20009,0]"))
                .isEqualTo("{\"value\":20000}");
    }

    @Test
    void keepsWhatTheStepHeldFor() {
        Running module = compiled("""
                module counting

                behavior positives : (xs: List<Int>) -> List<Int>

                let positives (xs) = List.filter(x -> x > 0, xs)
                """);

        assertThat(answerOf(module, "counting.positives", "[[1,-2,3,0]]"))
                .isEqualTo("{\"value\":[1,3]}");
    }

    @Test
    void carriesWhatTheBlockReadFromAroundIt() {
        Running module = compiled("""
                module counting

                behavior raised : (by: Int, xs: List<Int>) -> List<Int>

                let raised (by, xs) = List.map(x -> x + by, xs)
                """);

        assertThat(answerOf(module, "counting.raised", "[10,[1,2]]"))
                .isEqualTo("{\"value\":[11,12]}");
    }

    @Test
    void foldsToSomethingThatIsNotAList() {
        Running module = compiled("""
                module counting

                behavior total : (xs: List<Int>) -> Int

                let total (xs) = List.fold((acc, x) -> acc + x, 0, xs)

                behavior allPositive : (xs: List<Int>) -> Bool

                let allPositive (xs) = List.all(x -> x > 0, xs)
                """);

        assertThat(answerOf(module, "counting.total", "[[1,2,3]]")).isEqualTo("{\"value\":6}");
        assertThat(answerOf(module, "counting.total", "[[]]")).isEqualTo("{\"value\":0}");
        assertThat(answerOf(module, "counting.allPositive", "[[1,2]]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "counting.allPositive", "[[1,0]]")).isEqualTo("{\"value\":false}");
    }

    @Test
    void holdsEverythingAWalkGrewPastTheRoomItStartedWith() {
        Running module = compiled("""
                module counting

                behavior doubled : (xs: List<Int>) -> List<Int>

                let doubled (xs) = List.map(x -> x + x, xs)
                """);

        StringBuilder written = new StringBuilder("[[");
        StringBuilder expected = new StringBuilder("{\"value\":[");
        for (int i = 0; i < 40; i++) {
            written.append(i > 0 ? "," : "").append(i);
            expected.append(i > 0 ? "," : "").append(i * 2);
        }

        assertThat(answerOf(module, "counting.doubled", written.append("]]").toString()))
                .isEqualTo(expected.append("]}").toString());
    }

    @Test
    void growsAMapOutOfAList() {
        Running module = compiled("""
                module counting

                behavior byName : (xs: List<Int>) -> Map<String, Int>

                let byName (xs) = List.fold(
                    (acc, x) -> Map.insert(String.fromInt(x), x, acc), Map.empty, xs)
                """);

        assertThat(answerOf(module, "counting.byName", "[[2,1]]"))
                .isEqualTo("{\"value\":{\"1\":1,\"2\":2}}");
        assertThat(answerOf(module, "counting.byName", "[[]]")).isEqualTo("{\"value\":{}}");
    }

    @Test
    void findsTheFirstElementAStepHoldsFor() {
        Running module = compiled("""
                module counting

                behavior firstOver : (n: Int, xs: List<Int>) -> Int

                let firstOver (n, xs) = match List.find(x -> x > n, xs) with
                    | Some found -> found
                    | None -> -1
                """);

        assertThat(answerOf(module, "counting.firstOver", "[2,[1,3,4]]")).isEqualTo("{\"value\":3}");
        assertThat(answerOf(module, "counting.firstOver", "[9,[1,3,4]]")).isEqualTo("{\"value\":-1}");
    }

    @Test
    void walksAListOfShapes() {
        Running module = compiled("""
                module drawing

                data Point = { x: Int, y: Int }

                behavior xs : (ps: List<Point>) -> List<Int>

                let xs (ps) = List.map(p -> p.x, ps)
                """);

        assertThat(answerOf(module, "drawing.xs", "[[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]]"))
                .isEqualTo("{\"value\":[1,3]}");
    }

    private static Running compiled(String... sources) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
