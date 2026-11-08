package souther.wasm.link;

import java.io.ByteArrayOutputStream;
import java.util.List;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.WasmWriter;

/**
 * The two small modules that let a component answer what a program reaches out for.
 *
 * <p>A program reaches out through a call in its own memory: a behavior's number and where the
 * arguments are, answered with where the answer is. None of that is a thing the component model
 * carries, and a component's own call carries a string and answers one. So
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
                .writeUnsignedLeb128(3)
                .write(new byte[] {I32, I32, I32})
                .writeUnsignedLeb128(1)
                .write(I64));
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
                for (int i = 0; i < 3; i++) {
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
     * is the whole of the difference between the two shapes: a component's call writes where its
     * answer is into two words it is told of, and a program's call answers the two packed.
     *
     * @param crossings how many behaviors the program reaches out for
     */
    static byte[] fillingTheTable(int crossings) {
        byte[] lowering = functionType(List.of(I32, I32, I32), List.of());
        List<byte[]> types = new java.util.ArrayList<>();
        List<byte[]> adapters = new java.util.ArrayList<>();
        for (int i = 0; i < crossings; i++) {
            types.add(lowering);
            adapters.add(adapting(1 + i));
        }
        return filling(types, adapters);
    }

    /**
     * The module that fills the table, for a program whose behaviors cross typed.
     *
     * <p>The table and what fills it are the JSON one's. What differs is what each function in it
     * does with the two places the program's call out hands it: the first is where the arguments
     * are, flattened one core value to an eight-byte slot or laid out as the record of them, and
     * the second is where the answer goes. Each lowering takes the core values its typed function
     * flattens to, so each function here loads them out of the slots for it, and puts back the one
     * core value it answers where the answer goes; a lowering whose answer is laid out in memory is
     * handed the second place to lay it out at.
     *
     * @param lowered the core function each behavior's own call is lowered to, by its number
     */
    static byte[] fillingTheTableTyped(List<CanonicalAbi.CoreSignature> lowered) {
        List<byte[]> types = new java.util.ArrayList<>();
        List<byte[]> adapters = new java.util.ArrayList<>();
        for (int i = 0; i < lowered.size(); i++) {
            CanonicalAbi.CoreSignature each = lowered.get(i);
            types.add(functionType(each.params().stream().map(ReachingOut::core).toList(),
                    each.results().stream().map(ReachingOut::core).toList()));
            adapters.add(adaptingTyped(1 + i, each));
        }
        return filling(types, adapters);
    }

    /**
     * The module that fills the table, whichever way the program's behaviors cross: it imports the
     * program's memory, the table, the arena and each behavior's lowering, of the type it is given
     * for it, and holds a function per behavior, the body it is given for it, which the start
     * function puts in the table at the behavior's number. A function in the table is what a
     * crossing out of a program is; the lowering it calls is the first function after the arena's.
     *
     * @param lowerings the type of each behavior's lowering, by its number
     * @param adapters the body of the function in the table for each behavior, by its number
     */
    private static byte[] filling(List<byte[]> lowerings, List<byte[]> adapters) {
        int crossings = lowerings.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(out);
        preamble(writer);
        section(writer, TYPE, w -> {
            w.writeUnsignedLeb128(3 + crossings);
            // 0: what a crossing out of a program is.
            w.write(functionType(List.of(I32, I32, I32), List.of(I64)));
            // 1: what the arena hands out with.
            w.write(functionType(List.of(I32, I32, I32, I32), List.of(I32)));
            // 2: what a start function is, which takes nothing and answers nothing.
            w.write(functionType(List.of(), List.of()));
            // 3 on: what each behavior's own call is lowered to.
            lowerings.forEach(w::write);
        });
        section(writer, IMPORT, w -> {
            w.writeUnsignedLeb128(3 + crossings);
            imported(w, HELD, MEMORY_NAME, IMPORT_MEMORY, held -> held.write((byte) 0)
                    .writeUnsignedLeb128(1));
            imported(w, HELD, TABLE, IMPORT_TABLE, held -> held.write(FUNCREF)
                    .write(LIMITS_BOTH).writeUnsignedLeb128(crossings).writeUnsignedLeb128(crossings));
            imported(w, HELD, RuntimeAbi.CANONICAL_REALLOC, IMPORT_FUNC,
                    held -> held.writeUnsignedLeb128(1));
            for (int i = 0; i < crossings; i++) {
                int type = 3 + i;
                imported(w, HELD, lowered(i), IMPORT_FUNC, held -> held.writeUnsignedLeb128(type));
            }
        });
        section(writer, FUNCTION, w -> {
            w.writeUnsignedLeb128(crossings + 1);
            for (int i = 0; i < crossings; i++) {
                w.writeUnsignedLeb128(0);
            }
            w.writeUnsignedLeb128(2); // the one that fills the table takes and answers nothing
        });
        // What fills the table is the start function, so it has run before anything outside has
        // been handed anything that could reach a call through it. A memory and a table are not
        // functions, so what the numbers count is the arena's own and one lowering per behavior.
        int first = 1 + crossings;
        int filling = first + crossings;
        section(writer, START, w -> w.writeUnsignedLeb128(filling));
        section(writer, ELEMENT, w -> {
            w.writeUnsignedLeb128(1)
                    .writeUnsignedLeb128(1)   // a passive segment of function indices
                    .write((byte) 0x00)
                    .writeUnsignedLeb128(crossings);
            for (int i = 0; i < crossings; i++) {
                w.writeUnsignedLeb128(first + i);
            }
        });
        section(writer, CODE, w -> {
            w.writeUnsignedLeb128(crossings + 1);
            for (byte[] body : adapters) {
                w.writeUnsignedLeb128(body.length).write(body);
            }
            byte[] filled = filling(crossings);
            w.writeUnsignedLeb128(filled.length).write(filled);
        });
        return out.toByteArray();
    }

    /** A core function type, as a type section holds one. */
    private static byte[] functionType(List<Byte> params, List<Byte> results) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter w = new WasmWriter(out);
        w.write(FUNC_TYPE).writeUnsignedLeb128(params.size());
        params.forEach(each -> w.write((byte) each));
        w.writeUnsignedLeb128(results.size());
        results.forEach(each -> w.write((byte) each));
        return out.toByteArray();
    }

    /**
     * One typed behavior: the core values its lowering takes, out of the slots the program put
     * them in or as the place the record of them is; the call; and the one core value it answered
     * put where the answer goes, or nothing to put where the lowering laid the answer out there.
     */
    private static byte[] adaptingTyped(int lowering, CanonicalAbi.CoreSignature signature) {
        return body(b -> {
            b.writeUnsignedLeb128(0);
            boolean answersOne = signature.results().size() == 1;
            if (answersOne) {
                b.write(LOCAL_GET).writeUnsignedLeb128(2);
            }
            if (signature.argumentsInMemory()) {
                b.write(LOCAL_GET).writeUnsignedLeb128(1);
            } else {
                List<CanonicalAbi.Flat> arguments = signature.arguments();
                for (int k = 0; k < arguments.size(); k++) {
                    b.write(LOCAL_GET).writeUnsignedLeb128(1)
                            .write(I64_LOAD).writeUnsignedLeb128(3).writeUnsignedLeb128(8 * k);
                    if (arguments.get(k) == CanonicalAbi.Flat.I32) {
                        b.write(I32_WRAP_I64);
                    }
                }
            }
            if (signature.answerInMemory()) {
                b.write(LOCAL_GET).writeUnsignedLeb128(2);
            }
            b.write(CALL).writeUnsignedLeb128(lowering);
            if (answersOne) {
                if (signature.results().getFirst() == CanonicalAbi.Flat.I32) {
                    b.write(I64_EXTEND_I32_U);
                }
                b.write(I64_STORE).writeUnsignedLeb128(3).writeUnsignedLeb128(0);
            }
            b.write(I64_CONST).writeSignedLeb128(0);
            b.write(END);
        });
    }

    private static byte core(CanonicalAbi.Flat flat) {
        return switch (flat) {
            case I32 -> I32;
            case I64 -> I64;
            case F32 -> (byte) 0x7d;
            case F64 -> (byte) 0x7c;
        };
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
     * One behavior: ask the arena for the two words the answer is said in, make the call, and give
     * the program back what they say.
     *
     * <p>The lowering has already put the answer in the program's arena, through its realloc, so
     * nothing is copied: where it is and how long are packed as the program's crossing answers
     * them. The lowering is called once whatever the length of what it answers.
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
                    .write(LOCAL_SET).writeUnsignedLeb128(3);
            // The call, told the arguments and where its answer goes.
            b.write(LOCAL_GET).writeUnsignedLeb128(1)
                    .write(LOCAL_GET).writeUnsignedLeb128(2)
                    .write(LOCAL_GET).writeUnsignedLeb128(3)
                    .write(CALL).writeUnsignedLeb128(lowering);
            // Where the answer is, low; how long it is, high.
            b.write(LOCAL_GET).writeUnsignedLeb128(3).write(I32_LOAD).writeUnsignedLeb128(2)
                    .writeUnsignedLeb128(0)
                    .write(I64_EXTEND_I32_U);
            b.write(LOCAL_GET).writeUnsignedLeb128(3).write(I32_LOAD).writeUnsignedLeb128(2)
                    .writeUnsignedLeb128(4)
                    .write(I64_EXTEND_I32_U)
                    .write(I64_CONST).writeSignedLeb128(32)
                    .write(I64_SHL)
                    .write(I64_OR);
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
    private static final byte I64 = 0x7e;
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
    private static final byte I64_CONST = 0x42;
    private static final byte I64_SHL = (byte) 0x86;
    private static final byte I64_OR = (byte) 0x84;
    private static final byte I64_EXTEND_I32_U = (byte) 0xad;
    private static final byte I64_LOAD = 0x29;
    private static final byte I64_STORE = 0x37;
    private static final byte I32_WRAP_I64 = (byte) 0xa7;
    private static final byte CALL = 0x10;
    private static final byte CALL_INDIRECT = 0x11;
    private static final byte MISC = (byte) 0xfc;
    private static final byte END = 0x0b;

    /** The two modules, so a reader can ask what they are without a component around them. */
    static List<byte[]> both(int crossings) {
        return List.of(answeringThroughATable(crossings), fillingTheTable(crossings));
    }
}
