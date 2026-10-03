package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * {@code ++} on two lists, and every operation the library writes with it.
 *
 * <p>A fold that grows its whole accumulator with {@code acc ++ [x]} is rewritten into a builder
 * before it reaches a backend, so {@code List.map} never runs a {@code ++}. One that grows a list
 * held inside a pair — {@code mapIndexed}, {@code partition}, {@code take} — is not, and runs the
 * operator as written, which joined the two lists as if they were text.
 */
class AListIsJoinedToAnotherTest {

    private static final String PROGRAM = """
            module joining

            behavior joined : (a: List<Int>, b: List<Int>) -> List<Int>

            let joined (a, b) = a ++ b

            behavior indexed : (xs: List<String>) -> List<String>

            let indexed (xs) = List.mapIndexed((i, x) -> x ++ String.fromInt(i), xs)

            behavior zipped : (xs: List<String>, ys: List<Int>) -> List<String>

            let zipped (xs, ys) = List.map(pair -> {
                let (x, y) = pair
                x ++ String.fromInt(y)
            }, List.zipShortest(xs, ys))

            behavior firsts : (xs: List<Int>) -> List<Int>

            let firsts (xs) = List.take(2, xs)

            behavior rest : (xs: List<Int>) -> List<Int>

            let rest (xs) = List.drop(2, xs)

            behavior once : (xs: List<Int>) -> List<Int>

            let once (xs) = List.distinct(xs)

            behavior evens : (xs: List<Int>) -> List<Int>

            let evens (xs) = {
                let (yes, no) = List.partition(x -> x > 2, xs)
                yes ++ [0] ++ no
            }

            behavior flattened : (xss: List<List<Int>>) -> List<Int>

            let flattened (xss) = List.concat(xss)
            """;

    @Test
    void joinsTwoListsElementByElement() {
        Running module = compiled();

        assertThat(answerOf(module, "joining.joined", "[1,2],[3]")).isEqualTo(value("[1,2,3]"));
        assertThat(answerOf(module, "joining.joined", "[],[3]")).isEqualTo(value("[3]"));
        assertThat(answerOf(module, "joining.joined", "[1],[]")).isEqualTo(value("[1]"));
        assertThat(answerOf(module, "joining.flattened", "[[1],[],[2,3]]"))
                .isEqualTo(value("[1,2,3]"));
    }

    @Test
    void growsAListHeldInAPair() {
        Running module = compiled();

        assertThat(answerOf(module, "joining.indexed", "[\"a\",\"b\",\"c\"]"))
                .isEqualTo(value("[\"a0\",\"b1\",\"c2\"]"));
        assertThat(answerOf(module, "joining.zipped", "[\"a\",\"b\",\"c\"],[1,2]"))
                .isEqualTo(value("[\"a1\",\"b2\"]"));
        assertThat(answerOf(module, "joining.firsts", "[5,6,7,8]")).isEqualTo(value("[5,6]"));
        assertThat(answerOf(module, "joining.rest", "[5,6,7,8]")).isEqualTo(value("[7,8]"));
        assertThat(answerOf(module, "joining.once", "[3,1,3,2,1]")).isEqualTo(value("[3,1,2]"));
        assertThat(answerOf(module, "joining.evens", "[1,2,3,4]"))
                .isEqualTo(value("[3,4,0,1,2]"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(PROGRAM))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        String written = "[" + arguments + "]";
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
