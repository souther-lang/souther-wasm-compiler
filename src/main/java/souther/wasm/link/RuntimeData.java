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
 * <p>What this says is used to leave data out, so it says something only where it read all of what
 * the linker said, and says nothing otherwise: a {@code linking} section of a version other than
 * the one this reads, a symbol or a relocation of a kind it does not know, a relocation section
 * missing, or one naming a place that is no body and no segment, and every segment is kept. So is
 * a segment no relocation names, which the linker kept for a reason it did not write down, and one
 * a global's initial value points into, which no relocation covers.
 *
 * <p>Nothing here resolves anything again: the data stays where the linker put it, and a segment
 * dropped leaves zeros at its addresses, which nothing left reads.
 *
 * @see <a href="https://github.com/WebAssembly/tool-conventions/blob/main/Linking.md">Linking</a>
 */
final class RuntimeData {

    private static final int SEC_CUSTOM = 0;
    private static final int SEC_GLOBAL = 6;
    private static final int SEC_CODE = 10;
    private static final int SEC_DATA = 11;

    /** The version of the {@code linking} section this reads, the only one Linking.md defines. */
    private static final int LINKING_VERSION = 2;

    private static final int SUBSECTION_SEGMENT_INFO = 5;
    private static final int SUBSECTION_SYMBOL_TABLE = 8;

    private static final int SYMBOL_FUNCTION = 0;
    private static final int SYMBOL_DATA = 1;
    private static final int SYMBOL_GLOBAL = 2;
    private static final int SYMBOL_SECTION = 3;
    private static final int SYMBOL_TAG = 4;
    private static final int SYMBOL_TABLE = 5;

    private static final int FLAG_UNDEFINED = 0x10;
    private static final int FLAG_EXPLICIT_NAME = 0x40;

    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_END = 0x0b;

    private RuntimeData() {
    }

    /**
     * The runtime's data segments that functions read, each with those functions, in the runtime's
     * own index spaces, which a link keeps: the runtime's functions and segments come first in a
     * linked module. A segment not listed is kept whatever a link keeps.
     *
     * @param runtime the compiled runtime module
     * @return a claim per segment this could tell the readers of, and none where it read anything
     *     it could not be sure of
     */
    static List<OwnedDataSegment> owners(byte[] runtime) {
        // Every compile links onto the runtime this build carries, so what it says is read once for
        // it rather than once per link: its linker's sections are most of its bytes.
        Read last = lastRead;
        if (last != null && Arrays.equals(last.runtime(), runtime)) {
            return last.owners();
        }
        List<OwnedDataSegment> owners;
        try {
            owners = read(runtime);
        } catch (Unread e) {
            owners = List.of();
        }
        lastRead = new Read(runtime.clone(), owners);
        return owners;
    }

    /** The last runtime read, and what was read off it. */
    private record Read(byte[] runtime, List<OwnedDataSegment> owners) {
    }

    private static volatile @Nullable Read lastRead;

    /**
     * What the runtime's linker calls each function it defines, by the function's index: the
     * symbol's name, mangled as the compiler that wrote the function mangles it. For saying what a
     * module carries in the runtime's own words, which a test of what it carries reads.
     *
     * @param runtime the compiled runtime module
     * @return the names, or none where the linker's sections could not be read
     */
    static Map<Integer, String> functionNames(byte[] runtime) {
        try {
            return symbols(required(sections(runtime).custom().get("linking"))).functions();
        } catch (Unread e) {
            return Map.of();
        }
    }

    private static List<OwnedDataSegment> read(byte[] runtime) {
        Sections sections = sections(runtime);
        byte[] code = required(sections.code());
        byte[] data = required(sections.data());
        Symbols symbols = symbols(required(sections.custom().get("linking")));
        int importedFunctions = new LayoutReader(runtime).read().importedFunctionCount();
        int[] bodies = bodyStarts(code);
        Segments segments = segments(data);
        if (symbols.segmentCount() != segments.starts().length) {
            throw new Unread();
        }

        // What each function and each segment names, as segments.
        Map<Integer, BitSet> byFunction = new HashMap<>();
        BitSet[] bySegment = new BitSet[segments.starts().length];
        BitSet named = new BitSet();
        for (int i = 0; i < bySegment.length; i++) {
            bySegment[i] = new BitSet();
        }
        for (int[] each : addresses(required(sections.custom().get("reloc.CODE")), code.length)) {
            int segment = symbols.segmentOf(each[1]);
            if (segment >= 0) {
                int function = importedFunctions + within(bodies, each[0]);
                byFunction.computeIfAbsent(function, ignored -> new BitSet()).set(segment);
                named.set(segment);
            }
        }
        for (int[] each : addresses(required(sections.custom().get("reloc.DATA")), data.length)) {
            int segment = symbols.segmentOf(each[1]);
            if (segment >= 0) {
                bySegment[within(segments.starts(), each[0])].set(segment);
                named.set(segment);
            }
        }

        // What a global's initial value points into is read by whoever reads the global, which no
        // relocation says; it is kept, with everything it points at.
        BitSet held = closed(pointedAtByGlobals(sections.globals(), segments), bySegment);

        // Each function's segments, closed over what they point at, turned round.
        List<List<Integer>> readers = new ArrayList<>();
        for (int i = 0; i < bySegment.length; i++) {
            readers.add(new ArrayList<>());
        }
        for (Map.Entry<Integer, BitSet> each : byFunction.entrySet()) {
            BitSet reached = closed(each.getValue(), bySegment);
            for (int s = reached.nextSetBit(0); s >= 0; s = reached.nextSetBit(s + 1)) {
                readers.get(s).add(each.getKey());
            }
        }
        List<OwnedDataSegment> owned = new ArrayList<>(bySegment.length);
        for (int i = 0; i < bySegment.length; i++) {
            if (held.get(i) || readers.get(i).isEmpty()) {
                continue;
            }
            owned.add(new OwnedDataSegment(i, readers.get(i).stream().mapToInt(Integer::intValue).toArray()));
        }
        return List.copyOf(owned);
    }

