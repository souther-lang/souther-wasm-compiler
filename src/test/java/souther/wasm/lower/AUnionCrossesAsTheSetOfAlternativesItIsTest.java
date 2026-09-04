package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A behavior answering alternatives nobody named together.
 *
 * <p>How a set of alternatives crosses depends on what its members carry. Where every one of them
 * carries nothing but which it is, the value written is the name itself; where any carries
 * something of its own, the name stands beside it under a key. That is the language's rule and not
 * this backend's, so a set is one document whichever backend wrote it.
 */
class AUnionCrossesAsTheSetOfAlternativesItIsTest {

    private static final String AMOUNTS = """
            module counting

            data Amount = { n: Int }

            data Missing

            behavior amountOf : (n: Int) -> Amount | Missing

            let amountOf (n) = if n > 0 then Amount { n = n } else Missing
            """;

    @Test
    void writesTheNameBesideWhatAnAlternativeCarries() {
        Running module = compiled(AMOUNTS);

        assertThat(answerOf(module, "counting.amountOf", "[3]"))
                .isEqualTo("{\"value\":{\"type\":\"Amount\",\"n\":3}}");
        assertThat(answerOf(module, "counting.amountOf", "[0]"))
                .isEqualTo("{\"value\":{\"type\":\"Missing\"}}");
    }

    @Test
    void writesTheNameAloneWhereNoAlternativeCarriesAnything() {
        Running module = compiled("""
                module lighting

                data Red

                data Green

                data Signal = Red | Green

                behavior nextAfter : (s: Signal) -> Red | Green

                let nextAfter (s) = match s with
                    | Red -> Green
                    | Green -> Red
                """);

        assertThat(answerOf(module, "lighting.nextAfter", "[\"Red\"]"))
                .isEqualTo("{\"value\":\"Green\"}");
        assertThat(answerOf(module, "lighting.nextAfter", "[\"Green\"]"))
                .isEqualTo("{\"value\":\"Red\"}");
    }

    @Test
    void takesTheArmAnAlternativeIs() {
        Running module = compiled("""
                module counting

                data Amount = { n: Int }

                data Missing

                behavior amountOf : (n: Int) -> Amount | Missing

                let amountOf (n) = if n > 0 then Amount { n = n } else Missing

                behavior orZero : (n: Int) -> Int

                let orZero (n) = match amountOf(n) with
                    | Amount as a -> a.n
                    | Missing -> 0
                """);

        assertThat(answerOf(module, "counting.orZero", "[3]")).isEqualTo("{\"value\":3}");
        assertThat(answerOf(module, "counting.orZero", "[0]")).isEqualTo("{\"value\":0}");
    }

    @Test
    void saysSoWhereTheNameIsNoAlternativeTheSetOffers() {
        Running module = compiled(AMOUNTS);

        assertThat(answerOf(module, "counting.amountOf", "[1]")).contains("\"value\"");
        Running lights = compiled("""
                module lighting

                data Red

                data Green

                data Signal = Red | Green

                behavior same : (s: Signal) -> Signal

                let same (s) = s
                """);

        assertThat(answerOf(lights, "lighting.same", "[\"Amber\"]"))
                .contains("\"code\":\"not_allowed\"", "\"actual\":\"Amber\"");
        assertThat(answerOf(lights, "lighting.same", "[7]"))
                .contains("\"code\":\"type_mismatch\"", "\"expected\":\"a case\"");
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
