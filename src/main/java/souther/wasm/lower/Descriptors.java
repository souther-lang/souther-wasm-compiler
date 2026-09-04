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
    private static final int KIND_ENUMERATION = 10;
    private static final int KIND_TUPLE = 11;
    private static final int KIND_DECIMAL = 12;
    private static final int KIND_DATE = 13;
    private static final int KIND_TIME = 14;
    private static final int KIND_DATE_TIME = 15;
    private static final int KIND_INSTANT = 16;
    private static final int KIND_NEWTYPE = 17;
    /**
     * What the empty list's elements are, of which there are none.
     *
     * <p>A list written with nothing in it, standing where nothing said what it holds, has an
     * element type no value is of. A descriptor is still wanted, because a list's descriptor names
     * its element and something has to be there — but nothing reads or writes through this one,
     * and every path that would is the one that ends a call on a kind it does not know. So it is a
     * number no reader handles on purpose, rather than a stand-in for a type that would answer.
     */
    private static final int KIND_NOTHING = 18;

    private final CheckedProgram program;
    private final WasmFragment fragment;
    private final Map<Type, Integer> placed = new HashMap<>();
    private final Map<TypeSymbol.AtModule, Integer> byName = new HashMap<>();
    private final Map<String, Integer> languageCases = new HashMap<>();

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
            case Type.Prim.DECIMAL -> scalar(KIND_DECIMAL);
            case Type.Prim.DATE -> scalar(KIND_DATE);
            case Type.Prim.TIME -> scalar(KIND_TIME);
            case Type.Prim.DATETIME -> scalar(KIND_DATE_TIME);
            case Type.Prim.INSTANT -> scalar(KIND_INSTANT);
            case Type.Nothing ignored -> scalar(KIND_NOTHING);
            case Type.Ref reference when reference.name() instanceof TypeSymbol.AtModule named ->
                    ofDeclared(named);
            case Type.ListOf list -> holding(KIND_LIST, list.element());
            case Type.OptionOf option -> holding(KIND_OPTION, option.element());
            case Type.SetOf set -> holding(KIND_SET, set.element());
            case Type.TupleOf together -> {
                List<int[]> places = new ArrayList<>();
                for (Type each : together.elements()) {
                    places.add(new int[] {0, 0, of(each)});
                }
                yield written(KIND_TUPLE, null, places);
            }
            case Type.Union union -> alternatives(null, List.copyOf(union.members()));
            case Type.MapOf map -> {
                // A key is written as the name of an object's member, so a type keys a map
                // exactly when it is written as a bare string — and it is written the same way
                // there as anywhere else, which is why the key carries its own descriptor and
                // nothing here decides a second time what a key of it looks like.
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

    /**
     * The set of ways to round, which the language declares.
     *
     * <p>An operation that rounds is told which way as a place among these, because what it does
     * with it is pick one of that many ways — and a value of one of the cases is typed as that
     * case, so the set it belongs to is asked of the language rather than of the value.
     */
    int roundingModes() {
        for (CheckedData each : program.languageDeclarations()) {
            if (each instanceof CheckedData.Sum held && held.name().name().equals("RoundingMode")) {
                return ofDeclared(held.name());
            }
        }
        throw new NotLowered("the language declares no set of ways to round");
    }

    /**
     * What a declared shape says must hold of its values.
     *
     * <p>Asked of what a value is made of rather than of which form it was declared in: a name for
     * a value of another type carries clauses exactly as a product does, and they are the same
     * clauses about the same field.
     */
    List<ValueShape.Invariant> invariantsOf(TypeSymbol.AtModule name) {
        return declared(name) instanceof CheckedData.WithFields found
                ? found.invariants() : List.of();
    }

    /**
     * Whether where a field lies is settled by the type a read is written against.
     *
     * <p>It is not, where that type is a set of alternatives: the field is one every case of it
     * spreads, and two cases need not put it in the same place. Which case a value turned out to
     * be is a thing there has to be a value to know.
     */
    boolean settlesWhereAFieldLies(TypeSymbol.AtModule name) {
        return declared(name) instanceof CheckedData.WithFields;
    }

    /** Which field of a shape a name is, by the shape's own ordering. */
    int positionOf(TypeSymbol.AtModule name, String field) {
        return product(name).positionOf(field);
    }

    /**
     * The descriptor of whatever a name in a set of alternatives names.
     *
     * <p>A member may be a type of the model, a scalar the language declares, or a case the
     * language declares on its own — the absence a kernel answers with. The last has no
     * declaration to read, and is a type with one value by being one.
     */
    int ofMember(TypeSymbol name) {
        return switch (name) {
            case TypeSymbol.AtModule declared -> ofDeclared(declared);
            case TypeSymbol.Primitive scalar -> of(scalarNamed(scalar.name()));
            default -> languageCases.computeIfAbsent(name.name(), each -> scalar(KIND_UNIT));
        };
    }

    private static Type scalarNamed(String written) {
        return switch (written) {
            case "Int" -> Type.Prim.INT;
            case "Bool" -> Type.Prim.BOOL;
            case "String" -> Type.Prim.STRING;
            case "Decimal" -> Type.Prim.DECIMAL;
            case "Date" -> Type.Prim.DATE;
            case "Time" -> Type.Prim.TIME;
            case "DateTime" -> Type.Prim.DATETIME;
            case "Instant" -> Type.Prim.INSTANT;
            default -> throw new NotLowered("a " + written
                    + " among alternatives, which this backend does not write yet");
        };
    }

    /** The descriptor of a declared type, by its name. */
    int ofDeclared(TypeSymbol.AtModule name) {
        Integer already = byName.get(name);
        if (already != null) {
            return already;
        }
        CheckedData data = declared(name);
        return switch (data) {
            case CheckedData.Unit ignored -> {
                int descriptor = scalar(KIND_UNIT);
                byName.put(name, descriptor);
                yield descriptor;
            }
            case CheckedData.Product shape -> composite(KIND_PRODUCT, name, shape.fields().stream()
                    .map(field -> new Member(field.name(), field.type()))
                    .toList());
            // A name for a value of another type. Laid out as the one-field product it is made
            // like, because that is what a value of it is made of, and written as the type it is a
            // name for is written — which is the one thing the two forms do not answer alike.
            case CheckedData.Newtype named -> composite(KIND_NEWTYPE, name, named.fields().stream()
                    .map(field -> new Member(field.name(), field.type()))
                    .toList());
            // A sum's cases are its leaves: a case written as another sum is carried here as the
            // cases under it, so nothing nested reaches this and the tag always names a leaf.
            case CheckedData.Sum choice -> alternatives(name, choice.cases());
        };
    }

    /**
     * A set of alternatives, in the form the set travels as.
     *
     * <p>Where every one of them carries nothing but which it is, the value written is the name
     * itself; where any carries something of its own, the name stands beside it under a key. That
     * is the language's rule about how a set of alternatives crosses, and both backends have to
     * read it the same way or one set is two documents.
     *
     * @param name the type the set is declared as, or null where nobody named the members together
     */
    private int alternatives(TypeSymbol.AtModule name, List<TypeSymbol> members) {
        boolean carriesNothing = !members.isEmpty() && members.stream().allMatch(
                each -> each instanceof TypeSymbol.AtModule held
                        && declared(held) instanceof CheckedData.Unit);
        List<int[]> described = new ArrayList<>();
        for (TypeSymbol member : members) {
            byte[] utf8 = member.name().getBytes(StandardCharsets.UTF_8);
            described.add(new int[] {fragment.place(utf8), utf8.length, ofMember(member)});
        }
        return written(carriesNothing ? KIND_ENUMERATION : KIND_SUM, name, described);
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
        int descriptor = reserveFor(kind, name, members.size());
        List<int[]> written = new ArrayList<>();
        for (Member member : members) {
            byte[] utf8 = member.name().getBytes(StandardCharsets.UTF_8);
            written.add(new int[] {fragment.place(utf8), utf8.length, of(member.type())});
        }
        return filled(kind, name, descriptor, written);
    }

    /** A set of alternatives whose members were described before the descriptor was reserved. */
    private int written(int kind, TypeSymbol.AtModule name, List<int[]> members) {
        int descriptor = reserveFor(kind, name, members.size());
        return filled(kind, name, descriptor, members);
    }

    private int reserveFor(int kind, TypeSymbol.AtModule name, int members) {
        // A form a value is built out of carries its own name and the slot of what checks it,
        // after its fields.
        int descriptor = fragment.reserve(4 + 4 + 12 * members + (carriesRules(kind) ? 12 : 0));
        if (name != null) {
            byName.put(name, descriptor);
        }
        return descriptor;
    }

    private int filled(int kind, TypeSymbol.AtModule name, int descriptor, List<int[]> written) {
        boolean product = carriesRules(kind);
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(kind).writeLittleEndian4(written.size());
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

    /**
     * Whether a form is one a value is built out of field by field, and so one that carries what
     * must hold of a value and the name a violation is reported under.
     *
     * <p>Asked in one place because two forms answer it and the room a descriptor takes and what
     * goes in that room are two different lines: a form added to one and not the other would leave
     * a descriptor saying it has a check, at bytes that are somebody else's.
     */
    private static boolean carriesRules(int kind) {
        return kind == KIND_PRODUCT || kind == KIND_NEWTYPE;
    }

    /**
     * What a name declares, whether the model declared it or the language did.
     *
     * <p>A rounding mode is the language's, and a body that names one is naming a type like any
     * other — so it is found where it is declared rather than only where a module would put it.
     */
    private CheckedData declared(TypeSymbol.AtModule name) {
        for (CheckedData each : program.languageDeclarations()) {
            // By the name every declaration answers, not by asking each form in turn: a form this
            // does not name would be looked for where a module's declarations are and not found
            // there, and the day the language declares one that is what would happen.
            if (each.name().equals(name)) {
                return each;
            }
        }
        return program.declaration(name).data();
    }

    private CheckedData.WithFields product(TypeSymbol.AtModule name) {
        if (declared(name) instanceof CheckedData.WithFields found) {
            return found;
        }
        throw new NotLowered(name + " is not written as fields, and a field is read off one that is");
    }
}
