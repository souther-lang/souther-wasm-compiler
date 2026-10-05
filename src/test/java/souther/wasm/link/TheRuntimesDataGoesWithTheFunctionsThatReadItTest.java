package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.ActiveDataSegment;
import com.dylibso.chicory.wasm.types.DataSegment;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.emit.WasmTreeShaker;
import souther.wasm.emit.WasmTreeShaker.OwnedDataSegment;

/**
 * A linked module carries the runtime's static data that the runtime functions it keeps read, and
 * none of the rest.
 *
 * <p>Which functions read which data is what the runtime's linker said of it ({@link RuntimeData}),
 * so the first thing held here is that it said it of every segment: a runtime built without
 * {@code --emit-relocs} would say nothing, and every module would carry all of its data again with
 * every other test still passing. Then the bytes a module carries are held to the bytes of the
 * segments its kept functions read, counted by what they write: written together, a few segments
 * have zeros between them, which memory holds before anything is written.
 */
class TheRuntimesDataGoesWithTheFunctionsThatReadItTest {

    private static final CheckedProgram FEE = Compiled.program(List.of("""
            module a

            behavior fee : (total: Int) -> Int

            let fee (total) = if total >= 5000 then 0 else 500
            """));

    private static final CheckedProgram ECHO = Compiled.program(List.of("""
            module b

            behavior same : (s: String) -> String

            let same (s) = s
            """));

    private static final byte[] RUNTIME = Running.runtimeModule();

    @Test
    void saysWhichFunctionsReadEverySegmentOfTheRuntime() {
        List<OwnedDataSegment> owners = RuntimeData.owners(RUNTIME);

        assertThat(owners).hasSize(RuntimeLayout.of(RUNTIME).dataSegmentCount());
        assertThat(owners).allSatisfy(each -> assertThat(each.ownerFuncIndices())
                .describedAs("what reads segment %d", each.segmentIndex()).isNotEmpty());
    }

    @Test
    void carriesWhatTheFunctionsItKeepsReadAndNothingElse() {
        for (CheckedProgram program : List.of(FEE, ECHO)) {
            assertThat(carried(Compiled.module(program))).isEqualTo(read(Compiled.unshaken(program)));
        }
    }

    @Test
    void carriesWhatReadingTextReadsOnlyWhereTextIsRead() {
        assertThat(carried(Compiled.module(FEE))).isLessThan(carried(Compiled.module(ECHO)));
    }

    /** The bytes other than zeros the runtime's segments write, of those a kept function reads. */
    private static long read(byte[] unshaken) {
        WasmTreeShaker.Reach[] reached = WasmTreeShaker.reached(unshaken);
        DataSegment[] segments = Parser.parse(RUNTIME).dataSection().dataSegments();
        long written = 0;
        for (OwnedDataSegment each : RuntimeData.owners(RUNTIME)) {
            for (int reader : each.ownerFuncIndices()) {
                if (reached[reader] != null) {
                    written += nonZero(segments[each.segmentIndex()].data());
                    break;
                }
            }
        }
        return written;
    }

    /** The bytes other than zeros a module writes below where the runtime's data ends. */
    private static long carried(byte[] module) {
        int end = RuntimeLayout.of(RUNTIME).heapBase(RUNTIME);
        long written = 0;
        for (DataSegment each : Parser.parse(module).dataSection().dataSegments()) {
            long at = ((ActiveDataSegment) each).offsetInstructions().getFirst().operand(0);
            if (at < end) {
                written += nonZero(each.data());
            }
        }
        return written;
    }

    private static long nonZero(byte[] bytes) {
        long count = 0;
        for (byte each : bytes) {
            if (each != 0) {
                count++;
            }
        }
        return count;
    }
}
