package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.AbortReason;
import souther.wasm.abi.FailureRecord;
import souther.wasm.abi.RuntimeAbi;

/**
 * A construction attempted rather than made, and a position the program says gets no value.
 *
 * <p>The check is the one a construction would have ended the call on, read as an answer instead.
 * Which departure a failure takes is settled by the clause that failed.
 */
class AnAttemptedConstructionBranchesOnWhatMustHoldTest {

    @Test
    void takesTheValueWhereWhatMustHoldDoes() {
        Running module = compiled("""
                module counting

                data Positive = { n: Int }
                    invariant kept = n > 0

                behavior doubled : (x: Int) -> Int constructs Positive

                let doubled (x) = if Positive { n = x } as p then p.n + p.n else -1
                """);

        assertThat(answerOf(module, "counting.doubled", "[3]")).isEqualTo("{\"value\":6}");
        assertThat(answerOf(module, "counting.doubled", "[0]")).isEqualTo("{\"value\":-1}");
    }

    @Test
    void departsByTheClauseThatFailed() {
        Running module = compiled("""
                module ranging

                data Between = { n: Int }
                    invariant low = n > 0
                    invariant high = n < 10

                behavior placed : (x: Int) -> Int constructs Between

                let placed (x) = if Between { n = x } as b then b.n
                    else | low -> -1
                         | high -> -2
                """);

        assertThat(answerOf(module, "ranging.placed", "[5]")).isEqualTo("{\"value\":5}");
        assertThat(answerOf(module, "ranging.placed", "[0]")).isEqualTo("{\"value\":-1}");
        assertThat(answerOf(module, "ranging.placed", "[20]")).isEqualTo("{\"value\":-2}");
    }

    @Test
    void endsTheCallWhereTheProgramSaidThereIsNoValue() {
        Running module = compiled("""
                module counting

                behavior only : (x: Int) -> Int

                let only (x) = if x > 0 then x else unreachable "a count is never under one"
                """);

        assertThat(answerOf(module, "counting.only", "[3]")).isEqualTo("{\"value\":3}");

        FailureRecord record = endOf(module, "counting.only", "[0]");
        assertThat(record.namedReason()).contains(AbortReason.NOTHING_TO_ANSWER_WITH);
        assertThat(reasonIn(module, record)).isEqualTo("a count is never under one");
    }

    /** The reason an abort carries, which is in static memory and outlives the arena. */
    private static String reasonIn(Running module, FailureRecord record) {
        return new String(module.read((int) record.aux0(), (int) record.aux1()),
                StandardCharsets.UTF_8);
    }

    private static FailureRecord endOf(Running module, String export, String arguments) {
        int snapshot = module.call(RuntimeAbi.FAILURE_GENERATION);
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        try {
            answerOf(module, export, arguments);
            throw new AssertionError(export + " answered " + arguments + " rather than ending");
        } catch (ChicoryException trapped) {
            FailureRecord record = module.failureRecord();
            module.call(RuntimeAbi.ALLOC_RESET, mark);
            assertThat(record.describesTrapAfter(snapshot)).isTrue();
            return record;
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
