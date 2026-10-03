package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.ChicoryException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import souther.compiler.abort.AbortKind;
import souther.runtime.CEILING;
import souther.runtime.DOWN;
import souther.runtime.FLOOR;
import souther.runtime.HALF_DOWN;
import souther.runtime.HALF_EVEN;
import souther.runtime.HALF_UP;
import souther.runtime.Rational;
import souther.runtime.RationalMath;
import souther.runtime.Representations;
import souther.runtime.RoundingMode;
import souther.runtime.UP;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.FailureCause;
import souther.wasm.abi.RuntimeAbi;

/**
 * An exact quotient, against Souther's own account of what it comes to.
 *
 * <p>These do not say what a third is. They run the same arithmetic twice — once as this backend
 * writes it and once through {@code RationalMath}, which is what the JVM backend calls — and
 * require the two to answer alike. A {@code Rational} has no external form, so what crosses is
 * what a model narrows one to: a {@code Decimal} at a scale and a mode, an {@code Int}, a truth.
 */
class AnExactQuotientIsWhatSouthersOwnRuntimeSaysTest {

    /** Numerators and denominators, nought and the ends of an Int among them. */
    private static final long[] WHOLES = {
        0, 1, -1, 2, 3, -3, 7, 10, 12, -40, 1_000_000_007L, Long.MAX_VALUE, Long.MIN_VALUE,
    };

    private static final String[] AMOUNTS = {
        "0", "1", "-2.5", "0.001", "3.00", "12345.6789", "1E+30", "1E-30",
        "999999999999999999999.999",
    };

    private static final RoundingMode[] MODES = {
        HALF_UP.INSTANCE, HALF_EVEN.INSTANCE, HALF_DOWN.INSTANCE, UP.INSTANCE, DOWN.INSTANCE,
        CEILING.INSTANCE, FLOOR.INSTANCE,
    };

    private static final String SOURCE = """
            module exact

            data AsWhole = { whole: Bool, n: Int }

            data AsFinite = { finite: Bool, d: Decimal }

            behavior quotient : (a: Int, b: Int) -> Decimal

            let quotient (a, b) = Rational.toDecimal(30, HALF_EVEN, a / b)

            behavior amounts : (a: Decimal, b: Decimal) -> Decimal

            let amounts (a, b) = Rational.toDecimal(30, HALF_EVEN, a / b)

            behavior mixed : (a: Int, b: Int, c: Int, d: Decimal) -> Decimal

            let mixed (a, b, c, d) = Rational.toDecimal(30, HALF_EVEN, (a / b + c) * d - a / b)

            behavior opposite : (a: Int, b: Int) -> Decimal

            let opposite (a, b) = Rational.toDecimal(30, HALF_EVEN, -(a / b))

            behavior below : (a: Int, b: Int, c: Int) -> Bool

            let below (a, b, c) = a / b < c

            behavior same : (a: Int, b: Int, c: Int, d: Int) -> Bool

            let same (a, b, c, d) = a / b == c / d

            behavior atLeast : (a: Int, b: Int, d: Decimal) -> Bool

            let atLeast (a, b, d) = a / b >= d

            behavior rounded : (a: Int, b: Int) -> List<Int>

            let rounded (a, b) = {
                let r = a / b
                [Rational.toInt(HALF_UP, r), Rational.toInt(HALF_EVEN, r),
                 Rational.toInt(HALF_DOWN, r), Rational.toInt(UP, r), Rational.toInt(DOWN, r),
                 Rational.toInt(CEILING, r), Rational.toInt(FLOOR, r)]
            }

            behavior whole : (a: Int, b: Int) -> AsWhole

            let whole (a, b) = match Rational.toWholeNumber(a / b) with
                | Int as n -> AsWhole { whole = true, n = n }
                | NotWhole -> AsWhole { whole = false, n = 0 }

            behavior finite : (a: Decimal, b: Decimal) -> AsFinite

            let finite (a, b) = match Rational.toFiniteDecimal(a / b) with
                | Decimal as d -> AsFinite { finite = true, d = d }
                | NotAFiniteDecimal -> AsFinite { finite = false, d = 0m }

            behavior functions : (a: Int, d: Decimal) -> List<Decimal>

            let functions (a, d) = {
                let x = Rational.fromInt(a)
                let y = Rational.fromDecimal(d)
                List.map(r -> Rational.toDecimal(30, HALF_EVEN, r),
                    [Rational.add(x, y), Rational.subtract(x, y), Rational.multiply(x, y)])
            }

            behavior compared : (a: Int, d: Decimal) -> Int

            let compared (a, d) = Rational.compare(Rational.fromInt(a), Rational.fromDecimal(d))

            behavior divided : (a: Int, d: Decimal) -> Decimal

            let divided (a, d) =
                Rational.toDecimal(30, HALF_EVEN, Rational.divide(Rational.fromInt(a), Rational.fromDecimal(d)))

            behavior distinct : (xs: List<Int>) -> Int

            let distinct (xs) = Set.size(Set.fromList(List.map(x -> x / 4, xs)))

            behavior keyed : (xs: List<Int>) -> Int

            let keyed (xs) = Map.size(Map.fromList(List.map(x -> (x / 4, x), xs)))

            behavior sorted : (xs: List<Int>) -> List<Decimal>

            let sorted (xs) = List.map(r -> Rational.toDecimal(5, HALF_EVEN, r), List.sort(List.map(x -> 1 / x, xs)))

            behavior harmonic : (xs: List<Int>) -> Decimal

            let harmonic (xs) = Rational.toDecimal(30, HALF_EVEN, List.sum(List.map(x -> 1 / x, xs)))

            behavior product : (xs: List<Int>) -> Decimal

            let product (xs) = Rational.toDecimal(30, HALF_EVEN, List.product(List.map(x -> x / 3, xs)))
            """;

