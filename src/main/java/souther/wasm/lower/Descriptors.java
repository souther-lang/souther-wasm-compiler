package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import souther.compiler.core.ValueShape;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedProgram;
import souther.compiler.types.Type;
import souther.compiler.types.TypeSymbol;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.WasmFragment;

/**
 * Every declared type, written once in static memory for the runtime to walk.
 *
 * <p>Reading and writing are driven by the declaration rather than by the value or the document,
 * and a descriptor is the whole of what the emitter says about a type. It places one per type and
 * passes its address; nothing else about the shape of a value crosses into a generated body.
 *
 * <p>Placing one is what discovers what this backend cannot write yet, so the refusals live here:
 * met while the type is being described rather than while a body is being emitted, which is where
 * the question is actually asked.
 */
final class Descriptors {

    /** What kind of type a descriptor describes, as the runtime reads it. */
    private static final int KIND_INT = 0;
    private static final int KIND_BOOL = 1;
    private static final int KIND_STRING = 2;
    private static final int KIND_UNIT = 3;
    private static final int KIND_PRODUCT = 4;
    private static final int KIND_SUM = 5;
    private static final int KIND_LIST = 6;
    private static final int KIND_OPTION = 7;
    private static final int KIND_SET = 8;
    private static final int KIND_MAP = 9;

    private final CheckedProgram program;
    private final WasmFragment fragment;
    private final Map<Type, Integer> placed = new HashMap<>();
    private final Map<TypeSymbol.AtModule, Integer> byName = new HashMap<>();

    private final ToIntFunction<TypeSymbol.AtModule> checks;

    Descriptors(CheckedProgram program, WasmFragment fragment,
            ToIntFunction<TypeSymbol.AtModule> checks) {
        this.program = program;
        this.fragment = fragment;
        this.checks = checks;
    }

    /**
     * The descriptor of a type, placing it if this is the first place to want one.
     *
     * <p>Kept, so that a type used in many places is written down once. What a descriptor holds is
     * the same every time, and a value's cell carries its address, so a second copy would make one
     * type look like two to whatever compares them — which is how a sum decides which of its cases
     * a value it is handed is.
     */
    int of(Type type) {
        Integer already = placed.get(type);
        if (already != null) {
            return already;
        }
        int descriptor = switch (type) {
            case Type.Prim.INT -> scalar(KIND_INT);
            case Type.Prim.BOOL -> scalar(KIND_BOOL);
            case Type.Prim.STRING -> scalar(KIND_STRING);
            case Type.Ref reference when reference.name() instanceof TypeSymbol.AtModule named ->
                    ofDeclared(named);
            case Type.ListOf list -> holding(KIND_LIST, list.element());
            case Type.OptionOf option -> holding(KIND_OPTION, option.element());
            case Type.SetOf set -> holding(KIND_SET, set.element());
            case Type.MapOf map -> {
                // A key is written as the name of an object's member, so only a type that is
                // already text is one this backend writes. What a date or a declared key is
                // written as is a rule of its own and is not read off the key's type.
                if (map.key() != Type.Prim.STRING) {
                    throw new NotLowered("a map keyed by " + map.key()
                            + ", and this backend writes one keyed by a String");
                }
                yield pair(KIND_MAP, map.key(), map.value());
            }
            default -> throw new NotLowered("a " + type
                    + ", which this backend does not write yet — it writes a scalar, a shape and a sum");
        };
        placed.put(type, descriptor);
        return descriptor;
    }

    /** The fields of a declared shape, in the order it declares them. */
    List<ValueShape.Field> fieldsOf(TypeSymbol.AtModule name) {
        return product(name).fields();
    }

    /** What a declared shape says must hold of its values. */
    List<ValueShape.Invariant> invariantsOf(TypeSymbol.AtModule name) {
        return product(name).invariants();
    }

    /** Which field of a shape a name is, by the shape's own ordering. */
    int positionOf(TypeSymbol.AtModule name, String field) {
        return product(name).positionOf(field);
    }

