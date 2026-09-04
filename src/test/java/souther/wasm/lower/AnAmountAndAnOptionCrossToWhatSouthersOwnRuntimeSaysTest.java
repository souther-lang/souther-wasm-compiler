package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.runtime.Strings;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * Writing an amount out, reading one back, and carrying a block over an option.
 *
 * <p>What an amount is written as and what a written amount comes to are Souther's answers rather
 * than this backend's, so both go through {@code souther.runtime} as well and the two must agree.
 * The scale an amount carries is part of the answer: a thousand held to two places is written to
 * two places, which is the one thing a shorter form would quietly get wrong.
 */
class AnAmountAndAnOptionCrossToWhatSouthersOwnRuntimeSaysTest {

    private static final String[] AMOUNTS = {
        "0", "1", "-1", "1000.00", "0.10", "-0.10", "1.5", "3.14159", "0.000001",
        "123456789012345678901234567890", "-123456789012345678901234567890",
        "1E+3", "1E-3", "0.0", "-0.0", "100", "0.001",
    };

    private static final String[] WRITTEN = {
        "0", "1", "-1", "1000.00", ".5", "5.", "1e3", "1E+3", "1e-3", "+7", "-7.25",
        "", " ", "1.2.3", "abc", "1,000", "0x10", "12345678901234567890.12345",
    };

    @Test
    void writesAnAmountOutWhereSouthersOwnRuntimeWritesIt() {
        Running module = compiled();

        for (String written : AMOUNTS) {
            BigDecimal held = new BigDecimal(written);
            assertThat(answerOf(module, "amounts.shown", written))
                    .describedAs(written)
                    .isEqualTo(value(quoted(Strings.fromDecimal(held))));
        }
    }

    @Test
    void readsAWrittenAmountWhereSouthersOwnRuntimeReadsIt() {
        Running module = compiled();

        for (String written : WRITTEN) {
            Object held = Strings.toDecimal(written);
            String expected = held instanceof BigDecimal amount
                    ? quoted(Strings.fromDecimal(amount))
                    : quoted("no");
            assertThat(answerOf(module, "amounts.read", quoted(written)))
                    .describedAs(written)
                    .isEqualTo(value(expected));
        }
    }

    @Test
    void carriesABlockOverWhatAnOptionHoldsAndOverNothing() {
        Running module = compiled();

        assertThat(answerOf(module, "amounts.doubled", "[7]")).isEqualTo(value("14"));
        assertThat(answerOf(module, "amounts.doubled", "[]")).isEqualTo(value("-1"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module amounts

                behavior shown : (d: Decimal) -> String

                let shown (d) = String.fromDecimal(d)

                behavior read : (s: String) -> String

                let read (s) = match String.toDecimal(s) with
                    | Decimal as held -> String.fromDecimal(held)
                    | NotANumber -> "no"

                behavior doubled : (xs: List<Int>) -> Int

                let doubled (xs) = Option.withDefault(-1, Option.map(n -> n * 2, List.get(0, xs)))
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
