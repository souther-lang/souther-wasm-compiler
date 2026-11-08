package souther.wasm.lower;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import souther.compiler.program.CheckedAlternativesForm;
import souther.compiler.program.CheckedBehavior;
import souther.compiler.program.CheckedBoundaryInput;
import souther.compiler.program.CheckedBoundaryOutput;
import souther.compiler.program.CheckedCodecShape;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedModule;
import souther.compiler.program.CheckedProgram;
import souther.compiler.program.CheckedSignature;
import souther.compiler.types.LeafScalar;
import souther.compiler.types.MapKeyRepresentation;
import souther.compiler.types.Type;
import souther.compiler.types.TypeSymbol;
import souther.wasm.link.Component;
import souther.wasm.link.TypedOffering;
import souther.wasm.link.WitType;

/**
 * What a program offers, typed: each value as the WIT type it crosses as, read off the checked
 * program as {@link Surface} reads what it writes, and decided nowhere else.
 *
 * <p>Read off the program and not off the descriptors a module is built with. Those are where a
 * value's type is placed in memory for the runtime to read by, made as the module is written; what
 * a program offers is the program's, the same whether a module is written or only its interfaces
 * are.
 *
 * <p>A Souther value is held to more than its WIT type says. A newtype's rules, a set's distinct
 * elements and the text of an amount or a date are checked as they are of a value read from JSON,
 * by the same reading, so what WIT says is the shape and Souther says the rest.
 *
 * <ul>
 *   <li>a product is a {@code record}, a newtype a {@code type} naming what it wraps, and a unit an
 *       {@code enum} of itself alone, where it stands as a value;</li>
 *   <li>a sum that travels as an enumeration is an {@code enum}, and one that travels discriminated
 *       a {@code variant} whose case is named after its type and carries it, a unit carrying
 *       nothing;</li>
 *   <li>a union a behavior answers that nobody named is declared as the behavior's
 *       {@code <behavior>-result}, as the JVM names it {@code <Behavior>Result};</li>
 *   <li>a list and a set are a {@code list}, an optional field an {@code option}, and a map a
 *       {@code list} of {@code tuple}s of a key and a value, since a WIT {@code map} takes no
 *       enumeration as its key and is not yet where every runtime is;</li>
 *   <li>an {@code Int} is an {@code s64}, which is what it is; a {@code Decimal} and the four
 *       temporals are a {@code string}, written as the JSON boundary writes them, since WIT has no
 *       exact decimal and no local date.</li>
 * </ul>
 */
final class WitTypes {

    private final CheckedProgram program;
    private final Map<TypeSymbol.AtModule, WitType.Named> named = new LinkedHashMap<>();
    private final Deque<TypeSymbol.AtModule> pending = new ArrayDeque<>();
    private final Map<String, Map<String, WitType.Declaration>> declared = new LinkedHashMap<>();
    private final Map<WitType.Named, String> owners = new LinkedHashMap<>();
    /** Each union a behavior answers that nobody named, by the name it is declared under. */
    private final Map<Type.Union, WitType.Named> unions = new LinkedHashMap<>();
    private @Nullable TypedOffering offering;

    private WitTypes(CheckedProgram program) {
        this.program = program;
    }

    /** The typed offering of {@code program}. */
    static TypedOffering of(CheckedProgram program) {
        return read(program).offering();
    }

    /** The typed offering of {@code program}, and what each type it holds crosses as. */
    static WitTypes read(CheckedProgram program) {
        WitTypes read = new WitTypes(program);
        read.offering = read.offered();
        return read;
    }

    /** What the program offers, typed. */
    TypedOffering offering() {
        return java.util.Objects.requireNonNull(offering);
    }

    /**
     * What a value of a type crosses as, for a type the offering names: what a descriptor of it is
     * laid out by where it crosses typed.
     */
    WitType typeOf(Type type) {
        return switch (type) {
            case Type.Prim primitive -> {
                LeafScalar scalar = LeafScalar.of(primitive);
                if (scalar == null) {
                    throw new IllegalStateException(primitive + " crosses no typed boundary");
                }
                yield scalar(scalar);
            }
            case Type.Ref reference when reference.name() instanceof TypeSymbol.AtModule declared -> {
                WitType.Named name = named.get(declared);
                if (name == null) {
                    throw new IllegalStateException(declared + " crosses typed, and the typed"
                            + " interfaces declare nothing for it");
                }
                yield name;
            }
            case Type.ListOf list -> new WitType.ListOf(typeOf(list.element()));
            case Type.SetOf set -> new WitType.ListOf(typeOf(set.element()));
            case Type.OptionOf option -> new WitType.OptionOf(typeOf(option.element()));
            // A key that is a primitive is a string or a temporal, written as text.
            case Type.MapOf map -> mapOf(map.key() instanceof Type.Prim ? WitType.STRING
                    : typeOf(map.key()), typeOf(map.value()));
            case Type.Union union -> {
                WitType.Named name = unions.get(union);
                if (name == null) {
                    throw new IllegalStateException(union + " crosses typed, and no behavior"
                            + " answers it");
                }
                yield name;
            }
            default -> throw new IllegalStateException(type + " crosses no typed boundary");
        };
    }

