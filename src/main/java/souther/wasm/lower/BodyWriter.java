package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import souther.wasm.emit.WasmWriter;

/**
 * The instructions of one generated function, written as they are decided.
 *
 * <p>Small on purpose. What is here is what a body of this backend's writes today; an instruction
 * this class does not name is one nothing emits yet, so a reader can see the whole of what a
 * generated body can do by reading this.
 */
final class BodyWriter {

    private static final int OPCODE_END = 0x0b;
    private static final int OPCODE_CALL = 0x10;
    private static final int OPCODE_LOCAL_GET = 0x20;
    private static final int OPCODE_LOCAL_SET = 0x21;
    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_I64_CONST = 0x42;
    private static final int OPCODE_I32_WRAP_I64 = 0xa7;
    private static final int OPCODE_I64_SHR_U = 0x88;

    private static final int TYPE_I32 = 0x7f;
    private static final int TYPE_I64 = 0x7e;

    private final ByteArrayOutputStream instructions = new ByteArrayOutputStream();
    private final WasmWriter writer = new WasmWriter(instructions);
    private final int extraI32Locals;
    private final int extraI64Locals;

    /**
     * @param extraI32Locals how many {@code i32} locals the body needs past its parameters
     * @param extraI64Locals how many {@code i64} locals it needs, which come after those
     */
    BodyWriter(int extraI32Locals, int extraI64Locals) {
        this.extraI32Locals = extraI32Locals;
        this.extraI64Locals = extraI64Locals;
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

    /** The body, framed as a code section entry wants it: its locals, then its instructions. */
    byte[] body() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(body);
        int groups = (extraI32Locals > 0 ? 1 : 0) + (extraI64Locals > 0 ? 1 : 0);
        out.writeUnsignedLeb128(groups);
        if (extraI32Locals > 0) {
            out.writeUnsignedLeb128(extraI32Locals).write((byte) TYPE_I32);
        }
        if (extraI64Locals > 0) {
            out.writeUnsignedLeb128(extraI64Locals).write((byte) TYPE_I64);
        }
        out.write(instructions.toByteArray()).write((byte) OPCODE_END);
        return body.toByteArray();
    }
}
