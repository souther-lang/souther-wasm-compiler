package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
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
        RuntimeUnderTest runtime = RuntimeUnderTest.started();

        assertThat(runtime.callInt(RuntimeAbi.ABI_VERSION)).isEqualTo(RuntimeAbi.VERSION);
    }

    @Test
    void placesTheArenaAboveEverythingStaticTheLinkPut() {
        RuntimeUnderTest runtime = RuntimeUnderTest.started();

        int failure = runtime.callInt(RuntimeAbi.FAILURE_ADDR);
        int first = runtime.call(RuntimeAbi.ALLOC, 4);

        assertThat(failure).isGreaterThanOrEqualTo(runtime.staticEnd());
        assertThat(first).isGreaterThanOrEqualTo(failure + RuntimeAbi.FAILURE_BYTES);
    }

    @Test
    void handsOutBytesNoOtherAllocationHolds() {
        RuntimeUnderTest runtime = RuntimeUnderTest.started();

        int first = runtime.call(RuntimeAbi.ALLOC, 40);
        int second = runtime.call(RuntimeAbi.ALLOC, 40);

        assertThat(second).isGreaterThanOrEqualTo(first + 40);
    }

    @Test
    void popsBackToAMarkSoTheNextCallReusesWhatTheLastOneTook() {
        RuntimeUnderTest runtime = RuntimeUnderTest.started();

        int mark = runtime.callInt(RuntimeAbi.ALLOC_MARK);
        runtime.call(RuntimeAbi.ALLOC, 1024);
        runtime.call(RuntimeAbi.ALLOC_RESET, mark);

        assertThat(runtime.call(RuntimeAbi.ALLOC, 8)).isEqualTo(mark);
    }

    @Test
    void growsMemoryForAnAllocationPastWhatIsMapped() {
        RuntimeUnderTest runtime = RuntimeUnderTest.started();

        int big = runtime.call(RuntimeAbi.ALLOC, 5 * 65536);

        assertThat(big).isPositive();
        runtime.write(big, "x".getBytes(StandardCharsets.UTF_8));
        assertThat(runtime.read(big, 1)).containsExactly((byte) 'x');
    }

    @Test
    void writesTheReasonForATrapWhereNoArenaResetCanReachIt() {
        RuntimeUnderTest runtime = RuntimeUnderTest.started();
        int snapshot = runtime.callInt(RuntimeAbi.FAILURE_GENERATION);
        int mark = runtime.callInt(RuntimeAbi.ALLOC_MARK);

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
        RuntimeUnderTest runtime = RuntimeUnderTest.started();
        int mark = runtime.callInt(RuntimeAbi.ALLOC_MARK);
        assertThatThrownBy(() -> runtime.call(RuntimeAbi.ALLOC_RESET, mark - 8))
                .isInstanceOf(ChicoryException.class);
        runtime.call(RuntimeAbi.ALLOC_RESET, mark);

        // A caller that snapshots after that abort and then sees a trap of its own reads the same
        // record, and has to be told the record is not about the trap it just saw.
        int snapshot = runtime.callInt(RuntimeAbi.FAILURE_GENERATION);

        assertThat(runtime.failureRecord().describesTrapAfter(snapshot)).isFalse();
    }

    /** The module this build carries, instantiated and placed, with the arena ready to hand out. */
    private static final class RuntimeUnderTest {

        private final Instance instance;

        private RuntimeUnderTest(Instance instance) {
            this.instance = instance;
        }

        static RuntimeUnderTest started() {
            var hostCall = new HostFunction(
                    RuntimeAbi.IMPORT_MODULE,
                    RuntimeAbi.IMPORT_HOST_CALL,
                    List.of(
                            ValType.I32,
                            ValType.I32,
                            ValType.I32,
                            ValType.I32,
                            ValType.I32),
                    List.of(ValType.I32),
                    (inst, args) -> new long[] {0});
            var imports = ImportValues.builder()
                    .addFunction(hostCall)
                    .build();
            var instance = Instance
                    .builder(Parser.parse(runtimeBytes()))
                    .withImportValues(imports)
                    .build();
            RuntimeUnderTest runtime = new RuntimeUnderTest(instance);
            runtime.call(RuntimeAbi.RUNTIME_INIT, runtime.staticEnd());
            return runtime;
        }

        int staticEnd() {
            byte[] bytes = runtimeBytes();
            return RuntimeLayout.of(bytes).heapBase(bytes);
        }

        int call(String export, int argument) {
            long[] answer = instance.export(export).apply(argument);
            return answer == null || answer.length == 0 ? 0 : (int) answer[0];
        }

        int callInt(String export) {
            return (int) instance.export(export).apply()[0];
        }

        void write(int address, byte[] bytes) {
            instance.memory().write(address, bytes);
        }

        byte[] read(int address, int length) {
            return instance.memory().readBytes(address, length);
        }

        FailureRecord failureRecord() {
            int address = callInt(RuntimeAbi.FAILURE_ADDR);
            ByteBuffer memory = ByteBuffer
                    .wrap(read(address, RuntimeAbi.FAILURE_BYTES))
                    .order(ByteOrder.LITTLE_ENDIAN);
            return FailureRecord.read(memory, 0);
        }

        private static byte[] runtimeBytes() {
            try (InputStream in = RuntimeAbi.class.getResourceAsStream("/souther/wasm/runtime.wasm")) {
                if (in == null) {
                    throw new IllegalStateException("this build carries no runtime module");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