    private TypedOffering offered() {
        Map<String, Map<String, TypedOffering.Function>> behaviors = new LinkedHashMap<>();
        for (CheckedModule module : program.modules()) {
            Map<String, TypedOffering.Function> functions = new LinkedHashMap<>();
            for (CheckedBehavior behavior : module.behaviors()) {
                if (WasmCompiler.publishes(module, behavior)) {
                    functions.put(behavior.name().name(), function(behavior));
                }
            }
            if (!functions.isEmpty()) {
                behaviors.put(module.name(), functions);
            }
        }

        Map<String, Map<String, TypedOffering.Function>> readable = new LinkedHashMap<>();
        for (TypeSymbol.AtModule type : WasmCompiler.decodable(program)) {
            WitType value = symbol(type);
            readable.computeIfAbsent(type.module(), held -> new LinkedHashMap<>())
                    .put(type.name(), new TypedOffering.Function(
                            "check-" + Component.interfaceName(type.name()),
                            List.of(new WitType.Field("value", value)), value));
        }

        Map<String, Map<String, TypedOffering.Function>> reached = new LinkedHashMap<>();
        for (CheckedBehavior behavior : WasmCompiler.reachingOut(program)) {
            reached.computeIfAbsent(behavior.name().module(), held -> new LinkedHashMap<>())
                    .put(behavior.name().name(), function(behavior));
        }

        // Each declaration may name another, so the declarations are closed by declaring until
        // nothing new is named.
        while (!pending.isEmpty()) {
            TypeSymbol.AtModule name = pending.poll();
            declare(name.module(), declaration(name));
        }

        Map<String, List<WitType.Declaration>> types = new LinkedHashMap<>();
        declared.forEach((module, byName) -> types.put(module, List.copyOf(byName.values())));
        return new TypedOffering(types, behaviors, readable, reached);
    }