    /** The segments {@code from}, with every segment they point at, all the way down. */
    private static BitSet closed(BitSet from, BitSet[] bySegment) {
        BitSet reached = (BitSet) from.clone();
        BitSet unwalked = (BitSet) reached.clone();
        for (int s = unwalked.nextSetBit(0); s >= 0; s = unwalked.nextSetBit(0)) {
            unwalked.clear(s);
            BitSet further = (BitSet) bySegment[s].clone();
            further.andNot(reached);
            reached.or(further);
            unwalked.or(further);
        }
        return reached;
    }

    /**
     * The segments a global's initial value is an address inside. A global whose initial value is
     * other than a constant could hold any address, so it leaves nothing this could claim.
     */
    private static BitSet pointedAtByGlobals(byte @Nullable [] globals, Segments segments) {
        BitSet pointed = new BitSet();
        if (globals == null) {
            return pointed;
        }
        Reading at = new Reading(globals, 0);
        int count = at.unsigned();
        for (int i = 0; i < count; i++) {
            at.next(); // value type
            at.next(); // mutability
            if (at.next() != OPCODE_I32_CONST) {
                throw new Unread();
            }
            long value = at.signed();
            if (at.next() != OPCODE_END) {
                throw new Unread();
            }
            for (int s = 0; s < segments.starts().length; s++) {
                if (value >= segments.addresses()[s]
                        && value < (long) segments.addresses()[s] + segments.lengths()[s]) {
                    pointed.set(s);
                }
            }
        }
        return pointed;
    }

    /** The sections of the runtime this reads: its custom ones by name, its globals, code and data. */
    private record Sections(Map<String, byte[]> custom, byte @Nullable [] globals, byte @Nullable [] code,
            byte @Nullable [] data) {
    }

    private static Sections sections(byte[] runtime) {
        Map<String, byte[]> custom = new HashMap<>();
        byte[] globals = null;
        byte[] code = null;
        byte[] data = null;
        for (RawSection section : new LayoutReader(runtime).rawSections()) {
            switch (section.id()) {
                case SEC_CUSTOM -> {
                    Reading at = new Reading(section.payload(), 0);
                    String name = at.name();
                    custom.put(name, Arrays.copyOfRange(section.payload(), at.position, section.payload().length));
                }
                case SEC_GLOBAL -> globals = section.payload();
                case SEC_CODE -> code = section.payload();
                case SEC_DATA -> data = section.payload();
                default -> { }
            }
        }
        return new Sections(custom, globals, code, data);
    }

    /**
     * What the symbol table says: the data segment each symbol lies in, by the symbol's index, or
     * minus one for a symbol of another kind; the name of each function defined, by its index; and
     * how many segments the segment info says there are.
     */
    private record Symbols(int[] segments, Map<Integer, String> functions, int segmentCount) {

        int segmentOf(int symbol) {
            if (symbol < 0 || symbol >= segments.length) {
                throw new Unread();
            }
            int segment = segments[symbol];
            if (segment >= segmentCount) {
                throw new Unread();
            }
            return segment;
        }
    }

