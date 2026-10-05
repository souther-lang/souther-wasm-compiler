package souther.wasm.emit;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import souther.wasm.emit.WasmSections.Section;

/**
 * Writes a module's active data segments as few segments as the same memory needs.
 *
 * <p>A segment costs its header, a few bytes, besides its bytes. A runtime whose linker kept each
 * piece of static data a segment of its own, so that what nothing reads can be left out one piece
 * at a time, has thousands, and what is left of them stands mostly side by side. Where one ends a
 * few bytes before the next begins, the two are written as one, the bytes between them zeros; and a
 * segment holding nothing but zeros is not written.
 *
 * <p>Both leave memory as instantiation would have left it only where no two segments write the
 * same bytes and every segment lies inside the memory the module starts with: then the bytes
 * between two segments are zeros nothing else writes, which memory a module defines starts as, and
 * a segment of zeros writes what is already there and cannot trap. That is held of the module
 * before anything is changed, and a module it is not true of is answered as it is: one segment
 * writing over another, a segment past the end of memory (which instantiation traps on), a memory
 * the module imports (which need not start as zeros), or a segment that is not active at a constant
 * address.
 *
 * <p>So is a module naming a segment by its index, in a {@code memory.init} or a {@code data.drop},
 * since one segment written as part of another is no longer the one its index named. Such a module
 * declares how many segments it has ({@code DataCount}), and one that does not declare it names
 * none.
 */
public final class WasmDataCoalescer {

    private static final int SEC_IMPORT = 2;
    private static final int SEC_MEMORY = 5;
    private static final int SEC_DATA = 11;
    private static final int SEC_DATA_COUNT = 12;

    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_END = 0x0B;
    private static final long PAGE = 65536;

    /** The widest run of zeros written between two segments rather than a header for each. */
    private static final int GAP = 8;

    private WasmDataCoalescer() {
    }

    /**
     * The module with its data segments coalesced.
     *
     * @param module a core WASM module
     * @return the module, its data section rewritten; the input where nothing changes, or where
     *     what the rewriting rests on is not so of it
     */
    public static byte[] coalesced(byte[] module) {
        List<Section> sections = WasmSections.parseSections(module);
        @Nullable Section data = WasmSections.find(sections, SEC_DATA);
        if (data == null || WasmSections.find(sections, SEC_DATA_COUNT) != null) {
            return module;
        }
        @Nullable List<Run> read = runs(data.payload());
        long memory = startingMemory(sections);
        if (read == null || !apartAndInside(read, memory)) {
            return module;
        }
        // Runs of segments standing close enough to be one, each written once it is complete. In
        // the order of their addresses, so that the bytes between two are bytes no segment writes;
        // written in another order, which no two writing the same byte makes another memory.
        List<Run> written = new ArrayList<>();
        List<Run> joining = new ArrayList<>();
        for (Run each : ascending(read)) {
            if (zeros(each.bytes)) {
                continue;
            }
            if (!joining.isEmpty()) {
                Run last = joining.getLast();
                long gap = (long) each.offset - ((long) last.offset + last.bytes.length);
                if (gap < 0 || gap > GAP) {
                    written.add(joined(joining));
                    joining.clear();
                }
            }
            joining.add(each);
        }
        if (!joining.isEmpty()) {
            written.add(joined(joining));
        }
        if (written.size() == read.size()) {
            return module;
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(payload).writeUnsignedLeb128(written.size());
        for (Run each : written) {
            out.writeUnsignedLeb128(0)
                    .write((byte) OPCODE_I32_CONST).writeSignedLeb128(each.offset).write((byte) OPCODE_END)
                    .writeUnsignedLeb128(each.bytes.length)
                    .write(each.bytes);
        }
        List<Section> rebuilt = new ArrayList<>(sections.size());
        for (Section each : sections) {
            rebuilt.add(each.id() == SEC_DATA ? new Section(SEC_DATA, payload.toByteArray()) : each);
        }
        return WasmSections.assemble(rebuilt);
    }

    /** A segment: where it is written and what it writes. */
    private record Run(int offset, byte[] bytes) {
    }

    /** The segments, or nothing where one is not active in memory zero at a constant address. */
    private static @Nullable List<Run> runs(byte[] payload) {
        int[] p = {0};
        int count = WasmSections.readU(payload, p);
        List<Run> runs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int flags = WasmSections.readU(payload, p);
            if (flags != 0 || (payload[p[0]++] & 0xff) != OPCODE_I32_CONST) {
                return null;
            }
            int offset = WasmSections.readS(payload, p);
            if ((payload[p[0]++] & 0xff) != OPCODE_END) {
                return null;
            }
            int length = WasmSections.readU(payload, p);
            runs.add(new Run(offset, WasmSections.slice(payload, p[0], p[0] + length)));
            p[0] += length;
        }
        return runs;
    }

    /**
     * How many bytes of memory the module starts with, of the one memory it defines; minus one
     * where it imports a memory, defines none, or defines one this does not read as starting zeroed.
     */
    private static long startingMemory(List<Section> sections) {
        @Nullable Section imports = WasmSections.find(sections, SEC_IMPORT);
        if (imports != null && WasmSections.parseImports(imports.payload()).stream()
                .anyMatch(each -> each.kind() == WasmSections.KIND_MEM)) {
            return -1;
        }
        @Nullable Section memories = WasmSections.find(sections, SEC_MEMORY);
        if (memories == null) {
            return -1;
        }
        byte[] payload = memories.payload();
        int[] p = {0};
        if (WasmSections.readU(payload, p) != 1) {
            return -1;
        }
        int flags = WasmSections.readU(payload, p);
        // A maximum or not; anything else — shared, 64-bit, a custom page size — is not read here.
        if (flags != 0 && flags != 1) {
            return -1;
        }
        return (long) WasmSections.readU(payload, p) * PAGE;
    }

    /** Whether no two segments write the same byte and each lies inside {@code memory} bytes. */
    private static boolean apartAndInside(List<Run> runs, long memory) {
        long end = 0;
        for (Run each : ascending(runs)) {
            long start = Integer.toUnsignedLong(each.offset);
            if (start < end || start + each.bytes.length > memory) {
                return false;
            }
            end = start + each.bytes.length;
        }
        return true;
    }

    private static List<Run> ascending(List<Run> runs) {
        List<Run> ascending = new ArrayList<>(runs);
        ascending.sort((a, b) -> Long.compare(Integer.toUnsignedLong(a.offset), Integer.toUnsignedLong(b.offset)));
        return ascending;
    }

    /** Segments standing in ascending order a few bytes apart at most, as one. */
    private static Run joined(List<Run> runs) {
        Run first = runs.getFirst();
        Run last = runs.getLast();
        byte[] bytes = new byte[last.offset + last.bytes.length - first.offset];
        for (Run each : runs) {
            System.arraycopy(each.bytes, 0, bytes, each.offset - first.offset, each.bytes.length);
        }
        return new Run(first.offset, bytes);
    }

    private static boolean zeros(byte[] bytes) {
        for (byte each : bytes) {
            if (each != 0) {
                return false;
            }
        }
        return true;
    }
}
