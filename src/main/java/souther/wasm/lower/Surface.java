package souther.wasm.lower;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import souther.compiler.core.ValueShape;
import souther.compiler.program.CheckedAlternativesForm;
import souther.compiler.program.CheckedBehavior;
import souther.compiler.program.CheckedBoundaryInput;
import souther.compiler.program.CheckedBoundaryOutput;
import souther.compiler.program.CheckedCodecShape;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedImplementation;
import souther.compiler.program.CheckedModule;
import souther.compiler.program.CheckedProgram;
import souther.compiler.program.CheckedSignature;
import souther.compiler.program.Declared;
import souther.compiler.program.DeclaredBy;
import souther.compiler.program.Publication;
import souther.compiler.types.LeafScalar;
import souther.compiler.types.MapKeyRepresentation;
import souther.compiler.types.TypeSymbol;
import souther.compiler.types.ValueName;

/**
 * What a program offers a caller, written as the JSON the module carries under
 * {@code souther:surface}.
 *
 * <p>A caller writing code against a module needs what it can call, what each behavior takes and
 * answers, and what a value of each type looks like as JSON: the fields of a shape, the cases of a
 * sum and the form they travel in, and the rules a value is held to, by name. That is what this
 * writes, read off the checked program and decided nowhere here. What a rule says is not written:
 * a caller is told a value broke one, by the place the module reports, and checking it again in
 * the caller's language would be the rule written twice.
 *
 * <p>Closed over what it names. Every declaration a behavior, a field, a case or a map key names is
 * among {@code declarations}, a dependency's and the language's included, so a reader writing a
 * type for each never meets a name it was told nothing of. Every declaration a module of the
 * program makes is there too, whether or not a behavior names it, because a caller may want to read
 * a value of one on its own.
 *
 * <p>Being here is not being offered. A declaration its module keeps is here with
 * {@code "published":false}, as a fact about what the module declares; what a caller may build or
 * read on its own is what a module publishes, and anything that hands a caller a way to make a
 * value of a type reads that rather than taking this list as the answer.
 *
 * <p>Which those are is said here as the module was built to offer them: a declaration a caller may
 * read a value of on its own carries {@code "decode"}, the number {@link WasmCompiler#DECODE} reads
 * it under, and no other does. The number is this module's: a caller looks it up here when it loads
 * the module, by the declaration's module and name, and does not carry it to another module.
 *
 * <p>{@link #VERSION} moves when what this says is read differently.
 */
final class Surface {

    /**
     * Which version of the surface this writes.
     *
     * <p>Version 1 said each module's behaviors and the declarations they name. Version 2 says, of
     * a declaration a caller may read a value of on its own, the number it is read under
     * ({@code "decode"}). Version 3 says, of a behavior the program reaches out for, the number a
     * call out carries for it ({@code "reachOut"}), which a section of its own used to say beside
     * the surface. What each version says is held to a file named for it by
     * {@code TheSurfaceChangesOnlyWithItsVersionTest}, so what it says cannot change while this
     * stays where it is.
     */
    static final int VERSION = 3;

    private final CheckedProgram program;
    private final Map<TypeSymbol.AtModule, Integer> decodable = new LinkedHashMap<>();
    private final Map<ValueName.Behavior, Integer> reachOut;
    private final Map<TypeSymbol.AtModule, Declared> named = new LinkedHashMap<>();
    private final Deque<TypeSymbol.AtModule> pending = new ArrayDeque<>();

    private Surface(CheckedProgram program, List<TypeSymbol.AtModule> decodable,
            Map<ValueName.Behavior, Integer> reachOut) {
        this.program = program;
        for (int i = 0; i < decodable.size(); i++) {
            this.decodable.put(decodable.get(i), i);
        }
        this.reachOut = Map.copyOf(reachOut);
    }

    /**
     * The surface of {@code program}, as JSON.
     *
     * @param decodable the types a caller may read a value of on its own, in the order their
     *     numbers run, as the module was built with them
     * @param reachOut the number a call out carries for each behavior the program reaches out for,
     *     as the module was built with them
     */
    static String of(CheckedProgram program, List<TypeSymbol.AtModule> decodable,
            Map<ValueName.Behavior, Integer> reachOut) {
        return new Surface(program, decodable, reachOut).written();
    }

    private String written() {
        StringJoiner modules = new StringJoiner(",", "[", "]");
        for (CheckedModule module : program.modules()) {
            for (CheckedData data : module.data()) {
                names(data.name());
            }
        }
        for (CheckedModule module : program.modules()) {
            StringJoiner behaviors = new StringJoiner(",", "[", "]");
            for (CheckedBehavior behavior : module.behaviors()) {
                behaviors.add(behavior(module, behavior));
            }
            modules.add("{\"name\":" + quoted(module.name()) + ",\"behaviors\":" + behaviors + "}");
        }
        // Each declaration written may name another, so the list is closed by writing until
        // nothing new is named.
        StringJoiner declarations = new StringJoiner(",", "[", "]");
        while (!pending.isEmpty()) {
            TypeSymbol.AtModule name = pending.poll();
            declarations.add(declaration(name, named.get(name)));
        }
        return "{\"version\":" + VERSION + ",\"modules\":" + modules
                + ",\"declarations\":" + declarations + "}";
    }

