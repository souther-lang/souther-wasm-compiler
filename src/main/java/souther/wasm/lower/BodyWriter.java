package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import souther.wasm.emit.WasmWriter;

/**
 * The instructions of one generated function, written as they are decided.
 *
 * <p>Small on purpose. What is here is what a body of this backend's writes today; an instruction
 * this class does not name is one nothing emits yet, so a reader can see the whole of what a
 * generated body can do by reading this.
 *
 * <p>Locals are taken as they turn out to be needed, because how many a body wants is not known
 * until it has been written — reading a shape wants one per shape it meets, nested. The sixty-four
 * bit locals come first in the numbering so that taking another thirty-two bit one moves nothing
 * that was already written.
 */
final class BodyWriter {

    private static final int OPCODE_END = 0x0b;
    private static final int OPCODE_IF = 0x04;
    private static final int OPCODE_ELSE = 0x05;
    private static final int OPCODE_CALL = 0x10;
    private static final int OPCODE_LOCAL_GET = 0x20;
    private static final int OPCODE_LOCAL_SET = 0x21;
    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_I64_CONST = 0x42;
    private static final int OPCODE_I32_EQZ = 0x45;
    private static final int OPCODE_I32_EQ = 0x46;
    private static final int OPCODE_I32_NE = 0x47;
    private static final int OPCODE_I32_LT_S = 0x48;
    private static final int OPCODE_I32_GT_S = 0x4a;
    private static final int OPCODE_I32_LE_S = 0x4c;
    private static final int OPCODE_I32_GE_S = 0x4e;
    private static final int OPCODE_UNREACHABLE = 0x00;
    private static final int OPCODE_I32_OR = 0x72;
    private static final int OPCODE_I32_WRAP_I64 = 0xa7;
    private static final int OPCODE_I64_SHR_U = 0x88;

    private static final int TYPE_I32 = 0x7f;
    private static final int TYPE_I64 = 0x7e;
    private static final int BLOCK_TYPE_EMPTY = 0x40;

    private final ByteArrayOutputStream instructions = new ByteArrayOutputStream();
    private final WasmWriter writer = new WasmWriter(instructions);
    private final int parameters;
    private final int wideLocals;
    private int narrowLocals;

    /**
     * @param parameters how many the function's type declares, which take the first indices
     * @param wideLocals how many {@code i64} locals it wants, which take the next
     */
    BodyWriter(int parameters, int wideLocals) {
        this.parameters = parameters;
        this.wideLocals = wideLocals;
    }

    /** The index of one of the function's {@code i64} locals. */
    int wide(int which) {
        return parameters + which;
    }

    /** Takes another {@code i32} local and answers its index. */
    int narrow() {
        return parameters + wideLocals + narrowLocals++;
    }

    BodyWriter localGet(int index) {
        writer.write((byte) OPCODE_LOCAL_GET).writeUnsignedLeb128(index);
        return this;
    }

    BodyWriter localSet(int index) {
        writer.write((byte) OPCODE_LOCAL_SET).writeUnsignedLeb128(index);
        return this;
    }

    BodyWriter constant(int value) {
        writer.write((byte) OPCODE_I32_CONST).writeSignedLeb128(value);
        return this;
    }

    BodyWriter constant(long value) {
        writer.write((byte) OPCODE_I64_CONST).writeSignedLeb128(value);
        return this;
    }

    BodyWriter call(int functionIndex) {
        writer.write((byte) OPCODE_CALL).writeUnsignedLeb128(functionIndex);
        return this;
    }

    /** Narrows the number on the stack to its low half. */
    BodyWriter wrap() {
        writer.write((byte) OPCODE_I32_WRAP_I64);
        return this;
    }

    /** Shifts the number on the stack right, without sign. */
    BodyWriter shiftRight(long places) {
        constant(places);
        writer.write((byte) OPCODE_I64_SHR_U);
        return this;
    }

    /** How one number stands to another. */
    enum Comparison {
        EQUAL, UNEQUAL, LESS, AT_MOST, GREATER, AT_LEAST
    }

    /** Turns the two numbers on the stack into whether the first stands that way to the second. */
    BodyWriter compares(Comparison how) {
        writer.write((byte) switch (how) {
            case EQUAL -> OPCODE_I32_EQ;
            case UNEQUAL -> OPCODE_I32_NE;
            case LESS -> OPCODE_I32_LT_S;
            case AT_MOST -> OPCODE_I32_LE_S;
            case GREATER -> OPCODE_I32_GT_S;
            case AT_LEAST -> OPCODE_I32_GE_S;
        });
        return this;
    }

    /** Runs what follows only when the number on the stack is zero. Closed by {@link #end}. */
    BodyWriter ifZero() {
        writer.write((byte) OPCODE_I32_EQZ).write((byte) OPCODE_IF).write((byte) BLOCK_TYPE_EMPTY);
        return this;
    }

    /** Runs what follows only when the number on the stack is not zero. Closed by {@link #end}. */
    BodyWriter ifNotZero() {
        writer.write((byte) OPCODE_IF).write((byte) BLOCK_TYPE_EMPTY);
        return this;
    }

    /** The other way of the condition just opened. */
    BodyWriter otherwise() {
        writer.write((byte) OPCODE_ELSE);
        return this;
    }

    /** Turns the two numbers on the stack into whether either of them is not zero. */
    BodyWriter or() {
        writer.write((byte) OPCODE_I32_OR);
        return this;
    }

    /** Ends the call where nothing else has. */
    BodyWriter unreachable() {
        writer.write((byte) OPCODE_UNREACHABLE);
        return this;
    }

    /** Closes a condition. */
    BodyWriter end() {
        writer.write((byte) OPCODE_END);
        return this;
    }

    /** The body, framed as a code section entry wants it: its locals, then its instructions. */
    byte[] body() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(body);
        int groups = (wideLocals > 0 ? 1 : 0) + (narrowLocals > 0 ? 1 : 0);
        out.writeUnsignedLeb128(groups);
        if (wideLocals > 0) {
            out.writeUnsignedLeb128(wideLocals).write((byte) TYPE_I64);
        }
        if (narrowLocals > 0) {
            out.writeUnsignedLeb128(narrowLocals).write((byte) TYPE_I32);
        }
        out.write(instructions.toByteArray()).write((byte) OPCODE_END);
        return body.toByteArray();
    }
}
