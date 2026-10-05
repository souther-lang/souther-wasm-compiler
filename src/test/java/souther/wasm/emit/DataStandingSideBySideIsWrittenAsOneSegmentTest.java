package souther.wasm.emit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Data segments standing a few bytes apart are written as one, and a segment of zeros is not
 * written, because memory a module defines starts as zeros: what instantiation leaves in memory is
 * the same either way.
 *
 * <p>The modules are written by hand: a memory, and segments at the addresses each test names.
 */
class DataStandingSideBySideIsWrittenAsOneSegmentTest {

    @Test
    void writesSegmentsAFewBytesApartAsOneWithZerosBetween() {
        byte[] coalesced = WasmDataCoalescer.coalesced(module(false,
                new Segment(0, "ab"), new Segment(2, "cd"), new Segment(8, "ef")));

        assertThat(segments(coalesced)).containsExactly(
                new Segment(0, "abcd\0\0\0\0ef"));
        assertThat(memory(coalesced)).isEqualTo(memory(module(false,
                new Segment(0, "ab"), new Segment(2, "cd"), new Segment(8, "ef"))));
    }

    @Test
    void keepsSegmentsFartherApartThanAHeaderCostsApart() {
        byte[] module = module(false, new Segment(0, "ab"), new Segment(32, "cd"));

        assertThat(WasmDataCoalescer.coalesced(module)).isSameAs(module);
    }

    @Test
    void writesNoSegmentOfZeros() {
        byte[] coalesced = WasmDataCoalescer.coalesced(module(false,
                new Segment(0, "ab"), new Segment(64, "\0\0\0\0"), new Segment(128, "cd")));

        assertThat(segments(coalesced)).containsExactly(new Segment(0, "ab"), new Segment(128, "cd"));
    }

    @Test
    void leavesAModuleWhoseSegmentsWriteOverOneAnotherAsItIs() {
        // The second writes a zero over the first's second byte, so memory holds "a\0", which
        // leaving out a segment of zeros would make "ab".
        byte[] module = module(false, new Segment(0, "ab"), new Segment(1, "\0"));

        assertThat(WasmDataCoalescer.coalesced(module)).isSameAs(module);
    }

    @Test
    void joinsSegmentsByWhereTheyStandAndNotByWhereTheyAreWritten() {
        // Between "ab" and "cd" stands "Q", written before either: joined in the order written,
        // the zeros between them would be written over it.
        byte[] module = module(false, new Segment(4, "Q"), new Segment(0, "ab"), new Segment(6, "cd"));
        byte[] coalesced = WasmDataCoalescer.coalesced(module);

        assertThat(segments(coalesced)).containsExactly(new Segment(0, "ab\0\0Q\0cd"));
        assertThat(memory(coalesced)).isEqualTo(memory(module));
    }

    @Test
    void leavesAModuleWithASegmentPastItsMemoryAsItIs() {
        // Instantiating it traps, and leaving out the segment of zeros would make it instantiate.
        byte[] module = module(false, new Segment(0, "ab"), new Segment(65536, "\0"));

        assertThat(WasmDataCoalescer.coalesced(module)).isSameAs(module);
    }

    @Test
    void leavesAModuleWhoseMemoryIsImportedAsItIs() {
        // A memory handed in need not start as zeros.
        byte[] module = importingItsMemory(new Segment(0, "ab"), new Segment(2, "\0"));

        assertThat(WasmDataCoalescer.coalesced(module)).isSameAs(module);
    }

    @Test
    void leavesAModuleThatMayNameASegmentByItsIndexAsItIs() {
        byte[] module = module(true, new Segment(0, "ab"), new Segment(2, "cd"));

        assertThat(WasmDataCoalescer.coalesced(module)).isSameAs(module);
    }

    private record Segment(int offset, String bytes) {
    }

    private static byte[] memory(byte[] module) {
        return Instance.builder(Parser.parse(module)).build().memory().readBytes(0, 256);
    }

    private static List<Segment> segments(byte[] module) {
        byte[] payload = WasmSections.find(WasmSections.parseSections(module), 11).payload();
        int[] p = {0};
        int count = WasmSections.readU(payload, p);
        List<Segment> held = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WasmSections.readU(payload, p); // flags
            p[0]++; // i32.const
            int offset = WasmSections.readS(payload, p);
            p[0]++; // end
            int length = WasmSections.readU(payload, p);
            held.add(new Segment(offset, new String(payload, p[0], length,
                    java.nio.charset.StandardCharsets.ISO_8859_1)));
            p[0] += length;
        }
        return held;
    }

    private static byte[] module(boolean countsItsData, Segment... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        section(out, 5, new byte[] {0x01, 0x00, 0x01});
        if (countsItsData) {
            section(out, 12, new byte[] {(byte) segments.length});
        }
        section(out, 11, data(segments));
        return out.toByteArray();
    }

    private static byte[] data(Segment... segments) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(data).writeUnsignedLeb128(segments.length);
        for (Segment each : segments) {
            byte[] bytes = each.bytes().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            writer.writeUnsignedLeb128(0).write((byte) 0x41).writeSignedLeb128(each.offset())
                    .write((byte) 0x0B).writeUnsignedLeb128(bytes.length).write(bytes);
        }
        return data.toByteArray();
    }

    private static byte[] importingItsMemory(Segment... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        section(out, 2, new byte[] {0x01, 0x01, 'm', 0x01, 'm', 0x02, 0x00, 0x01});
        section(out, 11, data(segments));
        return out.toByteArray();
    }

    private static void section(ByteArrayOutputStream out, int id, byte[] payload) {
        out.write(id);
        out.write(payload.length);
        out.writeBytes(payload);
    }
}
