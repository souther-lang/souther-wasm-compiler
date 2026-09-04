package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.runtime.Representations;
import souther.wasm.Running;

/**
 * An amount going out and coming back.
 *
 * <p>Two values that differ only in scale are one amount and two ways of writing it, so what
 * crosses is the one form the language settles on. What that form is belongs to Souther, so these
 * ask it: the same amount goes through this backend and through {@code Representations}, and the
 * two have to write it the same way.
 */
class AnAmountCrossesInTheOneFormItHasTest {

    private static final String[] AMOUNTS = {
        "0", "0.000", "1", "1.00", "1.50", "-3.000", "100", "100.00", "1.0E+6", "12345.6789",
        "0.0000001", "1E-7", "-0.5", "123456789012345678901234567890.5", "1E+20", "2E-20",
    };

    @Test
    void writesAnAmountWhereSouthersOwnAccountWritesIt() {
        Running module = compiled();

        for (String written : AMOUNTS) {
            assertThat(answerOf(module, "pricing.same", "[" + written + "]"))
                    .describedAs(written)
                    .isEqualTo("{\"value\":" + canonical(written) + "}");
        }
    }

    @Test
    void putsTwoWaysOfWritingOneAmountThroughAsOneDocument() {
        Running module = compiled();

        assertThat(answerOf(module, "pricing.same", "[1.00]"))
                .isEqualTo(answerOf(module, "pricing.same", "[1.0]"));
        assertThat(answerOf(module, "pricing.same", "[1.0E+6]"))
                .isEqualTo(answerOf(module, "pricing.same", "[1000000]"));
    }

    @Test
    void carriesAnAmountInsideAShape() {
        Running module = compiled();

        assertThat(answerOf(module, "pricing.held", "[{\"amount\":2.50}]"))
                .isEqualTo("{\"value\":{\"amount\":2.5}}");
    }

    @Test
    void saysSoWhereWhatWasWrittenIsNotANumber() {
        Running module = compiled();

        assertThat(answerOf(module, "pricing.same", "[\"1\"]"))
                .contains("\"expected\":\"Decimal\"", "\"actual\":\"string\"");
    }

    @Test
    void ordersAmountsBySizeAndThenByTheWayTheyAreWritten() {
        Running module = compiled();

        assertThat(answerOf(module, "pricing.sorted", "[[2, 1.5, 10, -1]]"))
                .isEqualTo("{\"value\":[-1,1.5,2,10]}");
    }

    /** The one form {@code Representations} writes an amount in. */
    private static String canonical(String written) {
        return Representations.canonicalNumber(new BigDecimal(written)).toString();
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module pricing

                data Money = { amount: Decimal }

                behavior same : (d: Decimal) -> Decimal

                let same (d) = d

                behavior held : (m: Money) -> Money

                let held (m) = m

                behavior sorted : (ds: List<Decimal>) -> List<Decimal>

                let sorted (ds) = List.sort(ds)
                """))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
