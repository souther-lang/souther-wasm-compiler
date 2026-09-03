package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A call, reaching the declaration the checker typed it against.
 *
 * <p>A behavior is two functions. One is what the declaration is — its parameters as values, one
 * value answered — and that is what another body's call reaches. The other is the crossing an
 * export is, which reads the document and writes the answer, and the first knows nothing of it.
 *
 * <p>A helper is not one of them. The checker expands a call to one where it was written, so a
 * checked program has no helper body left to reach and this backend never meets one.
 */
class ACallReachesWhatTheCheckerSaidItDoesTest {

    @Test
    void reachesTheBehaviorItNamesWhereMoreThanOneWouldFit() {
        Running module = compiled("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n

                behavior tripled : (n: Int) -> Int

                let tripled (n) = n + n + n

                behavior thrice : (n: Int) -> Int

                let thrice (n) = tripled(n)

                behavior twice : (n: Int) -> Int

                let twice (n) = doubled(n)
                """);

        assertThat(answerOf(module, "counting.thrice", "[5]")).isEqualTo("{\"value\":15}");
        assertThat(answerOf(module, "counting.twice", "[5]")).isEqualTo("{\"value\":10}");
    }

    @Test
    void handsTheArgumentsOverInTheOrderTheCallWroteThem() {
        Running module = compiled("""
                module counting

                behavior without : (a: Int, b: Int) -> Int

                let without (a, b) = a - b

                behavior remaining : (a: Int, b: Int) -> Int

                let remaining (a, b) = without(a, b)
                """);

        assertThat(answerOf(module, "counting.remaining", "[10, 3]")).isEqualTo("{\"value\":7}");
    }

    @Test
    void reachesOneDeclaredAfterTheBodyThatCallsIt() {
        Running module = compiled("""
                module counting

                behavior quadrupled : (n: Int) -> Int

                let quadrupled (n) = doubled(doubled(n))

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """);

        assertThat(answerOf(module, "counting.quadrupled", "[3]")).isEqualTo("{\"value\":12}");
    }

    @Test
    void reachesOneAnotherModuleDeclares() {
        Running module = compiled("""
                module maths exposing ( doubled )

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """, """
                module counting

                import maths ( doubled )

                behavior quadrupled : (n: Int) -> Int

                let quadrupled (n) = doubled(doubled(n))
                """);

        assertThat(answerOf(module, "counting.quadrupled", "[3]")).isEqualTo("{\"value\":12}");
    }

    @Test
    void carriesAShapeThroughACallAndBack() {
        Running module = compiled("""
                module drawing

                data Point = { x: Int, y: Int }

                behavior flipped : (p: Point) -> Point

                let flipped (p) = Point { x = p.y, y = p.x }

                behavior twice : (p: Point) -> Point

                let twice (p) = flipped(flipped(p))
                """);

        assertThat(answerOf(module, "drawing.twice", "[{\"x\": 1, \"y\": 2}]"))
                .isEqualTo("{\"value\":{\"x\":1,\"y\":2}}");
    }

    @Test
    void meetsNoHelperBecauseTheCheckerExpandedItWhereItWasWritten() {
        Running module = compiled("""
                module counting

                let doubled (n: Int): Int = n + n

                behavior quadrupled : (n: Int) -> Int

                let quadrupled (n) = doubled(doubled(n))
                """);

        assertThat(answerOf(module, "counting.quadrupled", "[3]")).isEqualTo("{\"value\":12}");
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