    private String behavior(CheckedModule module, CheckedBehavior behavior) {
        CheckedSignature signature = behavior.signature();
        List<CheckedBoundaryInput> inputs = signature.inputs();
        // A composition declares no names for what it takes, which is not the same as declaring
        // none, so each of its parameters is written with a null name.
        List<String> names = signature.declaredParameters()
                .map(declared -> declared.stream().map(CheckedSignature.Parameter::name).toList())
                .orElse(null);
        StringJoiner parameters = new StringJoiner(",", "[", "]");
        for (int i = 0; i < inputs.size(); i++) {
            parameters.add("{\"name\":" + (names == null ? "null" : quoted(names.get(i)))
                    + ",\"type\":" + input(inputs.get(i)) + "}");
        }
        return "{\"name\":" + quoted(behavior.name().name())
                + ",\"export\":" + quoted(WasmCompiler.exportName(behavior.name()))
                + ",\"published\":" + (module.publicationOf(behavior.name()) == Publication.PUBLISHED)
                + ",\"implementation\":" + quoted(implementation(behavior.implementation()))
                // Only where a call is made out of the module, which is where a number is carried.
                + (reachOut.containsKey(behavior.name())
                        ? ",\"reachOut\":" + reachOut.get(behavior.name()) : "")
                + ",\"parameters\":" + parameters
                + ",\"answers\":" + output(signature.output()) + "}";
    }

    /** Who answers a call: the program, whoever loads it, nobody yet, or another build. */
    private static String implementation(CheckedImplementation implementation) {
        return switch (implementation) {
            case CheckedImplementation.Body ignored -> "here";
            case CheckedImplementation.Composed ignored -> "here";
            case CheckedImplementation.Injected ignored -> "injected";
            case CheckedImplementation.Unwritten ignored -> "unwritten";
            case CheckedImplementation.ImplementedElsewhere ignored -> "elsewhere";
        };
    }

    private String declaration(TypeSymbol.AtModule name, Declared declared) {
        String identity = "{\"module\":" + quoted(name.module()) + ",\"name\":" + quoted(name.name())
                + ",\"by\":" + quoted(by(declared.declaredBy()))
                + ",\"published\":" + published(name, declared.declaredBy())
                + (decodable.containsKey(name) ? ",\"decode\":" + decodable.get(name) : "");
        return switch (declared.data()) {
            case CheckedData.Product product -> {
                StringJoiner fields = new StringJoiner(",", "[", "]");
                for (int i = 0; i < product.fields().size(); i++) {
                    fields.add("{\"name\":" + quoted(product.fields().get(i).name())
                            + ",\"type\":" + codec(product.codecShapes().get(i)) + "}");
                }
                yield identity + ",\"is\":\"product\",\"fields\":" + fields
                        + ",\"rules\":" + rules(product) + "}";
            }
            case CheckedData.Newtype newtype -> identity + ",\"is\":\"newtype\",\"wraps\":"
                    + codec(newtype.codecShapes().getFirst()) + ",\"rules\":" + rules(newtype) + "}";
            case CheckedData.Unit ignored -> identity + ",\"is\":\"unit\"}";
            case CheckedData.Sum sum -> identity + ",\"is\":\"sum\",\"cases\":"
                    + symbols(sum.cases()) + ",\"form\":" + form(sum.representation()) + "}";
        };
    }

    /**
     * Whether the module declaring it publishes it, for a declaration of this program's own
     * modules. Null for a dependency's or the language's: what another build publishes is that
     * build's answer, and this one was not asked.
     */
    private String published(TypeSymbol.AtModule name, DeclaredBy by) {
        if (by != DeclaredBy.A_MODULE) {
            return "null";
        }
        return String.valueOf(program.module(name.module()).publicationOf(name)
                == Publication.PUBLISHED);
    }

    private static String by(DeclaredBy who) {
        return switch (who) {
            case A_MODULE -> "module";
            case A_MODULE_ON_THE_PATH -> "path";
            case THE_LANGUAGE -> "language";
        };
    }

    /** The rules a value is held to, in the order a failure is decided in, each by its name. */
    private static String rules(CheckedData.WithFields data) {
        StringJoiner rules = new StringJoiner(",", "[", "]");
        for (ValueShape.Invariant rule : data.invariants()) {
            rules.add("{\"name\":" + rule.name().map(Surface::quoted).orElse("null") + "}");
        }
        return rules.toString();
    }

    private String input(CheckedBoundaryInput input) {
        return switch (input) {
            case CheckedBoundaryInput.Scalar scalar -> scalar(scalar.scalar());
            case CheckedBoundaryInput.Nominal nominal -> symbol(nominal.name());
            case CheckedBoundaryInput.ListOf list -> "{\"is\":\"list\",\"of\":" + input(list.element()) + "}";
            case CheckedBoundaryInput.SetOf set -> "{\"is\":\"set\",\"of\":" + input(set.element()) + "}";
            case CheckedBoundaryInput.MapOf map -> "{\"is\":\"map\",\"key\":" + key(map.key())
                    + ",\"value\":" + input(map.value()) + "}";
        };
    }