    /** The descriptor of a declared type, by its name. */
    int ofDeclared(TypeSymbol.AtModule name) {
        Integer already = byName.get(name);
        if (already != null) {
            return already;
        }
        CheckedData data = program.declaration(name).data();
        return switch (data) {
            case CheckedData.Unit ignored -> {
                int descriptor = scalar(KIND_UNIT);
                byName.put(name, descriptor);
                yield descriptor;
            }
            case CheckedData.Product shape -> composite(KIND_PRODUCT, name, shape.fields().stream()
                    .map(field -> new Member(field.name(), field.type()))
                    .toList());
            // A sum's cases are its leaves: a case written as another sum is carried here as the
            // cases under it, so nothing nested reaches this and the tag always names a leaf.
            case CheckedData.Sum choice -> composite(KIND_SUM, name, choice.cases().stream()
                    .map(each -> new Member(each.name(), new Type.Ref(each)))
                    .toList());
        };
    }

    /** A field of a shape, or a case of a sum: what it is called and what it holds. */
    private record Member(String name, Type type) {
    }

    /**
     * A descriptor for a type whose one member has no name.
     *
     * <p>Written with the same shape as a product's or a sum's, so that everything with members is
     * read the same way, and with an empty name because nothing names what a list holds.
     */
    private int holding(int kind, Type element) {
        int member = of(element);
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        new WasmWriter(table)
                .writeLittleEndian4(kind)
                .writeLittleEndian4(1)
                .writeLittleEndian4(0)
                .writeLittleEndian4(0)
                .writeLittleEndian4(member);
        return fragment.place(table.toByteArray());
    }

    /** A descriptor for a type with two unnamed members: a map's keys and its values. */
    private int pair(int kind, Type first, Type second) {
        int keys = of(first);
        int values = of(second);
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        new WasmWriter(table)
                .writeLittleEndian4(kind)
                .writeLittleEndian4(2)
                .writeLittleEndian4(0).writeLittleEndian4(0).writeLittleEndian4(keys)
                .writeLittleEndian4(0).writeLittleEndian4(0).writeLittleEndian4(values);
        return fragment.place(table.toByteArray());
    }

    private int scalar(int kind) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        new WasmWriter(table).writeLittleEndian4(kind);
        return fragment.place(table.toByteArray());
    }

    /**
     * A descriptor for a type whose members have descriptors of their own.
     *
     * <p>Its address is taken and remembered before its members are described, because a type may
     * hold a value of itself. Left the other way round, describing the member would ask for the
     * descriptor being described and neither would ever have an address.
     */
    private int composite(int kind, TypeSymbol.AtModule name, List<Member> members) {
        // A product carries its own name and the slot of what checks it, after its fields.
        boolean product = kind == KIND_PRODUCT;
        int descriptor = fragment.reserve(4 + 4 + 12 * members.size() + (product ? 12 : 0));
        byName.put(name, descriptor);

        List<int[]> written = new ArrayList<>();
        for (Member member : members) {
            byte[] utf8 = member.name().getBytes(StandardCharsets.UTF_8);
            written.add(new int[] {fragment.place(utf8), utf8.length, of(member.type())});
        }

        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(kind).writeLittleEndian4(members.size());
        for (int[] member : written) {
            out.writeLittleEndian4(member[0])
                    .writeLittleEndian4(member[1])
                    .writeLittleEndian4(member[2]);
        }
        if (product) {
            byte[] own = name.name().getBytes(StandardCharsets.UTF_8);
            out.writeLittleEndian4(fragment.place(own))
                    .writeLittleEndian4(own.length)
                    .writeLittleEndian4(checks.applyAsInt(name));
        }
        fragment.fill(descriptor, table.toByteArray());
        return descriptor;
    }

    private CheckedData.Product product(TypeSymbol.AtModule name) {
        if (program.declaration(name).data() instanceof CheckedData.Product found) {
            return found;
        }
        throw new NotLowered(name + " is not written as fields, and a field is read off one that is");
    }
}
