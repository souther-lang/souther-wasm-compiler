package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import souther.compiler.core.ValueShape;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedProgram;
import souther.compiler.program.Declared;
import souther.compiler.types.TypeSymbol;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.WasmFragment;

/**
 * What every value of a declared shape has in common, written once in static memory.
 *
 * <p>A shape's field names are the same for every value of it, so a cell carries where they are
 * rather than carrying them. That is what lets the runtime write a value it has never been told
 * anything about: it reads the names out of the descriptor the cell points at.
 *
 * <p>In static memory, so it outlives every arena the calls run in and is there before the first
 * call. A descriptor is:
 *
 * <pre>{@code
 * +0   u32 how many fields
 * +4   per field, in the order the shape declares them: u32 where the name is, u32 how long
 * }</pre>
 */
final class Descriptors {

    private final CheckedProgram program;
    private final WasmFragment fragment;
    private final Map<TypeSymbol.AtModule, Integer> placed = new HashMap<>();

    Descriptors(CheckedProgram program, WasmFragment fragment) {
        this.program = program;
        this.fragment = fragment;
    }

    /**
     * The descriptor of a declared shape, placing it if this is the first value of it to be built.
     *
     * <p>Kept, so that a shape used in many places is written down once. What a descriptor holds
     * is the same every time — a shape's field names do not depend on where a value of it is made
     * — so a second copy would be the same bytes again and nothing else.
     */
    int of(TypeSymbol.AtModule name) {
        Integer already = placed.get(name);
        if (already != null) {
            return already;
        }
        List<ValueShape.Field> fields = shape(name).fields();

        // The names first, so that the table pointing at them is written against addresses that
        // are already settled.
        int[] addresses = new int[fields.size()];
        int[] lengths = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            byte[] utf8 = fields.get(i).name().getBytes(StandardCharsets.UTF_8);
            addresses[i] = fragment.place(utf8);
            lengths[i] = utf8.length;
        }

        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            out.writeLittleEndian4(addresses[i]).writeLittleEndian4(lengths[i]);
        }
        int descriptor = fragment.place(table.toByteArray());
        placed.put(name, descriptor);
        return descriptor;
    }

    /** The fields of a declared shape, in the order it declares them. */
    List<ValueShape.Field> fieldsOf(TypeSymbol.AtModule name) {
        return shape(name).fields();
    }

    /** Which field of a shape a name is, by the shape's own ordering. */
    int positionOf(TypeSymbol.AtModule name, String field) {
        return shape(name).positionOf(field);
    }

    /**
     * The shape a name declares.
     *
     * <p>Refused where it is anything else this backend does not write yet, by what it is: a sum
     * and a shape with an invariant are different things to add, and one message about both would
     * name neither.
     */
    private CheckedData.Product shape(TypeSymbol.AtModule name) {
        Declared declared = program.declaration(name);
        CheckedData data = declared.data();
        if (!(data instanceof CheckedData.Product found)) {
            throw new NotLowered(name + " is not written as fields, and this backend writes a shape");
        }
        if (!found.invariants().isEmpty()) {
            throw new NotLowered(name + " has an invariant, and this backend does not check one yet");
        }
        return found;
    }
}
