package souther.wasm.link;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import souther.wasm.abi.RuntimeAbi;

/**
 * What the runtime module occupies, read without decoding a single code body.
 *
 * <p>The runtime is compiled from Rust and copied verbatim. Its instructions are whatever LLVM
 * chose, which is not a set anything here enumerates, so the reading stops at the framing: how
 * many entries each index space already holds, which names it exports, where its static data
 * ends, how much memory it asks for, and whether it claimed the start slot. A generated
 * definition is then emitted at its final index rather than relocated afterwards.
 *
 * <p>A constant expression is read where one appears — a global's initialiser and a segment's
 * offset are const expressions rather than code, and a linker that could not read them would
 * have nowhere to put what it appends.
 *
 * @param typeCount types the runtime declares
 * @param importedFunctionCount function imports, which precede every defined function
 * @param definedFunctionCount functions the runtime defines
 * @param globalCount globals, imported and defined together
 * @param tableCount tables, imported and defined together
 * @param memoryMinimumPages pages the memory asks for at instantiation
 * @param memoryMaximumPages pages the memory will not grow past, where it says
 * @param dataSegmentCount data segments, which a {@code DataCount} section must agree with
 * @param declaresDataCount whether the module carries a {@code DataCount} section
 * @param existingStart the function the runtime starts with, where it claimed the slot
 * @param exports every export by name
 */
public record RuntimeLayout(
        int typeCount,
        int importedFunctionCount,
        int definedFunctionCount,
        int globalCount,
        int tableCount,
        int memoryMinimumPages,
        OptionalInt memoryMaximumPages,
        int dataSegmentCount,
        boolean declaresDataCount,
        OptionalInt existingStart,
        Map<String, Export> exports) {

    /** An entry of the export section: what kind of thing it names, and which one. */
    public record Export(ExportKind kind, int index) {
    }

    /** Which index space an export names. */
    public enum ExportKind {
        FUNCTION, TABLE, MEMORY, GLOBAL
    }

    /** The first function index a generated definition may take. */
    public int firstGeneratedFunctionIndex() {
        return importedFunctionCount + definedFunctionCount;
    }

    /** The first type index a generated type may take. */
    public int firstGeneratedTypeIndex() {
        return typeCount;
    }

    /** The export by that name, where the runtime declares one. */
    public Optional<Export> export(String name) {
        return Optional.ofNullable(exports.get(name));
    }

    /**
     * Reads the layout of a core wasm module.
     *
     * @param module the eight-byte header followed by sections
     */
    public static RuntimeLayout of(byte[] module) {
        return new LayoutReader(module).read();
    }

    /**
     * Where the runtime's own static data ends, hence where the link's own data may begin.
     *
     * <p>Taken from the {@code __heap_base} global the runtime's link exports. Reading it is the
     * whole reason a linker may look at the global section: the arena is placed above everything
     * static, and what is static is not settled until the appended segments are placed, so the
     * runtime cannot answer it about itself.
     *
     * @param module the runtime module, whose layout this is
     */
    public int heapBase(byte[] module) {
        Export heapBase = export(RuntimeAbi.HEAP_BASE).orElseThrow(() -> new IllegalArgumentException(
                "the runtime exports no " + RuntimeAbi.HEAP_BASE + ", so where its data ends is unknown"));
        if (heapBase.kind() != ExportKind.GLOBAL) {
            throw new IllegalArgumentException(RuntimeAbi.HEAP_BASE + " is exported, but not as a global");
        }
        return new LayoutReader(module).globalInitialiser(heapBase.index());
    }

    /**
     * The first byte past every active data segment this module places.
     *
     * <p>Derived from the segments, so it says nothing about a shadow stack or anything else the
     * toolchain reserved without writing bytes into. {@link #heapBase} is the number to place
     * against; this is what an independent reading of the module can confirm it against, and what
     * a linker appending its own segments has to clear.
     *
     * @param module the runtime module, whose layout this is
     */
    public int activeDataEnd(byte[] module) {
        return new LayoutReader(module).activeDataEnd();
    }
}
