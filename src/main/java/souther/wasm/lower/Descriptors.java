package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import souther.compiler.core.Core;
import souther.compiler.core.ValueShape;
import souther.compiler.program.CheckedAlternativesForm;
import souther.compiler.program.CheckedBehavior;
import souther.compiler.program.CheckedBoundaryOutput;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedModule;
import souther.compiler.program.CheckedProgram;
import souther.compiler.types.Type;
import souther.compiler.types.TypeKey;
import souther.compiler.types.TypeSymbol;
import souther.wasm.abi.RuntimeAbi;
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
 *
 * <p>Before each descriptor, sixteen bytes name what reads, writes and orders a value of its type
 * ({@link RuntimeAbi.Operations}), each only where the program asks it of the type: a descriptor
 * is not a reader, a writer and an order, and a slot names a function the module then carries. What
 * is asked is said as it is asked ({@link #toRead}, {@link #toWrite}, {@link #toOrder}) and settled
 * once everything has been described ({@link #finish}), because asking one thing of a type asks
 * things of the types it is made of, and of types described after it.
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
    /**
     * An exact quotient, which nothing reads from a document or writes into one: what a collection
     * of them is ordered by, and what a comparison of them asks.
     */
    private static final int KIND_RATIONAL = 19;

    private final CheckedProgram program;
    private final WasmFragment fragment;
    private final Map<Type, Integer> placed = new HashMap<>();
    private final Map<TypeSymbol.AtModule, Integer> byName = new HashMap<>();
    private final Map<TypeSymbol.LanguageCase, Integer> languageCases = new HashMap<>();

    private final ToIntFunction<TypeSymbol.AtModule> checks;

    /** Every descriptor placed, by its address: its kind, where its operations go, its members. */
    private final Map<Integer, Described> described = new LinkedHashMap<>();

    /** The descriptors whose values the program reads from a document. */
    private final Set<Integer> reads = new HashSet<>();
    /** The descriptors whose values the program writes into a document. */
    private final Set<Integer> writes = new HashSet<>();
    /** The descriptors whose values the program places among a set's members. */
    private final Set<Integer> orders = new HashSet<>();

    /**
     * The newtypes over a list one of whose clauses is that the list holds no value twice, by the
     * descriptor of the list: reading one finds what it holds twice, by ordering and writing it.
     */
    private final Map<Integer, Integer> unique = new HashMap<>();

    /** Where each pattern's machine is placed, for a clause and a body alike. */
    private final Patterns patterns;

    /** What each type's clauses are reported as. */
    private final Clauses clauses;

    /**
     * Every union a behavior answers, as the checker settled it to cross: its leaves and the form
     * they travel in.
     */
    private final Map<Type.Union, CheckedBoundaryOutput.Cases> answered = new HashMap<>();

    Descriptors(CheckedProgram program, WasmFragment fragment,
            ToIntFunction<TypeSymbol.AtModule> checks) {
        this.program = program;
        this.fragment = fragment;
        this.checks = checks;
        this.patterns = new Patterns(fragment);
        this.clauses = new Clauses(fragment, patterns);
        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                settle(behavior.signature().output());
            }
        }
    }

    private void settle(CheckedBoundaryOutput output) {
        switch (output) {
            case CheckedBoundaryOutput.Cases cases -> answered.put(cases.type(), cases);
            case CheckedBoundaryOutput.ListOf list -> settle(list.element());
            case CheckedBoundaryOutput.SetOf set -> settle(set.element());
            case CheckedBoundaryOutput.MapOf map -> settle(map.value());
            case CheckedBoundaryOutput.Scalar ignored -> { }
            case CheckedBoundaryOutput.Nominal ignored -> { }
        }
    }

    /**
     * A union no behavior answers, which only a body holds.
     *
     * <p>Its leaves are its members with each sum among them descended into, as the checker's are,
     * because a value of it is told apart by the leaf it is. No boundary writes one, so its form is
     * never read; it is given the one the checker gives a union, a bare tag where every leaf is a
     * unit, so that a descriptor never says something about a set the checker would not.
     */
    private int unsettled(Type.Union union) {
        List<TypeSymbol> leaves = leavesOf(union);
        boolean units = !leaves.isEmpty() && leaves.stream().allMatch(
                each -> each instanceof TypeSymbol.AtModule held
                        && declared(held) instanceof CheckedData.Unit);
        return alternatives(null, leaves, units ? new CheckedAlternativesForm.Enumeration() : null);
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
            case Type.Prim.RATIONAL -> rational();
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
            case Type.Union union -> {
                CheckedBoundaryOutput.Cases settled = answered.get(union);
                yield settled != null
                        ? alternatives(null, settled.cases(), settled.representation())
                        : unsettled(union);
            }
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
     * Where the set of ways to round is declared.
     *
     * <p>An address, compared against the one an identity already carries. An identity is not
     * built from it here: what a declaration is the identity of is for the checker to say.
     */
    private static final TypeKey ROUNDING_MODE = new TypeKey("souther.decimal", "RoundingMode");

    /** Whether {@code parameter} is the language's one way of naming how to round. */
    static boolean isRoundingMode(Type parameter) {
        return parameter instanceof Type.Ref ref && isRoundingMode(ref.name());
    }

    private static boolean isRoundingMode(TypeSymbol symbol) {
        return symbol instanceof TypeSymbol.AtModule named && named.key().equals(ROUNDING_MODE);
    }

    /**
     * The set of ways to round, which the language declares.
     *
     * <p>An operation that rounds is told which way as a place among these, because what it does
     * with it is pick one of that many ways — and a value of one of the cases is typed as that
     * case, so the set it belongs to is asked of the language rather than of the value.
     *
     * <p>Found once. The program answers what the language declares by going through everything
     * it holds, which is what listing them costs, and every operation that rounds asks this.
     */
    int roundingModes() {
        if (roundingModes == null) {
            roundingModes = program.languageDeclarations().stream()
                    .filter(each -> each instanceof CheckedData.Sum && isRoundingMode(each.name()))
                    .findFirst()
                    .map(held -> ofDeclared(held.name()))
                    .orElseThrow(() -> new NotLowered(
                            "the language declares no set of ways to round"));
        }
        return roundingModes;
    }

    /** Where the set of ways to round is described, once something has asked. */
    private Integer roundingModes;

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
            case TypeSymbol.Primitive scalar -> of(scalar.primitive());
            case TypeSymbol.LanguageCase given ->
                    languageCases.computeIfAbsent(given, this::unit);
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
                int descriptor = unit(name);
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
            case CheckedData.Sum choice ->
                    alternatives(name, choice.cases(), choice.representation());
        };
    }

    /**
     * A set of alternatives, in the form the set travels as.
     *
     * <p>Where every one of them carries nothing but which it is, the value written is the name
     * itself; where any carries something of its own, the name stands beside it under a key. That
     * is the language's rule about how a set of alternatives crosses, and every backend has to
     * read it the same way or one set is two documents — so the form is the checker's answer, read
     * here and never worked out again from the members.
     *
     * <p>Where the name stands beside what an alternative carries, the keys it stands under are
     * the checker's too ({@link CheckedAlternativesForm.Discriminated}): the tag's, and the one a
     * case that is not laid out as fields — a newtype, a primitive — keeps its own form under. The
     * descriptor carries both, so the runtime reading and writing the set spells neither.
     *
     * @param name the type the set is declared as, or null where nobody named the members together
     * @param members the leaves, a member that is itself a sum already descended into: a value of
     *     the set carries the descriptor of the leaf it is, and that is what it is told apart by
     * @param form how the set travels, or null for a set only a body holds, which never crosses
     *     and so has no keys to stand under
     */
    private int alternatives(TypeSymbol.AtModule name, List<TypeSymbol> members,
            CheckedAlternativesForm form) {
        List<int[]> described = new ArrayList<>();
        for (TypeSymbol member : members) {
            byte[] utf8 = member.name().getBytes(StandardCharsets.UTF_8);
            described.add(new int[] {fragment.intern(utf8), utf8.length, ofMember(member)});
        }
        if (form instanceof CheckedAlternativesForm.Enumeration) {
            return written(KIND_ENUMERATION, name, described);
        }
        int[] keys = {0, 0, 0, 0};
        if (form instanceof CheckedAlternativesForm.Discriminated(String tag, String contents)) {
            byte[] tagged = tag.getBytes(StandardCharsets.UTF_8);
            byte[] held = contents.getBytes(StandardCharsets.UTF_8);
            keys = new int[] {fragment.intern(tagged), tagged.length,
                    fragment.intern(held), held.length};
        }
        return written(KIND_SUM, name, described, keys);
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
        return placed(kind, table.toByteArray(), member);
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
        return placed(kind, table.toByteArray(), keys, values);
    }

    private int scalar(int kind) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        new WasmWriter(table).writeLittleEndian4(kind);
        return placed(kind, table.toByteArray());
    }

    /**
     * Places a descriptor settled as it is written, after the sixteen bytes that will name its
     * operations.
     */
    private int placed(int kind, byte[] descriptor, int... members) {
        int operations = fragment.reserve(OPERATIONS);
        int at = fragment.place(descriptor);
        described.put(at, new Described(kind, operations, at, members));
        return at;
    }

    /** How many bytes before a descriptor name its operations: a reader, a writer, an order. */
    private static final int OPERATIONS = 16;

    /**
     * A descriptor placed: its kind, where its operations go, and the descriptors of what it is made
     * of, which a composite learns after it is placed.
     */
    private static final class Described {
        final int kind;
        final int operations;
        int[] members;

        Described(int kind, int operations, int at, int[] members) {
            if (at != operations + OPERATIONS) {
                throw new IllegalStateException("a descriptor at " + at
                        + " is not where its operations end, " + (operations + OPERATIONS));
            }
            this.kind = kind;
            this.operations = operations;
            this.members = members;
        }
    }

    /**
     * A descriptor of a value that carries nothing, with its own name where a product's is: after
     * its fields, of which it has none.
     *
     * <p>Its name, because a value of it is a case of every set of alternatives that lists it, and
     * which case it is has to be readable off the value whichever set it is met as. A set the value
     * was not made as — a union narrower than the sum it is later held as — does not list it, and
     * a name found by its place in that set's descriptor would be another case's.
     */
    private int unit(TypeSymbol name) {
        byte[] own = name.name().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        new WasmWriter(table)
                .writeLittleEndian4(KIND_UNIT)
                .writeLittleEndian4(0)
                .writeLittleEndian4(fragment.intern(own))
                .writeLittleEndian4(own.length);
        return placed(KIND_UNIT, table.toByteArray());
    }

    /**
     * A descriptor of an exact quotient, with the slot of what orders two of them after its kind.
     *
     * <p>The order of every kind is reached from one runtime function, and the order of these is
     * exact arithmetic. Reached by a call, it would be in every module that compares anything; put
     * in a slot here, it is in the modules that hold one, which are the ones that write this.
     */
    private int rational() {
        int order = fragment.slot(fragment.plan().functionIndexOf(RuntimeAbi.RATIONAL_ORDER));
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        new WasmWriter(table).writeLittleEndian4(KIND_RATIONAL).writeLittleEndian4(order);
        return placed(KIND_RATIONAL, table.toByteArray());
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
            written.add(new int[] {fragment.intern(utf8), utf8.length, of(member.type())});
        }
        return filled(kind, name, descriptor, written);
    }

    /**
     * A set of alternatives whose members were described before the descriptor was reserved,
     * with {@code after} written after them: a sum's keys.
     */
    private int written(int kind, TypeSymbol.AtModule name, List<int[]> members, int... after) {
        int descriptor = reserveFor(kind, name, members.size(), after.length);
        return filled(kind, name, descriptor, members, after);
    }

    private int reserveFor(int kind, TypeSymbol.AtModule name, int members) {
        return reserveFor(kind, name, members, 0);
    }

    private int reserveFor(int kind, TypeSymbol.AtModule name, int members, int after) {
        // A form a value is built out of carries its own name and the slot of what checks it,
        // after its fields.
        int operations = fragment.reserve(OPERATIONS);
        int descriptor = fragment.reserve(4 + 4 + 12 * members + (carriesRules(kind) ? 16 : 0)
                + 4 * after);
        described.put(descriptor, new Described(kind, operations, descriptor, new int[0]));
        if (name != null) {
            byName.put(name, descriptor);
        }
        return descriptor;
    }

    private int filled(int kind, TypeSymbol.AtModule name, int descriptor, List<int[]> written,
            int... after) {
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
            out.writeLittleEndian4(fragment.intern(own))
                    .writeLittleEndian4(own.length)
                    .writeLittleEndian4(checks.applyAsInt(name))
                    .writeLittleEndian4(clauses.of(name, invariantsOf(name),
                            kind == KIND_NEWTYPE));
        }
        for (int word : after) {
            out.writeLittleEndian4(word);
        }
        fragment.fill(descriptor, table.toByteArray());
        described.get(descriptor).members = written.stream().mapToInt(member -> member[2]).toArray();
        if (kind == KIND_NEWTYPE && holdsNoValueTwice(invariantsOf(name))) {
            unique.put(descriptor, written.getFirst()[2]);
        }
        return descriptor;
    }

    /** Whether a clause of a newtype says the list it is a name for holds no value twice. */
    private static boolean holdsNoValueTwice(List<ValueShape.Invariant> invariants) {
        return invariants.stream().flatMap(each -> each.projection().constraints().stream())
                .anyMatch(each -> each instanceof souther.compiler.core.BoundaryConstraint.Unique);
    }

    /** Says the program reads values of {@code descriptor}'s type from a document. */
    int toRead(int descriptor) {
        reads.add(descriptor);
        return descriptor;
    }

    /** Says the program writes values of {@code descriptor}'s type into a document. */
    int toWrite(int descriptor) {
        writes.add(descriptor);
        return descriptor;
    }

    /** Says the program places values of {@code descriptor}'s type among others. */
    int toOrder(int descriptor) {
        orders.add(descriptor);
        return descriptor;
    }

    /**
     * Names in every descriptor what reads, writes and orders a value of its type, where the program
     * asks that, and places the program's descriptors of the primitives for the runtime.
     *
     * <p>What is asked of a type is asked of what it is made of: a list read is its elements read.
     * And some things are asked by others. A set and a map are held in the order of their members
     * and their keys, whatever the program asks of them. A newtype whose list holds no value twice
     * finds what it holds twice by ordering and writing it. And a value a model declared is ordered
     * as what it was made as, read off the value, which may be a case of a set of alternatives the
     * descriptor that reached it does not list: so where any is ordered, every case of every set of
     * alternatives is.
     *
     * <p>Called once, after the last descriptor is described.
     */
    void finish() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Integer, Described> each : described.entrySet()) {
                int at = each.getKey();
                Described held = each.getValue();
                if (held.kind == KIND_SET || held.kind == KIND_MAP) {
                    changed |= orders.add(held.members[0]);
                }
                if (reads.contains(at) && unique.containsKey(at)) {
                    int element = described.get(unique.get(at)).members[0];
                    changed |= orders.add(element) | writes.add(element);
                }
                for (Set<Integer> asked : List.of(reads, writes, orders)) {
                    if (asked.contains(at)) {
                        for (int member : held.members) {
                            changed |= asked.add(member);
                        }
                    }
                }
            }
            if (orders.stream().anyMatch(at -> declaresAValue(described.get(at).kind))) {
                for (Described held : described.values()) {
                    if (held.kind == KIND_SUM || held.kind == KIND_ENUMERATION) {
                        for (int member : held.members) {
                            changed |= orders.add(member);
                        }
                    }
                }
            }
        }
        for (Map.Entry<Integer, Described> each : described.entrySet()) {
            int at = each.getKey();
            int kind = each.getValue().kind;
            ByteArrayOutputStream operations = new ByteArrayOutputStream();
            new WasmWriter(operations)
                    .writeLittleEndian4(reads.contains(at) ? slotOf(readerOf(kind)) : 0)
                    .writeLittleEndian4(writes.contains(at) ? slotOf(writerOf(kind)) : 0)
                    .writeLittleEndian4(orders.contains(at) ? slotOf(ordererOf(kind)) : 0)
                    .writeLittleEndian4(0);
            fragment.fill(each.getValue().operations, operations.toByteArray());
        }
        primitives();
    }

    /** Whether a kind is of a value a model declared, which is ordered as what it was made as. */
    private static boolean declaresAValue(int kind) {
        return kind == KIND_UNIT || kind == KIND_PRODUCT || kind == KIND_SUM
                || kind == KIND_ENUMERATION;
    }

    /**
     * Places where the program's descriptor of each primitive is, in the order the runtime reads
     * them, for a value of one asked what it is: nothing where the program holds none, and no table
     * at all where it holds none of them.
     */
    private void primitives() {
        List<Type> order = List.of(Type.Prim.INT, Type.Prim.BOOL, Type.Prim.STRING,
                Type.Prim.DECIMAL, Type.Prim.RATIONAL, Type.Prim.DATE, Type.Prim.TIME,
                Type.Prim.DATETIME, Type.Prim.INSTANT);
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        boolean any = false;
        for (Type primitive : order) {
            Integer held = placed.get(primitive);
            any |= held != null;
            out.writeLittleEndian4(held == null ? 0 : held);
        }
        if (any) {
            fragment.primitives(fragment.place(table.toByteArray()));
        }
    }

    private int slotOf(String function) {
        return function == null ? 0 : fragment.slot(fragment.plan().functionIndexOf(function));
    }

    /** What reads a value of a kind, or nothing for a kind nothing reads. */
    private static String readerOf(int kind) {
        return switch (kind) {
            case KIND_INT -> RuntimeAbi.Operations.READ_INT;
            case KIND_BOOL -> RuntimeAbi.Operations.READ_BOOL;
            case KIND_STRING -> RuntimeAbi.Operations.READ_STRING;
            case KIND_DECIMAL -> RuntimeAbi.Operations.READ_DECIMAL;
            case KIND_DATE, KIND_TIME, KIND_DATE_TIME, KIND_INSTANT ->
                    RuntimeAbi.Operations.READ_TEMPORAL;
            case KIND_UNIT -> RuntimeAbi.Operations.READ_UNIT;
            case KIND_PRODUCT -> RuntimeAbi.Operations.READ_PRODUCT;
            case KIND_NEWTYPE -> RuntimeAbi.Operations.READ_NEWTYPE;
            case KIND_SUM -> RuntimeAbi.Operations.READ_SUM;
            case KIND_ENUMERATION -> RuntimeAbi.Operations.READ_ENUMERATION;
            case KIND_LIST -> RuntimeAbi.Operations.READ_LIST;
            case KIND_SET -> RuntimeAbi.Operations.READ_SET;
            case KIND_MAP -> RuntimeAbi.Operations.READ_MAP;
            case KIND_OPTION -> RuntimeAbi.Operations.READ_OPTION;
            default -> null;
        };
    }

    /** What writes a value of a kind, or nothing for a kind nothing writes. */
    private static String writerOf(int kind) {
        return switch (kind) {
            case KIND_INT -> RuntimeAbi.Operations.WRITE_INT;
            case KIND_BOOL -> RuntimeAbi.Operations.WRITE_BOOL;
            case KIND_STRING -> RuntimeAbi.Operations.WRITE_STRING;
            case KIND_DECIMAL -> RuntimeAbi.Operations.WRITE_DECIMAL;
            case KIND_DATE, KIND_TIME, KIND_DATE_TIME, KIND_INSTANT ->
                    RuntimeAbi.Operations.WRITE_TEMPORAL;
            case KIND_UNIT -> RuntimeAbi.Operations.WRITE_UNIT;
            case KIND_PRODUCT -> RuntimeAbi.Operations.WRITE_PRODUCT;
            case KIND_NEWTYPE -> RuntimeAbi.Operations.WRITE_NEWTYPE;
            case KIND_SUM -> RuntimeAbi.Operations.WRITE_SUM;
            case KIND_ENUMERATION -> RuntimeAbi.Operations.WRITE_ENUMERATION;
            case KIND_LIST -> RuntimeAbi.Operations.WRITE_LIST;
            case KIND_SET -> RuntimeAbi.Operations.WRITE_SET;
            case KIND_MAP -> RuntimeAbi.Operations.WRITE_MAP;
            case KIND_OPTION -> RuntimeAbi.Operations.WRITE_OPTION;
            default -> null;
        };
    }

    /** What orders two values of a kind, or nothing for a kind no value is of. */
    private static String ordererOf(int kind) {
        return switch (kind) {
            case KIND_INT -> RuntimeAbi.Operations.ORDER_INT;
            case KIND_BOOL -> RuntimeAbi.Operations.ORDER_BOOL;
            case KIND_STRING -> RuntimeAbi.Operations.ORDER_STRING;
            case KIND_DECIMAL -> RuntimeAbi.Operations.ORDER_DECIMAL;
            case KIND_RATIONAL -> RuntimeAbi.Operations.ORDER_RATIONAL;
            case KIND_DATE, KIND_TIME, KIND_DATE_TIME -> RuntimeAbi.Operations.ORDER_TEMPORAL;
            case KIND_INSTANT -> RuntimeAbi.Operations.ORDER_INSTANT;
            case KIND_UNIT, KIND_PRODUCT, KIND_SUM, KIND_ENUMERATION ->
                    RuntimeAbi.Operations.ORDER_DECLARED;
            case KIND_NEWTYPE -> RuntimeAbi.Operations.ORDER_NEWTYPE;
            case KIND_TUPLE -> RuntimeAbi.Operations.ORDER_TUPLE;
            case KIND_LIST, KIND_SET -> RuntimeAbi.Operations.ORDER_LIST;
            case KIND_MAP -> RuntimeAbi.Operations.ORDER_MAP;
            case KIND_OPTION -> RuntimeAbi.Operations.ORDER_OPTION;
            default -> null;
        };
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
    /** Where each pattern's machine is placed, which a body's {@code String.matches} reads too. */
    Patterns patterns() {
        return patterns;
    }

    /**
     * What a newtype is a name for, or nothing where the type is no newtype. A value of one is
     * laid out as the one field it is made of, so what it is a name for is that field's.
     */
    Optional<Type> wrappedBy(Type type) {
        if (type instanceof Type.Ref reference
                && reference.name() instanceof TypeSymbol.AtModule named
                && declared(named) instanceof CheckedData.Newtype newtype) {
            return Optional.of(newtype.fields().getFirst().type());
        }
        return Optional.empty();
    }

    /** What a type is made of under every newtype it is, which is the type itself for none. */
    Type madeOf(Type type) {
        Type held = type;
        for (var within = wrappedBy(held); within.isPresent(); within = wrappedBy(held)) {
            held = within.get();
        }
        return held;
    }

    /**
     * The leaves of a set of alternatives — a sum's cases, a union's members with each sum among
     * them descended into, as the checker's are — or nothing for a type that is no such set.
     */
    List<TypeSymbol> leavesOf(Type type) {
        List<TypeSymbol> members = switch (type) {
            case Type.Union union -> List.copyOf(union.members());
            case Type.Ref reference when reference.name() instanceof TypeSymbol.AtModule named
                    && declared(named) instanceof CheckedData.Sum -> List.of(named);
            default -> List.of();
        };
        List<TypeSymbol> leaves = new ArrayList<>();
        for (TypeSymbol member : members) {
            List<TypeSymbol> under = member instanceof TypeSymbol.AtModule named
                    && declared(named) instanceof CheckedData.Sum sum ? sum.cases() : List.of(member);
            for (TypeSymbol leaf : under) {
                if (!leaves.contains(leaf)) {
                    leaves.add(leaf);
                }
            }
        }
        return leaves;
    }

    /**
     * How many newtypes a value held as {@code held} wears, which is how many it is opened through
     * to be {@link #madeOf what it is made of}.
     *
     * <p>Whether a value is opened at all is not this answer. A comparison's reading says it
     * ({@link Core.BinaryReading}): a {@code Code} beside the {@code Key} listing it is read in the
     * cases as it stands, and opened it would be an {@code Int} the {@code Key} has no case for.
     */
    int namesWornBy(Type held) {
        int layers = 0;
        for (var within = wrappedBy(held); within.isPresent(); within = wrappedBy(within.get())) {
            layers++;
        }
        return layers;
    }

    /**
     * What values held as {@code held} are placed as on the order the checker settled for them
     * ({@link Core.OrderingBasis}): the basis, under every newtype it is. With no basis there was
     * no value to order, and they are placed as what they are.
     */
    Type orderedAs(Type held, Optional<Core.OrderingBasis> ordering) {
        return madeOf(ordering.map(Core.OrderingBasis::type).orElse(held));
    }

    /**
     * The descriptor of the order the checker settled for values of {@code held}, for values
     * opened to it ({@link #namesWornBy}).
     *
     * <p>How a value is held and what orders it are two answers, and this is only the second.
     * A newtype over a case — {@code data BetaN = Beta}, {@code Beta} one case of {@code Rising} —
     * is opened to a {@code Beta}, which has no order of its own, and placed by {@code Rising}; a
     * union of cases is placed by the sum listing them; a newtype over a number by the number.
     */
    int orderOf(Type held, Optional<Core.OrderingBasis> ordering) {
        return of(orderedAs(held, ordering));
    }

    private CheckedData declared(TypeSymbol.AtModule name) {
        return program.declaration(name).data();
    }

    private CheckedData.WithFields product(TypeSymbol.AtModule name) {
        if (declared(name) instanceof CheckedData.WithFields found) {
            return found;
        }
        throw new NotLowered(name + " is not written as fields, and a field is read off one that is");
    }
}