    @Test
    void dividesTwoWholeNumbersAndTwoAmountsExactly() {
        Running module = compiled();

        for (long a : WHOLES) {
            for (long b : WHOLES) {
                if (b == 0) {
                    continue;
                }
                assertThat(answerOf(module, "exact.quotient", array(a, b)))
                        .describedAs(a + " / " + b)
                        .isEqualTo(written(RationalMath.toDecimal(30, HALF_EVEN.INSTANCE,
                                RationalMath.divideWholeNumbers(a, b))));
            }
        }
        for (String a : AMOUNTS) {
            for (String b : AMOUNTS) {
                if (new BigDecimal(b).signum() == 0) {
                    continue;
                }
                assertThat(answerOf(module, "exact.amounts", "[" + a + "," + b + "]"))
                        .describedAs(a + " / " + b)
                        .isEqualTo(written(RationalMath.toDecimal(30, HALF_EVEN.INSTANCE,
                                RationalMath.divide(amount(a), amount(b)))));
            }
        }
    }

    @Test
    void readsAnIntOrAnAmountBesideAnExactQuotientAtItsExactValue() {
        Running module = compiled();

        for (long a : new long[] {1, -7, 22, Long.MAX_VALUE}) {
            for (long b : new long[] {3, -2, 7}) {
                Rational r = RationalMath.divideWholeNumbers(a, b);
                for (long c : new long[] {0, 1, -5}) {
                    for (String d : new String[] {"0", "2.5", "-0.125", "1E-20"}) {
                        Rational expected = RationalMath.subtract(
                                RationalMath.multiply(RationalMath.add(r, RationalMath.fromInt(c)),
                                        amount(d)),
                                r);
                        assertThat(answerOf(module, "exact.mixed",
                                "[" + a + "," + b + "," + c + "," + d + "]"))
                                .describedAs("(%d / %d + %d) * %s - %d / %d", a, b, c, d, a, b)
                                .isEqualTo(written(RationalMath.toDecimal(
                                        30, HALF_EVEN.INSTANCE, expected)));
                        assertThat(answerOf(module, "exact.atLeast",
                                "[" + a + "," + b + "," + d + "]"))
                                .describedAs("%d / %d >= %s", a, b, d)
                                .isEqualTo(truth(RationalMath.compare(r, amount(d)) >= 0));
                    }
                    assertThat(answerOf(module, "exact.below", array(a, b, c)))
                            .describedAs("%d / %d < %d", a, b, c)
                            .isEqualTo(truth(RationalMath.compare(r, RationalMath.fromInt(c)) < 0));
                }
                assertThat(answerOf(module, "exact.opposite", array(a, b)))
                        .isEqualTo(written(RationalMath.toDecimal(
                                30, HALF_EVEN.INSTANCE, RationalMath.negate(r))));
            }
        }
        assertThat(answerOf(module, "exact.same", array(1, 2, 2, 4))).isEqualTo(truth(true));
        assertThat(answerOf(module, "exact.same", array(1, 3, 333, 1000))).isEqualTo(truth(false));
        assertThat(answerOf(module, "exact.same", array(-1, 2, 1, -2))).isEqualTo(truth(true));
    }

