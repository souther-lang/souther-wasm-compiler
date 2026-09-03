package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.link.RuntimeLayout;

/**
 * The runtime's side of {@link RuntimeAbi}, read off the module this build carries.
 *
 * <p>A constant naming an export is a claim about a file that is compiled somewhere else, and the
 * two drift the moment nobody looks. These run the module.
 */
class TheRuntimeAnswersTheAbiItIsCompiledAgainstTest {

    @Test
    void answersTheAbiVersionThisCompilerEmitsAgainst() {
        Running runtime = Running.bareRuntime();

        assertThat(runtime.call(RuntimeAbi.ABI_VERSION)).isEqualTo(RuntimeAbi.VERSION);
    }

    @Test
    void placesTheArenaAboveEverythingStaticItWasToldAbout() {
        Running runtime = Running.bareRuntime();
        byte[] module = Running.runtimeModule();
        int staticEnd = RuntimeLayout.of(module).heapBase(module);

        int failure = runtime.call(RuntimeAbi.FAILURE_ADDR);
        int first = runtime.call(RuntimeAbi.ALLOC, 4);

        assertThat(failure).isGreaterThanOrEqualTo(staticEnd);
        assertThat(first).isGreaterThanOrEqualTo(failure + RuntimeAbi.FAILURE_BYTES);
    }

    @Test
    void handsOutBytesNoOtherAllocationHolds() {
        Running runtime = Running.bareRuntime();

        int first = runtime.call(RuntimeAbi.ALLOC, 40);
        int second = runtime.call(RuntimeAbi.ALLOC, 40);

        assertThat(second).isGreaterThanOrEqualTo(first + 40);
    }

    @Test
    void popsBackToAMarkSoTheNextCallReusesWhatTheLastOneTook() {
        Running runtime = Running.bareRuntime();

        int mark = runtime.call(RuntimeAbi.ALLOC_MARK);
        runtime.call(RuntimeAbi.ALLOC, 1024);
        runtime.call(RuntimeAbi.ALLOC_RESET, mark);

        assertThat(runtime.call(RuntimeAbi.ALLOC, 8)).isEqualTo(mark);
    }

    @Test
    void growsMemoryForAnAllocationPastWhatIsMapped() {
        Running runtime = Running.bareRuntime();

        int big = runtime.call(RuntimeAbi.ALLOC, 5 * 65536);

        assertThat(big).isPositive();
        runtime.write(big, "x".getBytes(StandardCharsets.UTF_8));
        assertThat(runtime.read(big, 1)).containsExactly((byte) 'x');
    }

    @Test
    void writesTheReasonForATrapWhereNoArenaResetCanReachIt() {
        Running runtime = Running.bareRuntime();
        int snapshot = runtime.call(RuntimeAbi.FAILURE_GENERATION);
        int mark = runtime.call(RuntimeAbi.ALLOC_MARK);

        assertThatThrownBy(() -> runtime.call(RuntimeAbi.ALLOC_RESET, mark - 8))
                .isInstanceOf(ChicoryException.class);

        runtime.call(RuntimeAbi.ALLOC_RESET, mark);
        FailureRecord record = runtime.failureRecord();
        assertThat(record.describesTrapAfter(snapshot)).isTrue();
        assertThat(record.namedReason()).contains(AbortReason.BAD_MARK);
        assertThat(record.aux1()).isEqualTo(mark);
    }

    @Test
    void leavesTheRecordAloneWhenTheTrapWasNotSouthersOwn() {
        Running runtime = Running.bareRuntime();
        int mark = runtime.call(RuntimeAbi.ALLOC_MARK);
        assertThatThrownBy(() -> runtime.call(RuntimeAbi.ALLOC_RESET, mark - 8))
                .isInstanceOf(ChicoryException.class);
        runtime.call(RuntimeAbi.ALLOC_RESET, mark);

        // A caller that snapshots after that abort and then sees a trap of its own reads the same
        // record, and has to be told the record is not about the trap it just saw.
        int snapshot = runtime.call(RuntimeAbi.FAILURE_GENERATION);

        assertThat(runtime.failureRecord().describesTrapAfter(snapshot)).isFalse();
    }
}
