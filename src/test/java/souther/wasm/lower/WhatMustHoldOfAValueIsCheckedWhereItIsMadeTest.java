package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.AbortReason;
import souther.wasm.abi.RuntimeAbi;

/**
 * What a type says must hold of its values, checked wherever one is made.
 *
 * <p>An invariant violation is treated by where it happens. At the boundary it is bad input and
 * comes back as an issue; inside a behavior there is no case for it and no value to answer with,
 * so the call ends.
 *
 * <p>The check is written in Souther, so what runs it is a body the runtime knows nothing about.
 * It is reached through the module's table, which is what the table is there for.
 */
class WhatMustHoldOfAValueIsCheckedWhereItIsMadeTest {

    private static final String COUNTING = """
            module counting

            data Positive = { n: Int }
                invariant kept = n > 0

            behavior same : (p: Positive) -> Positive

            let same (p) = p

            behavior lowered : (p: Positive, by: Int) -> Positive

            let lowered (p, by) = Positive { n = p.n - by }
            """;

    @Test
    void answersWithWhatItRefusedWhereTheValueCameFromOutside() {
        Running module = compiled(COUNTING);

        assertThat(answerOf(module, "counting.same", "[{\"n\": 0}]")).isEqualTo(
                "{\"issues\":[{\"path\":\"/0\",\"code\":\"invariant_violation\","
                        + "\"meta\":{\"actual\":\"0\",\"expected\":\"Positive\"}}]}");
    }

    @Test
    void letsThroughAValueThatKeepsWhatItsTypeSays() {
        Running module = compiled(COUNTING);

        assertThat(answerOf(module, "counting.same", "[{\"n\": 1}]"))
                .isEqualTo("{\"value\":{\"n\":1}}");
    }

    @Test
    void endsTheCallWhereTheValueIsTheBodysOwn() {
        Running module = compiled(COUNTING);

        assertThat(answerOf(module, "counting.lowered", "[{\"n\": 5}, 2]"))
                .isEqualTo("{\"value\":{\"n\":3}}");
        assertThat(abortOf(module, "counting.lowered", "[{\"n\": 5}, 5]"))
                .contains(AbortReason.INVARIANT_VIOLATION);
    }

    @Test
    void saysWhichOfTheTypesInvariantsWasBroken() {
        Running module = compiled("""
                module ranging

                data Between = { n: Int }
                    invariant low = n > 0
                    invariant high = n < 10

                behavior same : (b: Between) -> Between

                let same (b) = b
                """);

        assertThat(answerOf(module, "ranging.same", "[{\"n\": 0}]")).contains("\"actual\":\"0\"");
        assertThat(answerOf(module, "ranging.same", "[{\"n\": 20}]")).contains("\"actual\":\"1\"");
    }

    @Test
    void checksWhatIsNestedInsideAnotherValue() {
        Running module = compiled("""
                module ranging

                data Positive = { n: Int }
                    invariant kept = n > 0

                data Pair = { left: Positive, right: Positive }

                behavior same : (p: Pair) -> Pair

                let same (p) = p
                """);

        assertThat(answerOf(module, "ranging.same", "[{\"left\": {\"n\": 1}, \"right\": {\"n\": 0}}]"))
                .contains("\"path\":\"/0/right\"", "\"code\":\"invariant_violation\"");
    }

    private static Optional<AbortReason> abortOf(Running module, String export, String arguments) {
        int snapshot = module.call(RuntimeAbi.FAILURE_GENERATION);
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        try {
            answerOf(module, export, arguments);
            throw new AssertionError(export + " answered " + arguments + " rather than ending");
        } catch (ChicoryException trapped) {
            var record = module.failureRecord();
            module.call(RuntimeAbi.ALLOC_RESET, mark);
            return record.describesTrapAfter(snapshot) ? record.namedReason() : Optional.empty();
        }
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