    @Test
    void narrowsToAWholeNumberOrAnAmountAsItIsTold() {
        Running module = compiled();

        for (long a : WHOLES) {
            for (long b : new long[] {1, 2, -2, 3, 7, 10, -1}) {
                Rational r = RationalMath.divideWholeNumbers(a, b);
                if (RationalMath.compare(r, RationalMath.fromInt(Long.MAX_VALUE)) > 0) {
                    continue;
                }
                String modes = java.util.Arrays.stream(MODES)
                        .map(mode -> String.valueOf(RationalMath.toInt(mode, r)))
                        .collect(Collectors.joining(","));
                assertThat(answerOf(module, "exact.rounded", array(a, b)))
                        .describedAs("%d / %d by each mode", a, b)
                        .isEqualTo("{\"value\":[" + modes + "]}");
                Object whole = RationalMath.toWholeNumber(r);
                assertThat(answerOf(module, "exact.whole", array(a, b)))
                        .describedAs("%d / %d as a whole number", a, b)
                        .isEqualTo(whole instanceof Long n
                                ? "{\"value\":{\"whole\":true,\"n\":" + n + "}}"
                                : "{\"value\":{\"whole\":false,\"n\":0}}");
            }
        }
        for (String a : AMOUNTS) {
            for (String b : new String[] {"1", "3", "0.5", "-8", "1E-30", "7.00"}) {
                Object finite = RationalMath.toFiniteDecimal(RationalMath.divide(amount(a), amount(b)));
                assertThat(answerOf(module, "exact.finite", "[" + a + "," + b + "]"))
                        .describedAs("%s / %s as a finite decimal", a, b)
                        .isEqualTo(finite instanceof BigDecimal d
                                ? "{\"value\":{\"finite\":true,\"d\":"
                                        + Representations.canonicalNumber(d) + "}}"
                                : "{\"value\":{\"finite\":false,\"d\":0}}");
            }
        }
    }

    @Test
    void answersTheLibrarysFunctionsAsItsOperatorsDo() {
        Running module = compiled();

        for (long a : new long[] {0, 3, -9, Long.MIN_VALUE}) {
            for (String d : new String[] {"1", "-2.5", "0.003", "1E+20"}) {
                Rational x = RationalMath.fromInt(a);
                Rational y = amount(d);
                String each = List.of(RationalMath.add(x, y), RationalMath.subtract(x, y),
                                RationalMath.multiply(x, y)).stream()
                        .map(r -> String.valueOf(Representations.canonicalNumber(
                                RationalMath.toDecimal(30, HALF_EVEN.INSTANCE, r))))
                        .collect(Collectors.joining(","));
                assertThat(answerOf(module, "exact.functions", "[" + a + "," + d + "]"))
                        .describedAs("%d and %s", a, d)
                        .isEqualTo("{\"value\":[" + each + "]}");
                assertThat(answerOf(module, "exact.compared", "[" + a + "," + d + "]"))
                        .isEqualTo("{\"value\":" + RationalMath.compare(x, y) + "}");
                assertThat(answerOf(module, "exact.divided", "[" + a + "," + d + "]"))
                        .isEqualTo(written(RationalMath.toDecimal(
                                30, HALF_EVEN.INSTANCE, RationalMath.divide(x, y))));
            }
        }
    }

