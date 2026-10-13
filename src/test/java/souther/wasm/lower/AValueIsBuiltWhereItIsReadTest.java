package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * A value a module declares, built by a function of its own wherever a body reads it.
 *
 * <p>Its function is handed the values its root region built already, and another module reads a
 * published one through the entry its module holds for it. Built again on every read rather than
 * kept: what a call builds lives in its arena, which the caller pops after the call.
 */
class AValueIsBuiltWhereItIsReadTest {

    @Test
    void aBehaviorReadsAValueItsModuleDeclares() {
        Running module = compiled("""
                module counting

                let ks = List.reverse([1, 2, 3])

                behavior first : (n: Int) -> Int

                let first (n) = n + List.length(ks)
                """);

        assertThat(answerOf(module, "counting.first", "[10]")).isEqualTo("{\"value\":13}");
        assertThat(answerOf(module, "counting.first", "[20]")).isEqualTo("{\"value\":23}");
    }

    @Test
    void aValueIsHandedTheValuesItsRootReads() {
        Running module = compiled("""
                module counting

                let ks = [1, 2, 3]

                let ys = List.reverse(ks)

                behavior firstOf : (n: Int) -> List<Int>

                let firstOf (n) = List.take(n, ys)
                """);

        assertThat(answerOf(module, "counting.firstOf", "[2]")).isEqualTo("{\"value\":[3,2]}");
    }

    /**
     * The value's own function is handed {@code base}, and the entry hands it over; a reader in
     * another module has no {@code base} to hand, which is why it reads the entry.
     */
    @Test
    void anotherModuleReadsAPublishedValueThroughItsEntry() {
        Running module = compiled("""
                module stock exposing ( levels )

                let base = [4, 5, 6]

                let levels = List.reverse(base)
                """, """
                module counting

                import stock ( levels )

                behavior total : (n: Int) -> Int

                let total (n) = n + List.length(levels)
                """);

        assertThat(answerOf(module, "counting.total", "[1]")).isEqualTo("{\"value\":4}");
    }

    /**
     * A row names a value of the file its rows are written in. Nothing of that file is written
     * here, and the program it belongs to is written whole.
     */
    @Test
    void aProgramWhoseRowsNameAValueOfTheirOwnIsWritten() {
        Running module = compiled("""
                module counting

                behavior counted : (xs: List<Int>) -> Int

                let counted (xs) = List.length(xs)
                """, """
                examples for counting

                let few = List.reverse([1, 2])

                example counted
                    | (few) -> 2
                """);

        assertThat(answerOf(module, "counting.counted", "[[7, 8, 9]]")).isEqualTo("{\"value\":3}");
    }

    private static Running compiled(String... sources) {
        return Running.linked(Compiled.module(Compiled.program(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
