package souther.wasm.link;

import java.io.ByteArrayOutputStream;
import java.util.List;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.WasmWriter;

/**
 * The two small modules that let a component answer what a program reaches out for.
 *
 * <p>A program reaches out through a call in its own memory: a behavior's number, where the
 * arguments are, and a buffer of its own to write the answer into. None of that is a thing the
 * component model carries, and a component's own call carries a string and answers one. So
 * something has to stand between them, and what it needs is the program's memory — which does not
 * exist until the program has been instantiated, which cannot happen until something answers what
 * it reaches out for.
 *
 * <p>The way out of that is two modules rather than one. The first is instantiated before the
 * program and answers by calling through a table it exports, which is empty. The program is
 * instantiated against it. Its memory now exists, so the component's own calls can be lowered
 * against that memory — and the second module, instantiated last, holds what those lowerings are
 * and writes them into the table as it starts.
 *
 * <p>Nothing in the program knows any of this happened. What it calls has the shape it has always
 * called, and the table is filled before the first call can reach it, because a start function runs
 * at instantiation and nothing outside has been handed anything to call yet.
 */
final class ReachingOut {

    /** The table the first module answers through, and the second one fills. */
    static final String TABLE = "souther#reached";

    private ReachingOut() {
    }

    /**
     * The module that stands where the crossing out of a program is, before there is a program.
     *
     * @param crossings how many behaviors the program reaches out for
     */
    static byte[] answeringThroughATable(int crossings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(out);
        preamble(writer);
        // One type: what a crossing out of a program is.
        section(writer, TYPE, w -> w.writeUnsignedLeb128(1)
                .write(FUNC_TYPE)
                .writeUnsignedLeb128(5)
                .write(new byte[] {I32, I32, I32, I32, I32})
                .writeUnsignedLeb128(1)
                .write(I32));
        section(writer, FUNCTION, w -> w.writeUnsignedLeb128(1).writeUnsignedLeb128(0));
        // A table with a place for each behavior, and no more: a number outside it is one this
        // compiler wrote, and the machine refuses the call rather than reaching somewhere.
        section(writer, TABLE_SECTION, w -> w.writeUnsignedLeb128(1)
                .write(FUNCREF)
                .write(LIMITS_BOTH)
                .writeUnsignedLeb128(crossings)
                .writeUnsignedLeb128(crossings));
        section(writer, EXPORT, w -> {
            w.writeUnsignedLeb128(2);
            name(w, RuntimeAbi.IMPORT_HOST_CALL);
            w.write(EXPORT_FUNC).writeUnsignedLeb128(0);
            name(w, TABLE);
            w.write(EXPORT_TABLE).writeUnsignedLeb128(0);
        });
        section(writer, CODE, w -> {
            byte[] body = body(b -> {
                b.writeUnsignedLeb128(0); // no locals
                for (int i = 0; i < 5; i++) {
                    b.write(LOCAL_GET).writeUnsignedLeb128(i);
                }
                // Which one to reach is the behavior's own number, which is what the table is
                // ordered by — so nothing here holds a second account of which is which.
                b.write(LOCAL_GET).writeUnsignedLeb128(0);
                b.write(CALL_INDIRECT).writeUnsignedLeb128(0).writeUnsignedLeb128(0);
                b.write(END);
            });
            w.writeUnsignedLeb128(1).writeUnsignedLeb128(body.length).write(body);
        });
        return out.toByteArray();
    }

