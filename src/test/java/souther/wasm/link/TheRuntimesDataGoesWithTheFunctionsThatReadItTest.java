package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ActiveDataSegment;
import com.dylibso.chicory.wasm.types.DataSegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

    /** The runtime's segments, read once: parsing the runtime is most of what this test costs. */
    private static final DataSegment[] SEGMENTS = Parser.parse(RUNTIME).dataSection().dataSegments();

    @Test
    void saysWhichFunctionsReadEverySegmentOfTheRuntimeButWhatAGlobalPointsInto() {
        Set<Integer> claimed = RuntimeData.owners(RUNTIME).stream()
                .map(OwnedDataSegment::segmentIndex).collect(Collectors.toSet());

        assertThat(RuntimeData.owners(RUNTIME)).allSatisfy(each -> assertThat(each.ownerFuncIndices())
                .describedAs("what reads segment %d", each.segmentIndex()).isNotEmpty());
        assertThat(IntStream.range(0, SEGMENTS.length).filter(i -> !claimed.contains(i)).boxed().toList())
                .isEqualTo(pointedAtByGlobals());
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

    /**
     * The bytes other than zeros the runtime's segments write, of those a kept function reads and
     * those nothing was said to read, which are kept whatever is.
     */
    private static long read(byte[] unshaken) {
        WasmTreeShaker.Reach[] reached = WasmTreeShaker.reached(unshaken);
        Map<Integer, int[]> readers = RuntimeData.owners(RUNTIME).stream()
                .collect(Collectors.toMap(OwnedDataSegment::segmentIndex, OwnedDataSegment::ownerFuncIndices));
        long written = 0;
        for (int i = 0; i < SEGMENTS.length; i++) {
            int[] reading = readers.get(i);
            if (reading == null || Arrays.stream(reading).anyMatch(reader -> reached[reader] != null)) {
                written += nonZero(SEGMENTS[i].data());
            }
        }
        return written;
    }

    /** The segments a global's initial value is an address inside. */
    private static List<Integer> pointedAtByGlobals() {
        WasmModule module = Parser.parse(RUNTIME);
        List<Integer> pointed = new ArrayList<>();
        for (int i = 0; i < SEGMENTS.length; i++) {
            long at = start(SEGMENTS[i]);
            for (int g = 0; g < module.globalSection().globalCount(); g++) {
                long value = module.globalSection().getGlobal(g).initInstructions().getFirst().operand(0);
                if (value >= at && value < at + SEGMENTS[i].data().length) {
                    pointed.add(i);
                    break;
                }
            }
        }
        return pointed;
    }

    private static long start(DataSegment segment) {
        return ((ActiveDataSegment) segment).offsetInstructions().getFirst().operand(0);
    }

    /** The bytes other than zeros a module writes below where the runtime's data ends. */
    private static long carried(byte[] module) {
        int end = RuntimeLayout.of(RUNTIME).heapBase(RUNTIME);
        long written = 0;
        for (DataSegment each : Parser.parse(module).dataSection().dataSegments()) {
            long at = start(each);
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
