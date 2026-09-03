package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.link.RuntimeLayout.ExportKind;

/**
 * What the linker reads off the runtime before it appends anything.
 *
 * <p>Every one of these is a thing a generated definition is placed against. A runtime rebuilt by
 * a different toolchain that answered differently would move what the link emitted, so they are
 * read from the module this build carries rather than assumed.
 */
class TheRuntimeIsReadWithoutDecodingACodeBodyTest {

    private static final byte[] RUNTIME = runtimeBytes();

    @Test
    void leavesTheStartSlotForTheThunkTheLinkGenerates() {
        assertThat(RuntimeLayout.of(RUNTIME).existingStart()).isEmpty();
    }

    @Test
    void importsOnlyTheOneCrossingOutOfTheModule() {
        RuntimeLayout layout = RuntimeLayout.of(RUNTIME);

        assertThat(layout.importedFunctionCount()).isEqualTo(1);
        assertThat(layout.firstGeneratedFunctionIndex())
                .isEqualTo(layout.importedFunctionCount() + layout.definedFunctionCount());
    }

    @Test
    void exportsEveryNameACallerOfALinkedModuleReachesFor() {
        RuntimeLayout layout = RuntimeLayout.of(RUNTIME);

        assertThat(layout.exports()).containsKeys(
                RuntimeAbi.MEMORY,
                RuntimeAbi.ALLOC,
                RuntimeAbi.ALLOC_MARK,
                RuntimeAbi.ALLOC_RESET,
                RuntimeAbi.RUNTIME_INIT,
                RuntimeAbi.FAILURE_ADDR,
                RuntimeAbi.FAILURE_GENERATION,
                RuntimeAbi.ABI_VERSION,
                RuntimeAbi.ABORT,
                RuntimeAbi.HOST_CALL,
                RuntimeAbi.HEAP_BASE,
                RuntimeAbi.DATA_END);
        assertThat(layout.export(RuntimeAbi.MEMORY)).get()
                .extracting(RuntimeLayout.Export::kind).isEqualTo(ExportKind.MEMORY);
        assertThat(layout.export(RuntimeAbi.HEAP_BASE)).get()
                .extracting(RuntimeLayout.Export::kind).isEqualTo(ExportKind.GLOBAL);
    }

    @Test
    void placesTheArenaAboveTheRuntimesOwnDataAndItsStack() {
        RuntimeLayout layout = RuntimeLayout.of(RUNTIME);
        int dataEnd = new LayoutReader(RUNTIME)
                .globalInitialiser(layout.export(RuntimeAbi.DATA_END).orElseThrow().index());
        int initialStackPointer = new LayoutReader(RUNTIME).globalInitialiser(0);

        assertThat(layout.heapBase(RUNTIME)).isGreaterThanOrEqualTo(dataEnd);
        // The stack grows down from where its pointer starts, so everything below that is stack.
        assertThat(layout.heapBase(RUNTIME)).isGreaterThanOrEqualTo(initialStackPointer);
    }

    @Test
    void readsHowFarUpTheDataOfAModuleThatPlacesSomeReaches() {
        byte[] module = moduleWithOneDataSegmentOfFourBytesAtSixteen();

        assertThat(RuntimeLayout.of(module).activeDataEnd(module)).isEqualTo(20);
    }

    @Test
    void offersTheTableAGeneratedSlotIsAppendedTo() {
        RuntimeLayout layout = RuntimeLayout.of(RUNTIME);

        assertThat(layout.export(RuntimeAbi.TABLE)).get()
                .extracting(RuntimeLayout.Export::kind).isEqualTo(ExportKind.TABLE);
        assertThat(layout.tableCount()).isEqualTo(1);
        assertThat(layout.exports()).containsKey(RuntimeAbi.CALL_SLOT);
    }

    /**
     * A module placing four bytes at sixteen, and nothing else.
     *
     * <p>The runtime places no data segment of its own — its statics start at zero, so the
     * toolchain reserves the room without writing bytes into it — and a reading of how far data
     * reaches has to be confirmed against a module where data reaches somewhere.
     */
    private static byte[] moduleWithOneDataSegmentOfFourBytesAtSixteen() {
        return new byte[] {
                0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
                0x05, 0x03, 0x01, 0x00, 0x01,
                0x0b, 0x0a, 0x01, 0x00, 0x41, 0x10, 0x0b, 0x04,
                (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef,
        };
    }

    @Test
    void asksForEnoughMemoryToHoldWhatItPlacedInIt() {
        RuntimeLayout layout = RuntimeLayout.of(RUNTIME);

        assertThat(layout.memoryMinimumPages() * 65536).isGreaterThanOrEqualTo(layout.heapBase(RUNTIME));
    }

    @Test
    void refusesAModuleWhoseSectionsRunPastItsEnd() {
        byte[] truncated = java.util.Arrays.copyOf(RUNTIME, RUNTIME.length - 1);

        assertThatThrownBy(() -> RuntimeLayout.of(truncated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("past the end");
    }

    @Test
    void saysSoRatherThanAnsweringForAGlobalTheModuleDoesNotDefine() {
        RuntimeLayout layout = RuntimeLayout.of(RUNTIME);

        assertThatThrownBy(() -> new LayoutReader(RUNTIME).globalInitialiser(layout.globalCount()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defines no global");
    }

    @Test
    void refusesToPlaceDataAgainstAGlobalItCannotReadAConstantFrom() {
        byte[] module = moduleWhoseOnlyGlobalIsInitialisedFromAnother();

        assertThatThrownBy(() -> new LayoutReader(module).globalInitialiser(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other than an i32 constant");
    }

    /**
     * A module whose second global is initialised from its first rather than from a constant.
     *
     * <p>Valid wasm, and not something to place an arena against: what the global holds is settled
     * by instantiation, and a linker that guessed would put the arena over a value.
     */
    private static byte[] moduleWhoseOnlyGlobalIsInitialisedFromAnother() {
        return new byte[] {
                0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
                0x06, 0x0b, 0x02,
                0x7f, 0x00, 0x41, 0x08, 0x0b,
                0x7f, 0x00, 0x23, 0x00, 0x0b,
        };
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
