package souther.wasm.link;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import souther.wasm.emit.WasmTreeShaker.OwnedDataSegment;
import souther.wasm.link.LayoutReader.RawSection;

/**
 * Which of the runtime's functions read each of its data segments, read off what its linker left
 * in it.
 *
 * <p>A body's address of static data is an {@code i32.const} like any other number, and a table of
 * text whose entries point at more text holds its pointers as plain words, so neither can be told
 * from the module apart from numbers. The linker that placed the data could, and the runtime is
 * built with it saying so ({@code --emit-relocs}): a {@code linking} section saying which data
 * segment each data symbol lies in, and a {@code reloc.CODE} and a {@code reloc.DATA} saying where
 * each body and each segment names one ({@code --no-merge-data-segments} keeps each symbol's data a
 * segment of its own). Read here once, as the functions each segment is read by, directly or through
 * the segments that point at it, which is what a shake asks to drop a segment no surviving function
 * reads ({@link OwnedDataSegment}).
 *
 * <p>What it says is what the linker resolved, and nothing here resolves anything again: the data
 * stays where the linker put it, and a segment dropped leaves zeros at its addresses, which nothing
 * left reads. A runtime built without the sections says nothing, and every segment of it is kept.
 *
 * @see <a href="https://github.com/WebAssembly/tool-conventions/blob/main/Linking.md">Linking</a>
 */
final class RuntimeData {

    private static final int SEC_CUSTOM = 0;
    private static final int SEC_CODE = 10;
    private static final int SEC_DATA = 11;

    private static final int SUBSECTION_SYMBOL_TABLE = 8;

    private static final int SYMBOL_FUNCTION = 0;
    private static final int SYMBOL_DATA = 1;
    private static final int SYMBOL_GLOBAL = 2;
    private static final int SYMBOL_SECTION = 3;
    private static final int SYMBOL_TAG = 4;
    private static final int SYMBOL_TABLE = 5;

    private static final int FLAG_UNDEFINED = 0x10;
    private static final int FLAG_EXPLICIT_NAME = 0x40;

    private RuntimeData() {
    }

    /**
     * The runtime's data segments each with the functions that read it, in the runtime's own index
     * spaces, which a link keeps: the runtime's functions and segments come first in a linked module.
     *
     * @param runtime the compiled runtime module
     * @return one claim per segment, or none for a runtime its linker said nothing about
     */
    static List<OwnedDataSegment> owners(byte[] runtime) {
        // Every compile links onto the runtime this build carries, so what it says is read once for
        // it rather than once per link: its linker's sections are most of its bytes.
        Read last = lastRead;
        if (last != null && Arrays.equals(last.runtime(), runtime)) {
            return last.owners();
        }
        List<OwnedDataSegment> owners = read(runtime);
        lastRead = new Read(runtime.clone(), owners);
        return owners;
    }

    /** The last runtime read, and what was read off it. */
    private record Read(byte[] runtime, List<OwnedDataSegment> owners) {
    }

    private static volatile @Nullable Read lastRead;