    /**
     * What a behavior answers, as the model says it.
     *
     * <p>A union nobody named is two answers, and both are written. Its members are the union as it
     * was written, {@code Signal | Missing}; how a value of it crosses is the leaves those descend to,
     * {@code Red}, {@code Green} and {@code Missing}, and the form they travel in. Neither is
     * recovered from the other: the leaves alone are a union nobody wrote, and the members alone
     * leave a reader to descend and to decide the form again.
     */
    private String output(CheckedBoundaryOutput output) {
        return switch (output) {
            case CheckedBoundaryOutput.Scalar scalar -> scalar(scalar.scalar());
            case CheckedBoundaryOutput.Nominal nominal -> symbol(nominal.name());
            case CheckedBoundaryOutput.ListOf list -> "{\"is\":\"list\",\"of\":" + output(list.element()) + "}";
            case CheckedBoundaryOutput.SetOf set -> "{\"is\":\"set\",\"of\":" + output(set.element()) + "}";
            case CheckedBoundaryOutput.MapOf map -> "{\"is\":\"map\",\"key\":" + key(map.key())
                    + ",\"value\":" + output(map.value()) + "}";
            case CheckedBoundaryOutput.Cases cases -> "{\"is\":\"union\",\"members\":"
                    + symbols(List.copyOf(cases.type().members()))
                    + ",\"crossing\":{\"cases\":" + symbols(cases.cases())
                    + ",\"form\":" + form(cases.representation()) + "}}";
        };
    }

    /**
     * What a field holds. An optional is where absence is written, and where it stands says how: a
     * field omits its key, and an element or a map's value writes null.
     */
    private String codec(CheckedCodecShape shape) {
        return switch (shape) {
            case CheckedCodecShape.Scalar scalar -> scalar(scalar.kind());
            case CheckedCodecShape.Named named -> symbol(named.name());
            case CheckedCodecShape.ListOf list -> "{\"is\":\"list\",\"of\":" + codec(list.element()) + "}";
            case CheckedCodecShape.SetOf set -> "{\"is\":\"set\",\"of\":" + codec(set.element()) + "}";
            case CheckedCodecShape.MapOf map -> "{\"is\":\"map\",\"key\":" + key(map.key())
                    + ",\"value\":" + codec(map.value()) + "}";
            case CheckedCodecShape.OptionOf option -> "{\"is\":\"option\",\"of\":"
                    + codec(option.present()) + "}";
        };
    }

    private String key(MapKeyRepresentation key) {
        return switch (key) {
            case MapKeyRepresentation.Lexical lexical -> scalar(lexical.leaf());
            case MapKeyRepresentation.NamedKey named -> symbol(named.name());
        };
    }

    private String symbols(List<TypeSymbol> symbols) {
        StringJoiner written = new StringJoiner(",", "[", "]");
        symbols.forEach(each -> written.add(symbol(each)));
        return written.toString();
    }

    /** A type named by its identity, which names its declaration onto the surface. */
    private String symbol(TypeSymbol symbol) {
        return switch (symbol) {
            case TypeSymbol.AtModule declared -> {
                names(declared);
                yield "{\"is\":\"declared\",\"module\":" + quoted(declared.module())
                        + ",\"name\":" + quoted(declared.name()) + "}";
            }
            case TypeSymbol.Primitive primitive -> {
                LeafScalar scalar = LeafScalar.of(primitive.primitive());
                if (scalar == null) {
                    throw new NotLowered("a " + primitive.name()
                            + " at a boundary, which no value crosses as");
                }
                yield scalar(scalar);
            }
            // What the language declares does not cross: the checker refuses a model whose boundary
            // names one (E1325), so what a model publishes stays its own.
            case TypeSymbol.LanguageCase given -> throw new IllegalStateException(
                    given.name() + " stands at a boundary, which the checker refuses");
        };
    }

    private void names(TypeSymbol.AtModule name) {
        if (!named.containsKey(name)) {
            named.put(name, program.declaration(name));
            pending.add(name);
        }
    }

    private static String scalar(LeafScalar scalar) {
        return "{\"is\":\"scalar\",\"scalar\":" + quoted(scalar.name().toLowerCase(Locale.ROOT)) + "}";
    }

    /** How a set of alternatives travels, with both of its keys where it has them. */
    private static String form(CheckedAlternativesForm form) {
        return switch (form) {
            case CheckedAlternativesForm.Enumeration ignored -> "{\"is\":\"enumeration\"}";
            case CheckedAlternativesForm.Discriminated discriminated ->
                    "{\"is\":\"discriminated\",\"tag\":" + quoted(discriminated.tagKey())
                            + ",\"contents\":" + quoted(discriminated.contentsKey()) + "}";
        };
    }

    private static String quoted(String text) {
        StringBuilder written = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> written.append("\\\"");
                case '\\' -> written.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        written.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        written.append(c);
                    }
                }
            }
        }
        return written.append('"').toString();
    }
}