    /**
     * The module that holds what the component's own calls came to, and fills the table with them.
     *
     * <p>One function per behavior, each the same but for which lowering it calls. What each does
     * is the whole of the difference between the two shapes: a component's call writes its answer
     * where it is told to and says where that is, and a program's call writes into a buffer the
     * program owns and says how long the answer wanted to be.
     *
     * @param crossings how many behaviors the program reaches out for
     */
    static byte[] fillingTheTable(int crossings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(out);
        preamble(writer);
        section(writer, TYPE, w -> {
            w.writeUnsignedLeb128(4);
            // 0: what a crossing out of a program is.
            w.write(FUNC_TYPE).writeUnsignedLeb128(5).write(new byte[] {I32, I32, I32, I32, I32})
                    .writeUnsignedLeb128(1).write(I32);
            // 1: what one of the component's own calls came to, told where to put its answer.
            w.write(FUNC_TYPE).writeUnsignedLeb128(3).write(new byte[] {I32, I32, I32})
                    .writeUnsignedLeb128(0);
            // 2: what the arena hands out with.
            w.write(FUNC_TYPE).writeUnsignedLeb128(4).write(new byte[] {I32, I32, I32, I32})
                    .writeUnsignedLeb128(1).write(I32);
            // 3: what a start function is, which takes nothing and answers nothing.
            w.write(FUNC_TYPE).writeUnsignedLeb128(0).writeUnsignedLeb128(0);
        });
        section(writer, IMPORT, w -> {
            w.writeUnsignedLeb128(3 + crossings);
            imported(w, HELD, MEMORY_NAME, IMPORT_MEMORY, held -> held.write((byte) 0)
                    .writeUnsignedLeb128(1));
            imported(w, HELD, TABLE, IMPORT_TABLE, held -> held.write(FUNCREF)
                    .write(LIMITS_BOTH).writeUnsignedLeb128(crossings).writeUnsignedLeb128(crossings));
            imported(w, HELD, RuntimeAbi.CANONICAL_REALLOC, IMPORT_FUNC,
                    held -> held.writeUnsignedLeb128(2));
            for (int i = 0; i < crossings; i++) {
                imported(w, HELD, lowered(i), IMPORT_FUNC, held -> held.writeUnsignedLeb128(1));
            }
        });
        section(writer, FUNCTION, w -> {
            w.writeUnsignedLeb128(crossings + 1);
            for (int i = 0; i < crossings; i++) {
                w.writeUnsignedLeb128(0);
            }
            w.writeUnsignedLeb128(3); // the one that fills the table takes and answers nothing
        });
        // What fills the table is the start function, so it has run before anything outside has
        // been handed anything that could reach a call through it. A memory and a table are not
        // functions, so what the numbers count is the arena's own and one lowering per behavior.
        int adapters = 1 + crossings;
        int filling = adapters + crossings;
        section(writer, START, w -> w.writeUnsignedLeb128(filling));
        section(writer, ELEMENT, w -> {
            w.writeUnsignedLeb128(1)
                    .writeUnsignedLeb128(1)   // a passive segment of function indices
                    .write((byte) 0x00)
                    .writeUnsignedLeb128(crossings);
            for (int i = 0; i < crossings; i++) {
                w.writeUnsignedLeb128(adapters + i);
            }
        });
        section(writer, CODE, w -> {
            w.writeUnsignedLeb128(crossings + 1);
            for (int i = 0; i < crossings; i++) {
                byte[] body = adapting(1 + i);
                w.writeUnsignedLeb128(body.length).write(body);
            }
            byte[] filled = filling(crossings);
            w.writeUnsignedLeb128(filled.length).write(filled);
        });
        return out.toByteArray();
    }

    /** The name the lowering of one behavior's own call is imported under. */
    static String lowered(int ordinal) {
        return "souther#lowered-" + ordinal;
    }

    /** What the module the two of these stand between calls its memory. */
    private static final String MEMORY_NAME = RuntimeAbi.MEMORY;

    /** The module name both of these import everything under. */
    private static final String HELD = "souther";

