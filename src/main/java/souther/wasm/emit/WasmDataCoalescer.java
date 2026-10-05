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
 * segment holding nothing but zeros is not written. Both leave memory as instantiation would have
 * left it, because memory a module defines starts as zeros: what is written moves nowhere, and
 * nothing written is read differently.
 *
 * <p>A module naming a segment by its index, in a {@code memory.init} or a {@code data.drop}, is
 * left as it is, since one segment written as part of another is no longer the one its index named.
 * Such a module declares how many segments it has ({@code DataCount}), and one that does not
 * declare it names none.
 */
public final class WasmDataCoalescer {

    private static final int SEC_DATA = 11;
    private static final int SEC_DATA_COUNT = 12;

    /** The widest run of zeros written between two segments rather than a header for each. */
    private static final int GAP = 8;

    private WasmDataCoalescer() {
    }

    /**
     * The module with its data segments coalesced.
     *
     * @param module a core WASM module whose data segments are active, in memory zero, at constant
     *     addresses
     * @return the module, its data section rewritten; the input where nothing changes
     */
    public static byte[] coalesced(byte[] module) {
        List<Section> sections = WasmSections.parseSections(module);
        @Nullable Section data = WasmSections.find(sections, SEC_DATA);
        if (data == null || WasmSections.find(sections, SEC_DATA_COUNT) != null) {
            return module;
        }
        // Runs of segments standing close enough to be one, each written once it is complete.
        List<Run> read = runs(data.payload());
        List<Run> written = new ArrayList<>();
        List<Run> joining = new ArrayList<>();
        for (Run each : read) {
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
                    .write((byte) 0x41).writeSignedLeb128(each.offset).write((byte) 0x0B)
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

    private static List<Run> runs(byte[] payload) {
        int[] p = {0};
        int count = WasmSections.readU(payload, p);
        List<Run> runs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int flags = WasmSections.readU(payload, p);
            if (flags != 0 || (payload[p[0]++] & 0xff) != 0x41) {
                throw new IllegalArgumentException(
                        "a data segment that is not active in memory zero at a constant address");
            }
            int offset = WasmSections.readS(payload, p);
            if ((payload[p[0]++] & 0xff) != 0x0B) {
                throw new IllegalArgumentException("a data segment's address is more than a constant");
            }
            int length = WasmSections.readU(payload, p);
            runs.add(new Run(offset, WasmSections.slice(payload, p[0], p[0] + length)));
            p[0] += length;
        }
        return runs;
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