    private static List<OwnedDataSegment> read(byte[] runtime) {
        Map<String, byte[]> custom = new HashMap<>();
        byte[] code = null;
        byte[] data = null;
        for (RawSection section : new LayoutReader(runtime).rawSections()) {
            switch (section.id()) {
                case SEC_CUSTOM -> {
                    Reading at = new Reading(section.payload(), 0);
                    String name = at.name();
                    custom.put(name, Arrays.copyOfRange(section.payload(), at.position, section.payload().length));
                }
                case SEC_CODE -> code = section.payload();
                case SEC_DATA -> data = section.payload();
                default -> { }
            }
        }
        byte[] linking = custom.get("linking");
        if (linking == null || code == null || data == null) {
            return List.of();
        }
        int importedFunctions = new LayoutReader(runtime).read().importedFunctionCount();
        int[] symbolSegments = symbolSegments(linking);
        int[] bodies = bodyStarts(code);
        int[] segments = segmentStarts(data);

        // What each function and each segment names, as segments.
        Map<Integer, BitSet> byFunction = new HashMap<>();
        BitSet[] bySegment = new BitSet[segments.length];
        for (int i = 0; i < segments.length; i++) {
            bySegment[i] = new BitSet();
        }
        for (int[] each : addresses(custom.get("reloc.CODE"))) {
            int segment = symbolSegments[each[1]];
            if (segment >= 0) {
                int function = importedFunctions + within(bodies, each[0]);
                byFunction.computeIfAbsent(function, ignored -> new BitSet()).set(segment);
            }
        }
        for (int[] each : addresses(custom.get("reloc.DATA"))) {
            int segment = symbolSegments[each[1]];
            if (segment >= 0) {
                bySegment[within(segments, each[0])].set(segment);
            }
        }

        // Each function's segments, closed over what they point at, turned round.
        List<List<Integer>> readers = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            readers.add(new ArrayList<>());
        }
        for (Map.Entry<Integer, BitSet> each : byFunction.entrySet()) {
            BitSet reached = (BitSet) each.getValue().clone();
            BitSet unwalked = (BitSet) reached.clone();
            for (int s = unwalked.nextSetBit(0); s >= 0; s = unwalked.nextSetBit(0)) {
                unwalked.clear(s);
                BitSet further = (BitSet) bySegment[s].clone();
                further.andNot(reached);
                reached.or(further);
                unwalked.or(further);
            }
            for (int s = reached.nextSetBit(0); s >= 0; s = reached.nextSetBit(s + 1)) {
                readers.get(s).add(each.getKey());
            }
        }
        List<OwnedDataSegment> owned = new ArrayList<>(segments.length);
        for (int i = 0; i < segments.length; i++) {
            owned.add(new OwnedDataSegment(i, readers.get(i).stream().mapToInt(Integer::intValue).toArray()));
        }
        return List.copyOf(owned);
    }

    /** The data segment each symbol lies in, by the symbol's index, or minus one for another kind. */
    private static int[] symbolSegments(byte[] linking) {
        Reading at = new Reading(linking, 0);
        at.unsigned(); // version
        while (at.position < linking.length) {
            int kind = at.next();
            int length = at.unsigned();
            int end = at.position + length;
            if (kind != SUBSECTION_SYMBOL_TABLE) {
                at.position = end;
                continue;
            }
            int count = at.unsigned();
            int[] segments = new int[count];
            for (int i = 0; i < count; i++) {
                int symbol = at.next();
                int flags = at.unsigned();
                segments[i] = -1;
                switch (symbol) {
                    case SYMBOL_FUNCTION, SYMBOL_GLOBAL, SYMBOL_TAG, SYMBOL_TABLE -> {
                        at.unsigned();
                        if ((flags & FLAG_UNDEFINED) == 0 || (flags & FLAG_EXPLICIT_NAME) != 0) {
                            at.name();
                        }
                    }
                    case SYMBOL_DATA -> {
                        at.name();
                        if ((flags & FLAG_UNDEFINED) == 0) {
                            segments[i] = at.unsigned();
                            at.unsigned(); // offset within the segment
                            at.unsigned(); // size
                        }
                    }
                    case SYMBOL_SECTION -> at.unsigned();
                    default -> throw new IllegalArgumentException(
                            "the runtime's linking section holds a symbol of kind " + symbol
                                    + ", which this does not read");
                }
            }
            return segments;
        }
        throw new IllegalArgumentException("the runtime's linking section holds no symbol table");
    }

    /**
     * Where each relocation of a section is and which symbol it names, for those naming an address
     * in memory; every other kind is a function, a type, a global or a table, which say nothing of
     * data.
     */
    private static List<int[]> addresses(byte[] relocations) {
        if (relocations == null) {
            return List.of();
        }
        Reading at = new Reading(relocations, 0);
        at.unsigned(); // the section they apply to
        int count = at.unsigned();
        List<int[]> named = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int type = at.next();
            int offset = at.unsigned();
            int symbol = at.unsigned();
            if (hasAddend(type)) {
                at.signed();
            }
            if (namesMemory(type)) {
                named.add(new int[] {offset, symbol});
            }
        }
        return named;
    }

    /** The relocation types that carry an addend after their symbol (Linking.md). */
    private static boolean hasAddend(int type) {
        return switch (type) {
            case 3, 4, 5, 8, 9, 11, 14, 15, 16, 17, 21, 22, 23, 25 -> true;
            default -> false;
        };
    }

    /** The relocation types whose symbol is an address in memory. */
    private static boolean namesMemory(int type) {
        return switch (type) {
            case 3, 4, 5, 11, 14, 15, 16, 17, 21, 23, 25 -> true;
            default -> false;
        };
    }

    /** Where each body of a code section's payload starts: at its size. */
    private static int[] bodyStarts(byte[] code) {
        Reading at = new Reading(code, 0);
        int[] starts = new int[at.unsigned()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = at.position;
            int size = at.unsigned();
            at.position += size;
        }
        return starts;
    }

    /** Where each segment of a data section's payload starts: at its flags. */
    private static int[] segmentStarts(byte[] data) {
        Reading at = new Reading(data, 0);
        int[] starts = new int[at.unsigned()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = at.position;
            if (at.unsigned() != 0) {
                throw new IllegalArgumentException("the runtime holds a data segment that is not active"
                        + " in the first memory at a constant address, which this does not read");
            }
            at.next(); // i32.const
            at.signed();
            at.next(); // end
            int size = at.unsigned();
            at.position += size;
        }
        return starts;
    }

    /** Which entry an offset into the section's payload falls in. */
    private static int within(int[] starts, int offset) {
        int found = Arrays.binarySearch(starts, offset);
        return found >= 0 ? found : -found - 2;
    }

    /** A position in a payload, and the readings that move it. */
    private static final class Reading {
        private final byte[] bytes;
        int position;

        Reading(byte[] bytes, int position) {
            this.bytes = bytes;
            this.position = position;
        }

        int next() {
            return bytes[position++] & 0xff;
        }

        int unsigned() {
            int result = 0;
            int shift = 0;
            int b;
            do {
                b = next();
                result |= (b & 0x7f) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result;
        }

        long signed() {
            long result = 0;
            int shift = 0;
            int b;
            do {
                b = next();
                result |= (long) (b & 0x7f) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (shift < 64 && (b & 0x40) != 0) {
                result |= -1L << shift;
            }
            return result;
        }

        String name() {
            int length = unsigned();
            String name = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return name;
        }
    }
}
