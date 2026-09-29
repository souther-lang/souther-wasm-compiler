package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * {@code ++} over two lists is the two lists' elements, in that order, as it is on the JVM side.
 *
 * <p>A list and a string are laid out differently, so joining them is two operations of the
 * runtime and the operand's type says which one a body reaches. Each expected answer here is the
 * one the JVM backend gives for the same source.
 */
class APlusPlusJoinsWhatItsOperandsAreTest {

    private static final String SOURCE = """
            module joining

            behavior concat : (a: List<Int>, b: List<Int>) -> List<Int>
            let concat (a, b) = a ++ b

            behavior appended : (a: List<Int>, b: List<Int>) -> List<Int>
            let appended (a, b) = List.append(a, b)

            behavior flattened : (a: List<Int>, b: List<Int>) -> List<Int>
            let flattened (a, b) = List.concat([a, b])

            behavior firstTwo : (a: List<Int>) -> List<Int>
            let firstTwo (a) = List.take(2, a)

            behavior afterTwo : (a: List<Int>) -> List<Int>
            let afterTwo (a) = List.drop(2, a)

            behavior countAndSum : (a: List<Int>) -> Int
            let countAndSum (a) = {
                let (n, s) = List.fold((acc, x) -> { let (i, t) = acc
                                                      (i + 1, t + x) }, (0, 0), a)
                n * 1000 + s
            }

            behavior strings : (a: String, b: String) -> String
            let strings (a, b) = a ++ b

            behavior emptyLeft : (b: List<Int>) -> List<Int>
            let emptyLeft (b) = [] ++ b

            behavior emptyRight : (a: List<Int>) -> List<Int>
            let emptyRight (a) = a ++ []

            behavior grownByTheRewrite : (xs: List<Int>) -> List<Int>
            let grownByTheRewrite (xs) = List.fold((acc, x) -> acc ++ [x], [], xs)

            behavior grownAndRead : (xs: List<Int>) -> List<Int>
            let grownAndRead (xs) = List.fold(
                (acc, x) -> if List.length(acc) > 100 then acc else acc ++ [x], [], xs)
            """;

    @Test
    void answersTheLeftListsElementsThenTheRightOnes() {
        Running module = compiled(SOURCE);

        assertThat(answerOf(module, "joining.concat", "[[1,2],[3,4]]"))
                .isEqualTo("{\"value\":[1,2,3,4]}");
        assertThat(answerOf(module, "joining.appended", "[[1,2],[3,4]]"))
                .isEqualTo("{\"value\":[1,2,3,4]}");
        assertThat(answerOf(module, "joining.flattened", "[[1,2],[3,4]]"))
                .isEqualTo("{\"value\":[1,2,3,4]}");
    }

    @Test
    void answersTheOtherOperandWhereOneSideHoldsNothing() {
        Running module = compiled(SOURCE);

        assertThat(answerOf(module, "joining.emptyLeft", "[[5,6]]")).isEqualTo("{\"value\":[5,6]}");
        assertThat(answerOf(module, "joining.emptyRight", "[[7,8]]")).isEqualTo("{\"value\":[7,8]}");
        assertThat(answerOf(module, "joining.concat", "[[],[]]")).isEqualTo("{\"value\":[]}");
    }

    @Test
    void joinsWhatAFoldGrowsWhetherOrNotItIsRewrittenToABuilder() {
        Running module = compiled(SOURCE);

        assertThat(answerOf(module, "joining.grownByTheRewrite", "[[1,2,3]]"))
                .isEqualTo("{\"value\":[1,2,3]}");
        assertThat(answerOf(module, "joining.grownAndRead", "[[1,2,3]]"))
                .isEqualTo("{\"value\":[1,2,3]}");
    }

    @Test
    void takesAndDropsByJoiningTheElementsItWalks() {
        Running module = compiled(SOURCE);

        assertThat(answerOf(module, "joining.firstTwo", "[[1,2,3,4]]"))
                .isEqualTo("{\"value\":[1,2]}");
        assertThat(answerOf(module, "joining.afterTwo", "[[1,2,3,4]]"))
                .isEqualTo("{\"value\":[3,4]}");
        assertThat(answerOf(module, "joining.countAndSum", "[[1,2,3,4]]"))
                .isEqualTo("{\"value\":4010}");
    }

    @Test
    void stillJoinsTwoStringsAsText() {
        Running module = compiled(SOURCE);

        assertThat(answerOf(module, "joining.strings", "[\"ab\",\"cd\"]"))
                .isEqualTo("{\"value\":\"abcd\"}");
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