    private TypedOffering.Function function(CheckedBehavior behavior) {
        CheckedSignature signature = behavior.signature();
        List<CheckedBoundaryInput> inputs = signature.inputs();
        // A composition declares no names for what it takes, so each is named for its place.
        List<String> names = signature.declaredParameters()
                .map(declared -> declared.stream()
                        .map(parameter -> Component.interfaceName(parameter.name()))
                        .toList())
                .orElse(null);
        List<WitType.Field> parameters = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            parameters.add(new WitType.Field(
                    names == null ? "argument" + i : names.get(i), input(inputs.get(i))));
        }
        String crossing = Component.interfaceName(behavior.name().name());
        return new TypedOffering.Function(crossing, parameters,
                output(signature.output(), behavior.name().module(), crossing));
    }

    private WitType input(CheckedBoundaryInput input) {
        return switch (input) {
            case CheckedBoundaryInput.Scalar scalar -> scalar(scalar.scalar());
            case CheckedBoundaryInput.Nominal nominal -> symbol(nominal.name());
            case CheckedBoundaryInput.ListOf list -> new WitType.ListOf(input(list.element()));
            case CheckedBoundaryInput.SetOf set -> new WitType.ListOf(input(set.element()));
            case CheckedBoundaryInput.MapOf map -> mapOf(key(map.key()), input(map.value()));
        };
    }

    /**
     * What a behavior answers. A union nobody named is declared under the behavior's name in its
     * module's types interface, as its cases cross: the leaves its members descend to.
     */
    private WitType output(CheckedBoundaryOutput output, String module, String behavior) {
        return switch (output) {
            case CheckedBoundaryOutput.Scalar scalar -> scalar(scalar.scalar());
            case CheckedBoundaryOutput.Nominal nominal -> symbol(nominal.name());
            case CheckedBoundaryOutput.ListOf list ->
                    new WitType.ListOf(output(list.element(), module, behavior));
            case CheckedBoundaryOutput.SetOf set ->
                    new WitType.ListOf(output(set.element(), module, behavior));
            case CheckedBoundaryOutput.MapOf map ->
                    mapOf(key(map.key()), output(map.value(), module, behavior));
            case CheckedBoundaryOutput.Cases cases -> {
                WitType.Named name = new WitType.Named(module, behavior + "-result");
                unions.putIfAbsent(cases.type(), name);
                // A behavior offered and reached out for is one behavior, and declares its answer
                // once.
                if (!owners.containsKey(name)) {
                    claim(name, "the answer of " + module + "." + behavior);
                    declare(module, alternatives(
                            name.name(), cases.cases(), cases.representation()));
                }
                yield name;
            }
        };
    }

    private WitType codec(CheckedCodecShape shape) {
        return switch (shape) {
            case CheckedCodecShape.Scalar scalar -> scalar(scalar.kind());
            case CheckedCodecShape.Named name -> symbol(name.name());
            case CheckedCodecShape.ListOf list -> new WitType.ListOf(codec(list.element()));
            case CheckedCodecShape.SetOf set -> new WitType.ListOf(codec(set.element()));
            case CheckedCodecShape.MapOf map -> mapOf(key(map.key()), codec(map.value()));
            case CheckedCodecShape.OptionOf option -> new WitType.OptionOf(codec(option.present()));
        };
    }

    /** A key as its text where it is a primitive, which is a string or a temporal written as one. */
    private WitType key(MapKeyRepresentation key) {
        return switch (key) {
            case MapKeyRepresentation.Lexical lexical -> scalar(lexical.leaf());
            case MapKeyRepresentation.NamedKey name -> symbol(name.name());
        };
    }

    private static WitType mapOf(WitType key, WitType value) {
        return new WitType.ListOf(new WitType.TupleOf(List.of(key, value)));
    }

    private WitType.Declaration declaration(TypeSymbol.AtModule name) {
        String called = Component.interfaceName(name.name());
        return switch (program.declaration(name).data()) {
            case CheckedData.Product product -> {
                List<WitType.Field> fields = new ArrayList<>();
                for (int i = 0; i < product.fields().size(); i++) {
                    fields.add(new WitType.Field(
                            Component.interfaceName(product.fields().get(i).name()),
                            codec(product.codecShapes().get(i))));
                }
                yield new WitType.Record(called, fields);
            }
            case CheckedData.Newtype newtype ->
                    new WitType.Alias(called, codec(newtype.codecShapes().getFirst()));
            // A unit standing as a value is the one value of its type, which an enum of one case
            // is; as a case of a variant it is nothing, and is not declared for that.
            case CheckedData.Unit ignored -> new WitType.Enumeration(called, List.of(called));
            case CheckedData.Sum sum -> alternatives(called, sum.cases(), sum.representation());
        };
    }

    private WitType.Declaration alternatives(String name, List<TypeSymbol> cases,
            CheckedAlternativesForm form) {
        return switch (form) {
            case CheckedAlternativesForm.Enumeration ignored ->
                    new WitType.Enumeration(name, cases.stream().map(WitTypes::caseName).toList());
            case CheckedAlternativesForm.Discriminated ignored -> new WitType.Variant(name,
                    cases.stream().map(each -> new WitType.Case(caseName(each), carried(each)))
                            .toList());
        };
    }

    /** What a case of a variant carries: its value, or nothing for a unit. */
    private @Nullable WitType carried(TypeSymbol symbol) {
        if (symbol instanceof TypeSymbol.AtModule declared
                && program.declaration(declared).data() instanceof CheckedData.Unit) {
            return null;
        }
        return symbol(symbol);
    }

    /** What a case is named: its type's name, a primitive's as the language writes it. */
    private static String caseName(TypeSymbol symbol) {
        return switch (symbol) {
            case TypeSymbol.AtModule declared -> Component.interfaceName(declared.name());
            case TypeSymbol.Primitive primitive -> primitive.name().toLowerCase(Locale.ROOT);
            case TypeSymbol.LanguageCase given -> throw new IllegalStateException(
                    given.name() + " stands at a boundary, which the checker refuses");
        };
    }

    /** A type named by its identity, which names its declaration into its module's interface. */
    private WitType symbol(TypeSymbol symbol) {
        return switch (symbol) {
            case TypeSymbol.AtModule declared -> {
                WitType.Named name = named.get(declared);
                if (name == null) {
                    name = new WitType.Named(declared.module(),
                            Component.interfaceName(declared.name()));
                    claim(name, declared.module() + "." + declared.name());
                    named.put(declared, name);
                    pending.add(declared);
                }
                yield name;
            }
            case TypeSymbol.Primitive primitive -> {
                LeafScalar scalar = LeafScalar.of(primitive.primitive());
                if (scalar == null) {
                    throw new NotLowered("a " + primitive.name()
                            + " at a boundary, which no value crosses as");
                }
                yield scalar(scalar);
            }
            case TypeSymbol.LanguageCase given -> throw new IllegalStateException(
                    given.name() + " stands at a boundary, which the checker refuses");
        };
    }

    /**
     * Takes a name of a module's types interface for one thing, refusing a second: two Souther
     * names that come to one WIT name, or a behavior's answer named as a type of its module is.
     */
    private void claim(WitType.Named name, String owner) {
        String taken = owners.putIfAbsent(name, owner);
        if (taken != null) {
            throw new IllegalArgumentException(taken + " and " + owner + " would both be the type "
                    + name.name() + " of the typed interface of " + name.module());
        }
    }

    private void declare(String module, WitType.Declaration declaration) {
        declared.computeIfAbsent(module, held -> new LinkedHashMap<>())
                .put(declaration.name(), declaration);
    }

    private static WitType scalar(LeafScalar scalar) {
        return switch (scalar) {
            case STRING, DECIMAL, DATE, TIME, DATETIME, INSTANT -> WitType.STRING;
            case INT -> WitType.S64;
            case BOOL -> WitType.BOOL;
        };
    }
}
