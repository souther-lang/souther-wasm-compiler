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
