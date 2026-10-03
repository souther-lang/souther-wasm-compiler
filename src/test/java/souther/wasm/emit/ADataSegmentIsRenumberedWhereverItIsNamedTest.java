package souther.wasm.emit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.emit.WasmTreeShaker.DroppableDataRange;
import souther.wasm.emit.WasmTreeShaker.OwnedDataSegment;

/**
 * The tree shaker drops and cuts data segments, which moves every segment after one it took out.
 * Everything naming a segment by its index moves with it: a {@code memory.init} and a
 * {@code data.drop} in a surviving body, and the data count section. A segment such an
 * instruction names is a use of it, so it is kept, and kept whole, whatever a caller claimed.
 *
 * <p>The module is written by hand: segments {@code A}, {@code B} and {@code C}, a body that
 * survives because it is exported, and a body nothing reaches, which a caller may say owns a
 * segment.
 */
class ADataSegmentIsRenumberedWhereverItIsNamedTest {

    /** {@code memory.init 2}, {@code data.drop 0}, {@code data.drop 2}. */
    private static final byte[] NAMING = {
        0x41, 0x00, 0x41, 0x00, 0x41, 0x00, (byte) 0xFC, 0x08, 0x02, 0x00,
        (byte) 0xFC, 0x09, 0x00,
        (byte) 0xFC, 0x09, 0x02,
    };

    @Test
    void renumbersWhatASurvivingBodyNamesPastASegmentTakenOut() {
        // B belongs to the body nothing reaches, so it goes, and C becomes segment 1.
        byte[] shaken = WasmTreeShaker.shake(module(),
                List.of(new OwnedDataSegment(1, new int[] {1})), List.of());

        assertThat(segments(shaken)).containsExactly("A", "C");
        assertThat(dataCount(shaken)).isEqualTo(2);
        assertThat(namedData(shaken)).containsExactly(1, 0, 1);
        instantiates(shaken);
    }

    @Test
    void keepsASegmentABodyNamesWhateverItsOwnerWasSaidToBe() {
        // C is claimed by the body nothing reaches, and the surviving body names it.
        byte[] shaken = WasmTreeShaker.shake(module(),
                List.of(new OwnedDataSegment(2, new int[] {1})), List.of());

        assertThat(segments(shaken)).containsExactly("A", "B", "C");
        assertThat(namedData(shaken)).containsExactly(2, 0, 2);
        instantiates(shaken);
    }

    @Test
    void cutsNoRangeOutOfASegmentABodyNames() {
        // No body addresses A's byte, so a range over it would be cut; but A is named, and a
        // segment cut into runs is no longer the one its index named. B is not named, and goes.
        byte[] shaken = WasmTreeShaker.shake(module(), List.of(),
                List.of(new DroppableDataRange(0, 0, 1), new DroppableDataRange(1, 0, 1)));

        assertThat(segments(shaken)).containsExactly("A", "C");
        assertThat(dataCount(shaken)).isEqualTo(2);
        assertThat(namedData(shaken)).containsExactly(1, 0, 1);
        instantiates(shaken);
    }

    private static List<String> segments(byte[] module) {
        byte[] payload = WasmSections.find(WasmSections.parseSections(module), 11).payload();
        int[] p = {0};
        int count = WasmSections.readU(payload, p);
        List<String> held = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WasmSections.readU(payload, p); // flags
            p[0]++; // i32.const
            WasmSections.readS(payload, p);
            p[0]++; // end
            int length = WasmSections.readU(payload, p);
            held.add(new String(payload, p[0], length, StandardCharsets.US_ASCII));
            p[0] += length;
        }
        return held;
    }

    private static int dataCount(byte[] module) {
        return WasmSections.readU(
                WasmSections.find(WasmSections.parseSections(module), 12).payload(), new int[] {0});
    }

    private static List<Integer> namedData(byte[] module) {
        byte[] code = WasmSections.find(WasmSections.parseSections(module), 10).payload();
        List<Integer> named = new ArrayList<>();
        for (byte[] entry : WasmSections.parseCodeEntries(code)) {
            for (WasmSections.Ref r : WasmSections.scanBody(entry)) {
                if (r.kind() == WasmSections.RefKind.DATA) {
                    named.add(r.index());
                }
            }
        }
        return named;
    }

    /** Validation is what an index naming the wrong segment, or a wrong count, fails. */
    private static void instantiates(byte[] module) {
        Instance.builder(Parser.parse(module)).build();
    }

    private static byte[] module() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        section(out, 1, new byte[] {0x01, 0x60, 0x00, 0x00});
        section(out, 3, new byte[] {0x02, 0x00, 0x00});
        section(out, 5, new byte[] {0x01, 0x00, 0x01});
        section(out, 7, new byte[] {0x01, 0x04, 'k', 'e', 'p', 't', 0x00, 0x00});
        section(out, 12, new byte[] {0x03});
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x02);
        code.write(NAMING.length + 2);
        code.write(0x00);
        code.writeBytes(NAMING);
        code.write(0x0B);
        code.writeBytes(new byte[] {0x02, 0x00, 0x0B});
        section(out, 10, code.toByteArray());
        section(out, 11, new byte[] {
            0x03,
            0x00, 0x41, 0x00, 0x0B, 0x01, 'A',
            0x00, 0x41, 0x10, 0x0B, 0x01, 'B',
            0x00, 0x41, 0x20, 0x0B, 0x01, 'C',
        });
        return out.toByteArray();
    }

    private static void section(ByteArrayOutputStream out, int id, byte[] payload) {
        out.write(id);
        out.write(payload.length);
        out.writeBytes(payload);
    }
}
