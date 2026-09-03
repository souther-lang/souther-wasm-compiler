package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.runtime.DecimalMath;
import souther.runtime.HALF_UP;
import souther.runtime.Representations;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * Working an amount out, against Souther's own account of what that comes to.
 *
 * <p>These do not say what a sum is. They run the same arithmetic twice — once as this backend
 * writes it and once through {@code DecimalMath}, which is what the JVM backend calls — and
 * require the two to answer alike, scale and all.
 */
class AnAmountIsWorkedOutWhereSouthersOwnRuntimeDoesTest {

    private static final String[] AMOUNTS = {
        "0", "1", "-1", "2.5", "-2.5", "0.001", "100", "1.00", "12345.6789", "-0.5",
        "999999999999999999999", "0.0000001",
    };

    @Test
    void addsAndSubtractsAndMultipliesWhereSouthersRuntimeDoes() {
        Running module = compiled();

        for (String left : AMOUNTS) {
            for (String right : AMOUNTS) {
                BigDecimal a = new BigDecimal(left);
                BigDecimal b = new BigDecimal(right);
                assertThat(answerOf(module, "pricing.sum", "[" + left + "," + right + "]"))
                        .describedAs(left + " + " + right)
                        .isEqualTo(written(DecimalMath.add(a, b)));
                assertThat(answerOf(module, "pricing.difference", "[" + left + "," + right + "]"))
                        .describedAs(left + " - " + right)
                        .isEqualTo(written(DecimalMath.subtract(a, b)));
                assertThat(answerOf(module, "pricing.product", "[" + left + "," + right + "]"))
                        .describedAs(left + " * " + right)
                        .isEqualTo(written(DecimalMath.multiply(a, b)));
            }
        }
    }

    @Test
    void dividesWhereSouthersRuntimeDivides() {
        Running module = compiled();

        for (String left : AMOUNTS) {
            for (String right : AMOUNTS) {
                if (new BigDecimal(right).signum() == 0) {
                    continue;
                }
                assertThat(answerOf(module, "pricing.quotient", "[" + left + "," + right + "]"))
                        .describedAs(left + " / " + right)
                        .isEqualTo(written(
                                DecimalMath.divide(new BigDecimal(left), new BigDecimal(right))));
            }
        }
    }

    @Test
    void roundsToAScaleWhereSouthersRuntimeDoes() {
        Running module = compiled();

        for (String held : AMOUNTS) {
            for (long places : new long[] {0, 1, 2, 5}) {
                BigDecimal rounded = new BigDecimal(held)
                        .setScale((int) places, java.math.RoundingMode.HALF_UP);
                assertThat(answerOf(module, "pricing.rounded",
                        "[" + held + "," + places + "]"))
                        .describedAs(held + " at " + places)
                        .isEqualTo(written(rounded));
            }
        }
    }

    @Test
    void dividesAtAScaleOrAnswersTheCaseAZeroDivisorIs() {
        Running module = compiled();

        assertThat(answerOf(module, "pricing.shared", "[10,3,4]"))
                .isEqualTo(written((BigDecimal) DecimalMath.divide(
                        new BigDecimal("10"), new BigDecimal("3"), 4, HALF_UP.INSTANCE)));
        assertThat(answerOf(module, "pricing.shared", "[10,0,4]")).isEqualTo("{\"value\":-1}");
    }

    @Test
    void goesBetweenAWholeNumberAndAnAmount() {
        Running module = compiled();

        assertThat(answerOf(module, "pricing.asAmount", "[7]")).isEqualTo("{\"value\":7}");
        assertThat(answerOf(module, "pricing.asWhole", "[2.5]")).isEqualTo("{\"value\":3}");
        assertThat(answerOf(module, "pricing.asWhole", "[2.4]")).isEqualTo("{\"value\":2}");
        assertThat(answerOf(module, "pricing.asWhole", "[-2.5]")).isEqualTo("{\"value\":-3}");
    }

    /** The one form the language writes an amount in, inside the envelope an answer is. */
    private static String written(BigDecimal held) {
        return "{\"value\":" + Representations.canonicalNumber(held) + "}";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module pricing

                behavior sum : (a: Decimal, b: Decimal) -> Decimal

                let sum (a, b) = a + b

                behavior difference : (a: Decimal, b: Decimal) -> Decimal

                let difference (a, b) = a - b

                behavior product : (a: Decimal, b: Decimal) -> Decimal

                let product (a, b) = a * b

                behavior quotient : (a: Decimal, b: Decimal) -> Decimal

                let quotient (a, b) = a / b

                behavior rounded : (d: Decimal, places: Int) -> Decimal

                let rounded (d, places) = Decimal.round(places, HALF_UP, d)

                behavior shared : (a: Decimal, b: Decimal, places: Int) -> Decimal

                let shared (a, b, places) = match Decimal.divide(a, b, places, HALF_UP) with
                    | Decimal as d -> d
                    | DivisionByZero -> Decimal.fromInt(-1)

                behavior asAmount : (n: Int) -> Decimal

                let asAmount (n) = Decimal.fromInt(n)

                behavior asWhole : (d: Decimal) -> Int

                let asWhole (d) = Decimal.toInt(HALF_UP, d)
                """))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }
}
