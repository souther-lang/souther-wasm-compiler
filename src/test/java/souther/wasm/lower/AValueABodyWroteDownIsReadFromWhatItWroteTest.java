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
 * An amount, a day, a time and the two together, written down in a body rather than handed in.
 *
 * <p>What a body wrote is text in static memory and the value is built where it is used, because a
 * value lives on the arena and the arena is reset between calls. The scale of a written amount is
 * part of what was written, so a body that writes a thousand to two places has a thousand to two
 * places and not the shorter form of the same number.
 */
class AValueABodyWroteDownIsReadFromWhatItWroteTest {

    @Test
    void keepsTheScaleAnAmountWasWrittenWith() {
        Running module = compiled();

        assertThat(answerOf(module, "written.rate", "0"))
                .isEqualTo(value(quoted(Strings.fromDecimal(new BigDecimal("1.10")))));
        assertThat(answerOf(module, "written.thousand", "0"))
                .isEqualTo(value(quoted(Strings.fromDecimal(new BigDecimal("1000.00")))));
        assertThat(answerOf(module, "written.tiny", "0"))
                .isEqualTo(value(quoted(Strings.fromDecimal(new BigDecimal("0.000001")))));
    }

    @Test
    void worksOutAnAmountAgainstOneABodyWroteDown() {
        Running module = compiled();

        assertThat(answerOf(module, "written.withVat", "100"))
                .isEqualTo(value(quoted(
                        Strings.fromDecimal(new BigDecimal("100").multiply(new BigDecimal("1.10"))))));
    }

    @Test
    void readsADayATimeAndTheTwoTogetherFromTheTextTheyWereWrittenAs() {
        Running module = compiled();

        // None of the three is the value its own representation counts from, so a text that was
        // not read would answer the first of January nineteen seventy or midnight and not these.
        assertThat(answerOf(module, "written.armistice", "0"))
                .isEqualTo(value(quoted("1918-11-11")));
        assertThat(answerOf(module, "written.noon", "0")).isEqualTo(value(quoted("12:00")));
        assertThat(answerOf(module, "written.launch", "0"))
                .isEqualTo(value(quoted("2026-09-04T09:30")));
        assertThat(answerOf(module, "written.daysSince", quoted("1918-11-21")))
                .isEqualTo(value("10"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module written

                behavior rate : (ignored: Int) -> String

                let rate (ignored) = String.fromDecimal(1.10m)

                behavior thousand : (ignored: Int) -> String

                let thousand (ignored) = String.fromDecimal(1000.00m)

                behavior tiny : (ignored: Int) -> String

                let tiny (ignored) = String.fromDecimal(0.000001m)

                behavior withVat : (n: Int) -> String

                let withVat (n) = String.fromDecimal(Decimal.fromInt(n) * 1.10m)

                behavior armistice : (ignored: Int) -> Date

                let armistice (ignored) = Date("1918-11-11")

                behavior noon : (ignored: Int) -> Time

                let noon (ignored) = Time("12:00")

                behavior launch : (ignored: Int) -> DateTime

                let launch (ignored) = DateTime("2026-09-04T09:30")

                behavior daysSince : (d: Date) -> Int

                let daysSince (d) = Date.daysBetween(Date("1918-11-11"), d)
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
