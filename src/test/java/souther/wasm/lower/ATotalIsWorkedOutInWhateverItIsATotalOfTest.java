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
 * Adding up a list, and multiplying one out, over each of the two things a number can be.
 *
 * <p>The language admits a whole number and an amount and nothing else, so a total is worked out in
 * whichever of them the list holds. Starting from a whole-number zero and adding whole numbers is
 * an answer for one of them and a reading of the other's bytes as if they were that one's.
 */
class ATotalIsWorkedOutInWhateverItIsATotalOfTest {

    @Test
    void addsUpAListOfWholeNumbers() {
        Running module = compiled();

        assertThat(answerOf(module, "totals.counted", "[1,2,3]")).isEqualTo("{\"value\":6}");
        assertThat(answerOf(module, "totals.counted", "[]")).isEqualTo("{\"value\":0}");
    }

    @Test
    void addsUpAListOfAmountsAsAmounts() {
        Running module = compiled();

        assertThat(answerOf(module, "totals.summed", "[1500.00,1500.00]"))
                .isEqualTo(value(new BigDecimal("1500.00").add(new BigDecimal("1500.00"))));
        assertThat(answerOf(module, "totals.summed", "[0.10,0.20]"))
                .isEqualTo(value(new BigDecimal("0.10").add(new BigDecimal("0.20"))));
        assertThat(answerOf(module, "totals.summed", "[3000.00]"))
                .isEqualTo(value(new BigDecimal("3000.00")));
    }

    @Test
    void answersTheZeroOfWhateverAnEmptyListWasAListOf() {
        Running module = compiled();

        // Nothing in it says nothing about which of the two it holds, and the type does.
        assertThat(answerOf(module, "totals.summed", "[]")).isEqualTo(value(BigDecimal.ZERO));
        assertThat(answerOf(module, "totals.multiplied", "[]"))
                .isEqualTo(value(BigDecimal.ONE));
    }

    @Test
    void multipliesOutAListOfAmountsAsAmounts() {
        Running module = compiled();

        assertThat(answerOf(module, "totals.multiplied", "[1.50,4.00]"))
                .isEqualTo(value(new BigDecimal("1.50").multiply(new BigDecimal("4.00"))));
        assertThat(answerOf(module, "totals.scaled", "[3]")).isEqualTo("{\"value\":6}");
    }

    private static String value(BigDecimal held) {
        return "{\"value\":" + Strings.fromDecimal(held.stripTrailingZeros()) + "}";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module totals

                behavior counted : (xs: List<Int>) -> Int

                let counted (xs) = List.sum(xs)

                behavior summed : (xs: List<Decimal>) -> Decimal

                let summed (xs) = List.sum(xs)

                behavior multiplied : (xs: List<Decimal>) -> Decimal

                let multiplied (xs) = List.product(xs)

                behavior scaled : (xs: List<Int>) -> Int

                let scaled (xs) = List.product(xs) * 2
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
