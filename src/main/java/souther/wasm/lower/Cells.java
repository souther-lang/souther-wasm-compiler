package souther.wasm.lower;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import souther.wasm.abi.RuntimeAbi.Cell;
import souther.wasm.link.WasmFragment;

/**
 * Values a body writes down, placed in static memory as the cells they are.
 *
 * <p>A literal is the same value every time a body reaches it, and nothing changes a cell once it
 * is made, so one cell in static memory serves every time and every place it is written. Made in
 * the arena instead, a {@code 0} in a loop is a cell per step. Each is placed through
 * {@link WasmFragment#intern}, since a cell is read by what it holds and never told apart by where
 * it is: two places writing {@code 0} read one cell.
 */
final class Cells {

    private final WasmFragment fragment;

    Cells(WasmFragment fragment) {
        this.fragment = fragment;
    }

    /** An {@code Int}. */
    int ofInt(long value) {
        return fragment.intern(intCell(value));
    }

    /** A {@code Bool}. */
    int ofBool(boolean value) {
        return fragment.intern(boolCell(value));
    }

    /** A {@code String}, as the UTF-8 bytes it is. */
    int ofString(byte[] utf8) {
        return fragment.intern(stringCell(utf8));
    }

    /** An option holding nothing. */
    int none() {
        return fragment.intern(noneCell());
    }

    /** The one value of a type with one, which the cell names by the type's descriptor. */
    int unit(int descriptor) {
        return fragment.intern(unitCell(descriptor));
    }

    /** A block reading nothing from around it, by the slot its body sits in. */
    int closure(int slot) {
        return fragment.intern(closureCell(slot));
    }

    static byte[] intCell(long value) {
        return header(Cell.TAG_INT, 0, 8).putLong(value).array();
    }

    static byte[] boolCell(boolean value) {
        return header(Cell.TAG_BOOL, 0, 4).putInt(value ? 1 : 0).array();
    }

    static byte[] stringCell(byte[] utf8) {
        return header(Cell.TAG_STRING, utf8.length, utf8.length).put(utf8).array();
    }

    static byte[] noneCell() {
        return header(Cell.TAG_NONE, 0, 0).array();
    }

    static byte[] unitCell(int descriptor) {
        return header(Cell.TAG_UNIT, descriptor, 0).array();
    }

    static byte[] closureCell(int slot) {
        return header(Cell.TAG_CLOSURE, slot, 4).putInt(0).array();
    }

    private static ByteBuffer header(int tag, int second, int payload) {
        return ByteBuffer.allocate(Cell.PAYLOAD + payload)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(tag)
                .putInt(second);
    }
}
