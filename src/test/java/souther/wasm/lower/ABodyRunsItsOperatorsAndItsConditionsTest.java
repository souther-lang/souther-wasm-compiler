package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import souther.compiler.abort.AbortKind;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.FailureCause;
import souther.wasm.abi.RuntimeAbi;

/**
 * What a body does between reading its arguments and answering.
 *
 * <p>Arithmetic that leaves the range an {@code Int} holds ends the call rather than answering a
 * wrapped number: nothing an {@code Int} holds is the answer, and a wrapped one would be a
 * different number standing quietly where the right one was.
 */
class ABodyRunsItsOperatorsAndItsConditionsTest {

    @Test
    void answersWhatTheArithmeticComesTo() {
        Running module = compiled("""
                module counting

                behavior sum : (a: Int, b: Int) -> Int

                let sum (a, b) = a + b

                behavior product : (a: Int, b: Int) -> Int

                let product (a, b) = a * b

                behavior difference : (a: Int, b: Int) -> Int

                let difference (a, b) = a - b
                """);

        assertThat(answerOf(module, "counting.sum", "[2, 3]")).isEqualTo("{\"value\":5}");
        assertThat(answerOf(module, "counting.difference", "[2, 3]")).isEqualTo("{\"value\":-1}");
        assertThat(answerOf(module, "counting.product", "[2, 3]")).isEqualTo("{\"value\":6}");
    }

    @Test
    void endsTheCallWhereTheAnswerIsNotANumberAnIntHolds() {
        Running module = compiled("""
                module counting

                behavior sum : (a: Int, b: Int) -> Int

                let sum (a, b) = a + b
                """);

        assertThat(abortOf(module, "counting.sum", "[9223372036854775807, 1]"))
                .contains(new FailureCause.Language(AbortKind.REQUIRED_FORM_HAS_NO_PLACE));
    }

    /**
     * A quotient of two Ints is a Rational while its operands stay Ints, so the operands say
     * nothing about it. What this witnesses is the refusal and nothing else: the helper takes the
     * quotient and answers a constant, so no Rational kernel stands between the division and the
     * refusal, and a compile that goes through is an Int division written for a Rational.
     */
    @Test
    void refusesAnExactQuotientUntilRationalsAreWritten() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module counting

                behavior halved : (a: Int, b: Int) -> Int

                let ignoring (exact: Rational): Int = 1

                let halved (a, b) = ignoring(a / b)
                """));

        assertThatThrownBy(() -> WasmCompiler.compile(program))
                .isInstanceOf(NotLowered.class)
                .hasMessageContaining("Rational");
    }

    @Test
    void answersWhetherOneValueStandsAsAskedToAnother() {
        Running module = compiled("""
                module comparing

                behavior same : (a: Int, b: Int) -> Bool

                let same (a, b) = a == b

                behavior under : (a: Int, b: Int) -> Bool

                let under (a, b) = a < b

                behavior atLeast : (a: Int, b: Int) -> Bool

                let atLeast (a, b) = a >= b
                """);

        assertThat(answerOf(module, "comparing.same", "[2, 2]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "comparing.same", "[2, 3]")).isEqualTo("{\"value\":false}");
        assertThat(answerOf(module, "comparing.under", "[2, 3]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "comparing.under", "[3, 3]")).isEqualTo("{\"value\":false}");
        assertThat(answerOf(module, "comparing.atLeast", "[3, 3]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "comparing.atLeast", "[2, 3]")).isEqualTo("{\"value\":false}");
    }

    @Test
    void comparesTwoStringsWhereTwoStringsAreCompared() {
        Running module = compiled("""
                module wording

                behavior same : (a: String, b: String) -> Bool

                let same (a, b) = a == b

                behavior before : (a: String, b: String) -> Bool

                let before (a, b) = a < b
                """);

        assertThat(answerOf(module, "wording.same", "[\"a\", \"a\"]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "wording.before", "[\"a\", \"b\"]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "wording.before", "[\"b\", \"a\"]")).isEqualTo("{\"value\":false}");
    }

    @Test
    void joinsTwoStrings() {
        Running module = compiled("""
                module wording

                behavior joined : (a: String, b: String) -> String

                let joined (a, b) = a ++ b
                """);

        assertThat(answerOf(module, "wording.joined", "[\"ご\", \"きげん\"]"))
                .isEqualTo("{\"value\":\"ごきげん\"}");
    }

    @Test
    void takesTheWayTheConditionWent() {
        Running module = compiled("""
                module choosing

                behavior larger : (a: Int, b: Int) -> Int

                let larger (a, b) = if a > b then a else b
                """);

        assertThat(answerOf(module, "choosing.larger", "[2, 3]")).isEqualTo("{\"value\":3}");
        assertThat(answerOf(module, "choosing.larger", "[5, 3]")).isEqualTo("{\"value\":5}");
    }

    @Test
    void leavesTheSecondOperandOfAConditionUnrunWhereTheFirstSettlesIt() {
        Running module = compiled("""
                module guarding

                behavior safe : (a: Int, b: Int) -> Bool

                let safe (a, b) = b == 0 || Int.floorMod(a, b) > 1

                behavior both : (a: Int, b: Int) -> Bool

                let both (a, b) = b /= 0 && Int.floorMod(a, b) > 1
                """);

        // The floor modulo would end the call if it ran, and the first operand says it does not.
        assertThat(answerOf(module, "guarding.safe", "[1, 0]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "guarding.both", "[1, 0]")).isEqualTo("{\"value\":false}");
        assertThat(answerOf(module, "guarding.safe", "[5, 3]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "guarding.both", "[5, 3]")).isEqualTo("{\"value\":true}");
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