    private static Symbols symbols(byte[] linking) {
        Reading at = new Reading(linking, 0);
        if (at.unsigned() != LINKING_VERSION) {
            throw new Unread();
        }
        int[] segments = null;
        Map<Integer, String> functions = new HashMap<>();
        int segmentCount = -1;
        while (at.position < linking.length) {
            int kind = at.next();
            int end = at.unsigned() + at.position;
            switch (kind) {
                case SUBSECTION_SEGMENT_INFO -> segmentCount = at.unsigned();
                case SUBSECTION_SYMBOL_TABLE -> {
                    int count = at.unsigned();
                    segments = new int[count];
                    for (int i = 0; i < count; i++) {
                        segments[i] = symbol(at, functions);
                    }
                    if (at.position != end) {
                        throw new Unread();
                    }
                }
                default -> { }
            }
            // A subsection this does not read says nothing of which data a function reads.
            at.position = end;
        }
        if (segments == null || segmentCount < 0) {
            throw new Unread();
        }
        return new Symbols(segments, Map.copyOf(functions), segmentCount);
    }

    /** Reads one symbol, answering the data segment it lies in or minus one, and naming a function. */
    private static int symbol(Reading at, Map<Integer, String> functions) {
        int symbol = at.next();
        int flags = at.unsigned();
        boolean defined = (flags & FLAG_UNDEFINED) == 0;
        switch (symbol) {
            case SYMBOL_FUNCTION, SYMBOL_GLOBAL, SYMBOL_TAG, SYMBOL_TABLE -> {
                int index = at.unsigned();
                if (defined || (flags & FLAG_EXPLICIT_NAME) != 0) {
                    String name = at.name();
                    if (symbol == SYMBOL_FUNCTION && defined) {
                        functions.putIfAbsent(index, name);
                    }
                }
                return -1;
            }
            case SYMBOL_DATA -> {
                at.name();
                if (!defined) {
                    return -1;
                }
                int segment = at.unsigned();
                at.unsigned(); // offset within the segment
                at.unsigned(); // size
                return segment;
            }
            case SYMBOL_SECTION -> {
                at.unsigned();
                return -1;
            }
            default -> throw new Unread();
        }
    }

    /**
     * Where each relocation of a section is and which symbol it names, for those naming an address
     * in memory; every other kind is a function, a type, a global, a tag or a table, which say
     * nothing of data. Every kind is one Linking.md defines, and one it does not ends the reading,
     * since what follows a relocation is framed by its kind.
     */
    private static List<int[]> addresses(byte[] relocations, int sectionLength) {
        Reading at = new Reading(relocations, 0);
        at.unsigned(); // the section they apply to
        int count = at.unsigned();
        List<int[]> named = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int type = at.next();
            int offset = at.unsigned();
            int symbol = at.unsigned();
            if (offset < 0 || offset >= sectionLength) {
                throw new Unread();
            }
            switch (type) {
                // R_WASM_MEMORY_ADDR_*: an address in memory, and an addend.
                case 3, 4, 5, 11, 14, 15, 16, 17, 21, 23, 25 -> {
                    at.signed();
                    named.add(new int[] {offset, symbol});
                }
                // R_WASM_FUNCTION_OFFSET_* and R_WASM_SECTION_OFFSET_I32: an addend, and no data.
                case 8, 9, 22 -> at.signed();
                // A function, a table slot, a type, a global, a tag or a table: no addend.
                case 0, 1, 2, 6, 7, 10, 12, 13, 18, 19, 20, 24, 26 -> { }
                default -> throw new Unread();
            }
        }
        if (at.position != relocations.length) {
            throw new Unread();
        }
        return named;
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

    /** Where each segment of a data section's payload starts, and the address and length it writes. */
    private record Segments(int[] starts, int[] addresses, int[] lengths) {
    }

    private static Segments segments(byte[] data) {
        Reading at = new Reading(data, 0);
        int count = at.unsigned();
        int[] starts = new int[count];
        int[] addresses = new int[count];
        int[] lengths = new int[count];
        for (int i = 0; i < count; i++) {
            starts[i] = at.position;
            if (at.unsigned() != 0 || at.next() != OPCODE_I32_CONST) {
                throw new Unread();
            }
            addresses[i] = (int) at.signed();
            if (at.next() != OPCODE_END) {
                throw new Unread();
            }
            lengths[i] = at.unsigned();
            at.position += lengths[i];
        }
        return new Segments(starts, addresses, lengths);
    }

    /** Which entry an offset into the section's payload falls in. */
    private static int within(int[] starts, int offset) {
        int found = Arrays.binarySearch(starts, offset);
        int entry = found >= 0 ? found : -found - 2;
        if (entry < 0) {
            throw new Unread();
        }
        return entry;
    }

    private static <T> T required(@Nullable T held) {
        if (held == null) {
            throw new Unread();
        }
        return held;
    }

    /** What the linker said is not all read, so nothing is claimed from it. */
    private static final class Unread extends RuntimeException {
        Unread() {
            super(null, null, false, false);
        }
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
            if (position >= bytes.length) {
                throw new Unread();
            }
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
            if (length < 0 || position + length > bytes.length) {
                throw new Unread();
            }
            String name = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return name;
        }
    }
}