    /**
     * One behavior: ask the arena where the answer may go, make the call, and give the program back
     * what it asked for.
     *
     * <p>The program owns the buffer and says how long it is, and what comes back says how long the
     * answer wanted to be — so where the answer does not fit, nothing is written and the program
     * asks again against a longer one. That is what the program's own crossing has always meant,
     * and it is the whole of what is different about the component's.
     */
    private static byte[] adapting(int lowering) {
        return body(b -> {
            b.writeUnsignedLeb128(1).writeUnsignedLeb128(1).write(I32); // one local: where it went
            // Eight bytes for a pointer and a length, from the arena the program hands out with.
            b.write(I32_CONST).writeSignedLeb128(0)
                    .write(I32_CONST).writeSignedLeb128(0)
                    .write(I32_CONST).writeSignedLeb128(4)
                    .write(I32_CONST).writeSignedLeb128(8)
                    .write(CALL).writeUnsignedLeb128(0)
                    .write(LOCAL_SET).writeUnsignedLeb128(5);
            // The call, told the arguments and where its answer goes.
            b.write(LOCAL_GET).writeUnsignedLeb128(1)
                    .write(LOCAL_GET).writeUnsignedLeb128(2)
                    .write(LOCAL_GET).writeUnsignedLeb128(5)
                    .write(CALL).writeUnsignedLeb128(lowering);
            // Where it does fit, into the program's own buffer.
            b.write(LOCAL_GET).writeUnsignedLeb128(5).write(I32_LOAD).writeUnsignedLeb128(2)
                    .writeUnsignedLeb128(4); // the length
            b.write(LOCAL_GET).writeUnsignedLeb128(4);
            b.write(I32_LE_U);
            b.write(IF).write(EMPTY_BLOCK);
            b.write(LOCAL_GET).writeUnsignedLeb128(3);
            b.write(LOCAL_GET).writeUnsignedLeb128(5).write(I32_LOAD).writeUnsignedLeb128(2)
                    .writeUnsignedLeb128(0); // where the answer is
            b.write(LOCAL_GET).writeUnsignedLeb128(5).write(I32_LOAD).writeUnsignedLeb128(2)
                    .writeUnsignedLeb128(4);
            b.write(MISC).writeUnsignedLeb128(10).write((byte) 0).write((byte) 0); // memory.copy
            b.write(END);
            // And how long it wanted to be, either way.
            b.write(LOCAL_GET).writeUnsignedLeb128(5).write(I32_LOAD).writeUnsignedLeb128(2)
                    .writeUnsignedLeb128(4);
            b.write(END);
        });
    }

    /** Puts each behavior's own function where the number that behavior goes by looks. */
    private static byte[] filling(int crossings) {
        return body(b -> {
            b.writeUnsignedLeb128(0);
            b.write(I32_CONST).writeSignedLeb128(0)
                    .write(I32_CONST).writeSignedLeb128(0)
                    .write(I32_CONST).writeSignedLeb128(crossings)
                    .write(MISC).writeUnsignedLeb128(12) // table.init
                    .writeUnsignedLeb128(0)
                    .writeUnsignedLeb128(0);
            b.write(END);
        });
    }

    private static void preamble(WasmWriter writer) {
        writer.write(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
    }

    private static void section(WasmWriter writer, int id, java.util.function.Consumer<WasmWriter> what) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        what.accept(new WasmWriter(payload));
        byte[] held = payload.toByteArray();
        writer.write((byte) id).writeUnsignedLeb128(held.length).write(held);
    }

    private static byte[] body(java.util.function.Consumer<WasmWriter> what) {
        ByteArrayOutputStream held = new ByteArrayOutputStream();
        what.accept(new WasmWriter(held));
        return held.toByteArray();
    }

    private static void imported(WasmWriter writer, String module, String held, int kind,
            java.util.function.Consumer<WasmWriter> what) {
        name(writer, module);
        name(writer, held);
        writer.write((byte) kind);
        what.accept(writer);
    }

    private static void name(WasmWriter writer, String held) {
        byte[] utf8 = held.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeUnsignedLeb128(utf8.length).write(utf8);
    }

    private static final int TYPE = 1;
    private static final int IMPORT = 2;
    private static final int FUNCTION = 3;
    private static final int TABLE_SECTION = 4;
    private static final int EXPORT = 7;
    private static final int START = 8;
    private static final int ELEMENT = 9;
    private static final int CODE = 10;

    private static final byte FUNC_TYPE = 0x60;
    private static final byte I32 = 0x7f;
    private static final byte FUNCREF = 0x70;
    private static final byte LIMITS_BOTH = 0x01;
    private static final byte EXPORT_FUNC = 0x00;
    private static final byte EXPORT_TABLE = 0x01;
    private static final int IMPORT_FUNC = 0x00;
    private static final int IMPORT_TABLE = 0x01;
    private static final int IMPORT_MEMORY = 0x02;
    private static final byte LOCAL_GET = 0x20;
    private static final byte LOCAL_SET = 0x21;
    private static final byte I32_CONST = 0x41;
    private static final byte I32_LOAD = 0x28;
    private static final byte I32_LE_U = 0x4d;
    private static final byte IF = 0x04;
    private static final byte EMPTY_BLOCK = 0x40;
    private static final byte CALL = 0x10;
    private static final byte CALL_INDIRECT = 0x11;
    private static final byte MISC = (byte) 0xfc;
    private static final byte END = 0x0b;

    /** The two modules, so a reader can ask what they are without a component around them. */
    static List<byte[]> both(int crossings) {
        return List.of(answeringThroughATable(crossings), fillingTheTable(crossings));
    }
}
