package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * Where a value with an optional part stands relative to another.
 *
 * <p>A value is compared by what it is written as, and what an optional is written as is what it
 * holds, or nothing at all. Comparing the optional itself instead reads the cell that holds a value
 * as if it were the value — which answers, with a number, and the number is where in memory the
 * value happened to be put.
 *
 * <p>Two of everything a wrong answer here breaks: a set that keeps what it already holds, and a
 * set that drops what it does not.
 */
class AnOptionalIsComparedByWhatItHoldsTest {

    @Test
    void holdsWhatWasWrittenTwiceOnce() {
        Running module = compiled();

        assertThat(answerOf(module, "counting.numbers",
                        "[{\"x\":1,\"y\":9},{\"x\":1,\"y\":2},{\"x\":1,\"y\":9}]"))
                .isEqualTo("{\"value\":[{\"x\":1,\"y\":2},{\"x\":1,\"y\":9}]}");
    }

    @Test
    void keepsWhatIsNotTheSame() {
        Running module = compiled();

        // Two values that differ only in what an optional holds are two values. Reading the cell
        // rather than what it holds makes every one of them the same as every other.
        assertThat(answerOf(module, "counting.labels",
                        "[{\"id\":1,\"label\":\"b\"},{\"id\":1,\"label\":\"a\"}]"))
                .isEqualTo("{\"value\":[{\"id\":1,\"label\":\"a\"},{\"id\":1,\"label\":\"b\"}]}");
    }

    @Test
    void putsNothingBeforeSomething() {
        Running module = compiled();

        // What an absent optional is written as is nothing at all, and nothing sorts before a
        // string and before a number, which is what a document's own order says.
        assertThat(answerOf(module, "counting.labels",
                        "[{\"id\":1,\"label\":\"a\"},{\"id\":1}]"))
                .isEqualTo("{\"value\":[{\"id\":1},{\"id\":1,\"label\":\"a\"}]}");
        assertThat(answerOf(module, "counting.numbers", "[{\"x\":1,\"y\":2},{\"x\":1}]"))
                .isEqualTo("{\"value\":[{\"x\":1},{\"x\":1,\"y\":2}]}");
    }

    @Test
    void holdsTwoAbsencesAsOne() {
        Running module = compiled();

        assertThat(answerOf(module, "counting.numbers", "[{\"x\":1},{\"x\":1}]"))
                .isEqualTo("{\"value\":[{\"x\":1}]}");
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module counting

                data Point = { x: Int, y: Int? }
                data Named = { id: Int, label: String? }

                behavior numbers : (xs: Set<Point>) -> Set<Point>

                let numbers (xs) = xs

                behavior labels : (xs: Set<Named>) -> Set<Named>

                let labels (xs) = xs
                """))));
    }

    private static String answerOf(Running module, String export, String argument) {
        String written = "[" + argument + "]";
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(written);
        long[] answer = module.callWithString(
                export, address, written.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }
}
