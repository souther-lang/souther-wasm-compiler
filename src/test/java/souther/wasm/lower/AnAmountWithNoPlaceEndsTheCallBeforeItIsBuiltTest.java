package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import souther.compiler.abort.AbortKind;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.FailureCause;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.abi.WasmFault;

/**
 * An amount an operation would answer with, where the runtime has no place to write that amount.
 *
 * <p>A {@code Decimal} is any finite decimal, so a rounding, a quotient and a sum always have an
 * answer, and the runtime keeps one in a whole number held to the width the JVM's
 * {@code BigInteger} stops at. An answer wider than that is the platform failing and not the
 * language refusing a value, and the call ends as one out of room. A text longer than a
 * {@code String} holds is the language's refusal, and ends the call as
 * {@code REQUIRED_FORM_HAS_NO_PLACE} (spec
 * §an-operation-refuses-only-what-its-own-answer-has-no-place-for). Each one here would be a number
 * of some two billion bits, or a string of hundreds of millions of characters, and the call ends
 * before any of it is built.
 */
class AnAmountWithNoPlaceEndsTheCallBeforeItIsBuiltTest {

    private static final String SOURCE = """
            module amounts

            behavior rounded : (d: Decimal, places: Int) -> Decimal

            let rounded (d, places) = Decimal.round(places, HALF_UP, d)

            behavior shared : (a: Decimal, b: Decimal, places: Int) -> Decimal

            let shared (a, b, places) = match Decimal.divide(a, b, places, HALF_UP) with
                | Decimal as d -> d
                | DivisionByZero -> Decimal.fromInt(-1)

            behavior sum : (a: Decimal, b: Decimal) -> Decimal

            let sum (a, b) = a + b

            behavior shown : (d: Decimal) -> String

            let shown (d) = String.fromDecimal(d)
            """;

    @Test
    void roundingOrDividingToAScaleTheRuntimeHasNoRoomForEndsTheCallAsThePlatformFailing() {
        Running module = compiled();

        assertThat(abortOf(module, "amounts.rounded", "[1,2000000000]"))
                .contains(new FailureCause.Wasm(WasmFault.OUT_OF_MEMORY));
        assertThat(abortOf(module, "amounts.shared", "[1,1,2000000000]"))
                .contains(new FailureCause.Wasm(WasmFault.OUT_OF_MEMORY));
        assertThat(answerOf(module, "amounts.rounded", "[1,3]")).isEqualTo("{\"value\":1}");
    }

    @Test
    void aSumAcrossScalesTooFarApartToWriteEndsTheCallAsThePlatformFailing() {
        Running module = compiled();

        // The sum is at the larger scale, so the one is spelt out seven hundred million places.
        assertThat(abortOf(module, "amounts.sum", "[1e-700000000,1]"))
                .contains(new FailureCause.Wasm(WasmFault.OUT_OF_MEMORY));
        assertThat(answerOf(module, "amounts.sum", "[1e-3,1]")).isEqualTo("{\"value\":1.001}");
    }

    @Test
    void writingOutAnAmountLongerThanAStringHoldsEndsTheCall() {
        Running module = compiled();

        assertThat(abortOf(module, "amounts.shown", "[1e-300000000]"))
                .contains(new FailureCause.Language(AbortKind.REQUIRED_FORM_HAS_NO_PLACE));
        assertThat(answerOf(module, "amounts.shown", "[1e-3]")).isEqualTo("{\"value\":\"0.001\"}");
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