    @Test
    void holdsOneValueOnceInASetAndAMapAndOrdersAndTotalsThemByValue() {
        Running module = compiled();

        // 2/4 and 4/8 are a half each, as -4/4 and 4/-4 are minus one.
        assertThat(answerOf(module, "exact.distinct", "[[2,4,8,1,2,-4,4]]"))
                .isEqualTo("{\"value\":5}");
        assertThat(answerOf(module, "exact.keyed", "[[2,4,8,1,2,-4,4]]"))
                .isEqualTo("{\"value\":5}");

        long[] xs = {7, -3, 2, 1000000007, -1, 12, 3};
        List<Rational> reciprocals = new ArrayList<>();
        Rational sum = RationalMath.fromInt(0);
        Rational product = RationalMath.fromInt(1);
        for (long x : xs) {
            reciprocals.add(RationalMath.divideWholeNumbers(1, x));
            sum = RationalMath.add(sum, RationalMath.divideWholeNumbers(1, x));
            product = RationalMath.multiply(product, RationalMath.divideWholeNumbers(x, 3));
        }
        reciprocals.sort(Rational::compareTo);
        String list = "[" + java.util.Arrays.stream(xs).mapToObj(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
        assertThat(answerOf(module, "exact.sorted", "[" + list + "]"))
                .isEqualTo("{\"value\":[" + reciprocals.stream()
                        .map(r -> String.valueOf(Representations.canonicalNumber(
                                RationalMath.toDecimal(5, HALF_EVEN.INSTANCE, r))))
                        .collect(Collectors.joining(",")) + "]}");
        assertThat(answerOf(module, "exact.harmonic", "[" + list + "]"))
                .isEqualTo(written(RationalMath.toDecimal(30, HALF_EVEN.INSTANCE, sum)));
        assertThat(answerOf(module, "exact.product", "[" + list + "]"))
                .isEqualTo(written(RationalMath.toDecimal(30, HALF_EVEN.INSTANCE, product)));
        assertThat(answerOf(module, "exact.harmonic", "[[]]")).isEqualTo("{\"value\":0}");
    }

    @Test
    void endsTheCallOnAZeroDivisorAndOnAnAnswerWithNoPlace() {
        Running module = compiled();

        assertThat(abortOf(module, "exact.quotient", array(1, 0)))
                .contains(new FailureCause.Language(AbortKind.DIVISION_BY_ZERO));
        assertThat(abortOf(module, "exact.divided", "[1,0]"))
                .contains(new FailureCause.Language(AbortKind.DIVISION_BY_ZERO));
        // The least Int over minus one is a whole number no Int holds, which is not the case that
        // says it has a fraction.
        assertThat(abortOf(module, "exact.whole", array(Long.MIN_VALUE, -1)))
                .contains(new FailureCause.Language(AbortKind.REQUIRED_FORM_HAS_NO_PLACE));
        assertThat(abortOf(module, "exact.rounded", array(Long.MIN_VALUE, -1)))
                .contains(new FailureCause.Language(AbortKind.REQUIRED_FORM_HAS_NO_PLACE));
        // Still answering after both.
        assertThat(answerOf(module, "exact.quotient", array(1, 3)))
                .isEqualTo(written(RationalMath.toDecimal(30, HALF_EVEN.INSTANCE,
                        RationalMath.divideWholeNumbers(1, 3))));
    }

    private static Rational amount(String written) {
        return RationalMath.fromDecimal(new BigDecimal(written));
    }

    private static String written(BigDecimal held) {
        return "{\"value\":" + Representations.canonicalNumber(held) + "}";
    }

    private static String truth(boolean held) {
        return "{\"value\":" + held + "}";
    }

    private static String array(long... values) {
        return "[" + java.util.Arrays.stream(values).mapToObj(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(SOURCE))));
    }

    private static Optional<FailureCause> abortOf(Running module, String export, String arguments) {
        int snapshot = module.call(RuntimeAbi.FAILURE_GENERATION);
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        try {
            answerOf(module, export, arguments);
            throw new AssertionError(export + " answered " + arguments + " rather than ending");
        } catch (ChicoryException trapped) {
            var record = module.failureRecord();
            module.call(RuntimeAbi.ALLOC_RESET, mark);
            return record.describesTrapAfter(snapshot) ? record.cause() : Optional.empty();
        }
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
