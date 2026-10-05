package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import souther.wasm.emit.WasmTreeShaker.OwnedDataSegment;
import souther.wasm.emit.WasmWriter;

/**
 * A segment kept for a reason no function's relocation gives is kept with everything it points
 * at, so that nothing kept points at data left out.
 *
 * <p>The modules are written by hand, as a linker writes one with {@code --emit-relocs}: one
 * function {@code f}, a segment {@code A} of one pointer and a segment {@code B} of two bytes, and
 * the relocations each test names. {@code f} is the only reader a claim can name, so what is
 * claimed of a segment is that it goes when {@code f} does.
 */
class WhatIsKeptForAReasonNotGivenKeepsWhatItPointsAtTest {

    private static final int A = 0;
    private static final int B = 1;

    @Test
    void claimsWhatAFunctionReachesThroughTheSegmentsItReads() {
        // f reads A, and A points at B: both go when f does.
        Map<Integer, List<Integer>> claimed = claims(true, A, new int[] {B}, new int[0]);

        assertThat(claimed).isEqualTo(Map.of(A, List.of(1), B, List.of(1)));
    }

    @Test
    void keepsWhatASegmentNoFunctionReachesPointsAt() {
        // f reads B; nothing f reads reaches A, which points at B. A is kept for a reason the linker
        // did not write down, so B is kept with it, f or no f.
        Map<Integer, List<Integer>> claimed = claims(true, B, new int[] {B}, new int[0]);

        assertThat(claimed).isEmpty();
    }

    @Test
    void keepsSegmentsThatPointOnlyAtEachOther() {
        // A and B point at each other and no function reaches either: each is named by a
        // relocation, and neither is reached by one of a function.
        Map<Integer, List<Integer>> claimed = claims(false, -1, new int[] {B}, new int[] {A});

        assertThat(claimed).isEmpty();
    }

    /**
     * What is claimed of a module whose function reads {@code read} (none where it is minus one or
     * {@code reads} is false), whose {@code A} points at {@code fromA} and {@code B} at
     * {@code fromB}: each segment claimed with the functions that read it.
     */
    private static Map<Integer, List<Integer>> claims(boolean reads, int read, int[] fromA, int[] fromB) {
        return RuntimeData.owners(module(reads ? read : -1, fromA, fromB)).stream()
                .collect(Collectors.toMap(OwnedDataSegment::segmentIndex, each -> java.util.Arrays
                        .stream(each.ownerFuncIndices()).boxed().toList()));
    }

    // Symbols: 0 is f, 1 is A's data, 2 is B's data.
    private static final int SYMBOL_A = 1;
    private static final int SYMBOL_B = 2;
    private static final int ADDRESS_A = 0;
    private static final int ADDRESS_B = 16;

    private static byte[] module(int read, int[] fromA, int[] fromB) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        // An import, so that f is function 1 and a claim of it is not of index 0.
        section(out, 1, new byte[] {0x01, 0x60, 0x00, 0x00});
        section(out, 2, new byte[] {0x01, 0x01, 'h', 0x01, 'g', 0x00, 0x00});
        section(out, 3, new byte[] {0x01, 0x00});
        section(out, 5, new byte[] {0x01, 0x00, 0x01});

        // f: i32.const <an address, five bytes wide so a linker can rewrite it> drop end.
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.writeBytes(new byte[] {0x01, 0x09, 0x00, 0x41});
        int addressInCode = code.size();
        code.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00, 0x1a, 0x0b});
        section(out, 10, code.toByteArray());

        // A holds two words, at most two pointers; B holds two bytes.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(new byte[] {0x02, 0x00, 0x41, ADDRESS_A, 0x0b, 0x08});
        int wordsOfA = data.size();
        data.writeBytes(new byte[8]);
        data.writeBytes(new byte[] {0x00, 0x41, ADDRESS_B, 0x0b, 0x02});
        int wordsOfB = data.size();
        data.writeBytes(new byte[] {'b', 'b'});
        section(out, 11, data.toByteArray());

        custom(out, "linking", linking());
        List<int[]> codeRelocations = new ArrayList<>();
        if (read >= 0) {
            codeRelocations.add(new int[] {4, addressInCode, read == A ? SYMBOL_A : SYMBOL_B});
        }
        custom(out, "reloc.CODE", relocations(3, codeRelocations));
        List<int[]> dataRelocations = new ArrayList<>();
        for (int i = 0; i < fromA.length; i++) {
            dataRelocations.add(new int[] {5, wordsOfA + 4 * i, fromA[i] == A ? SYMBOL_A : SYMBOL_B});
        }
        for (int target : fromB) {
            dataRelocations.add(new int[] {5, wordsOfB, target == A ? SYMBOL_A : SYMBOL_B});
        }
        custom(out, "reloc.DATA", relocations(5, dataRelocations));
        return out.toByteArray();
    }

    private static byte[] linking() {
        ByteArrayOutputStream segments = new ByteArrayOutputStream();
        WasmWriter info = new WasmWriter(segments).writeUnsignedLeb128(2);
        for (String name : List.of(".rodata.a", ".rodata.b")) {
            info.writeUnsignedLeb128(name.length()).write(name.getBytes(StandardCharsets.UTF_8))
                    .writeUnsignedLeb128(0).writeUnsignedLeb128(0);
        }
        ByteArrayOutputStream symbols = new ByteArrayOutputStream();
        new WasmWriter(symbols).writeUnsignedLeb128(3)
                .write((byte) 0).writeUnsignedLeb128(0).writeUnsignedLeb128(1)
                .writeUnsignedLeb128(1).write("f".getBytes(StandardCharsets.UTF_8))
                .write((byte) 1).writeUnsignedLeb128(0)
                .writeUnsignedLeb128(1).write("a".getBytes(StandardCharsets.UTF_8))
                .writeUnsignedLeb128(A).writeUnsignedLeb128(0).writeUnsignedLeb128(8)
                .write((byte) 1).writeUnsignedLeb128(0)
                .writeUnsignedLeb128(1).write("b".getBytes(StandardCharsets.UTF_8))
                .writeUnsignedLeb128(B).writeUnsignedLeb128(0).writeUnsignedLeb128(2);
        ByteArrayOutputStream linking = new ByteArrayOutputStream();
        new WasmWriter(linking).writeUnsignedLeb128(2)
                .write((byte) 5).writeUnsignedLeb128(segments.size()).write(segments.toByteArray())
                .write((byte) 8).writeUnsignedLeb128(symbols.size()).write(symbols.toByteArray());
        return linking.toByteArray();
    }

    /** A relocation section of entries of type, offset and symbol, each with an addend of nought. */
    private static byte[] relocations(int section, List<int[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(out).writeUnsignedLeb128(section).writeUnsignedLeb128(entries.size());
        for (int[] each : entries) {
            writer.write((byte) each[0]).writeUnsignedLeb128(each[1]).writeUnsignedLeb128(each[2])
                    .writeSignedLeb128(0);
        }
        return out.toByteArray();
    }

    private static void custom(ByteArrayOutputStream out, String name, byte[] content) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        new WasmWriter(payload).writeUnsignedLeb128(name.length()).write(name.getBytes(StandardCharsets.UTF_8))
                .write(content);
        section(out, 0, payload.toByteArray());
    }

    private static void section(ByteArrayOutputStream out, int id, byte[] payload) {
        new WasmWriter(out).write((byte) id).writeUnsignedLeb128(payload.length).write(payload);
    }
}
