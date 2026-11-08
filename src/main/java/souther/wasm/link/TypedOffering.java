package souther.wasm.link;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * What a program offers and has to be given, with the type of everything that crosses: what the
 * typed interfaces are written from, and what a component offering them is built from.
 *
 * <p>The JSON interfaces carry every value as a string, so what {@link Offering} says of a behavior
 * is its name and where it is. Here a behavior is a function of the types its signature names, and
 * those types are declared once, in an interface of the module that declares them, which every
 * interface naming one uses. A Souther type is one WIT type wherever it crosses: in what a program
 * answers, in what it is given and in what a caller reads on its own.
 *
 * <p>What WIT cannot write is refused here and not where it is written down, as {@link Offering}
 * refuses a name an interface cannot carry: two names that come to one, a name that is not a WIT
 * name, and a type that holds itself, which WIT does not declare ("Types, however, cannot be
 * recursive"). Only what crosses is held, so a recursive type no boundary names is never refused.
 *
 * @param types each module's declarations, by the module that declares them
 * @param behaviors the behaviors each module offers, by the name Souther gave them
 * @param readable the types of each module a caller may read on its own, by the name Souther gave
 *     them
 * @param reached the behaviors of each module the program reaches out for, by the name Souther gave
 *     them
 */
public record TypedOffering(Map<String, List<WitType.Declaration>> types,
        Map<String, Map<String, Function>> behaviors, Map<String, Map<String, Function>> readable,
        Map<String, Map<String, Function>> reached) {

    /** The package each module's declarations are in. */
    public static final String TYPES = "souther:typed-types";

    /** The package each module's own behaviors are offered in. */
    public static final String PROGRAM = "souther:typed-program";

    /** The package the types a caller may read on its own are read in. */
    public static final String DECODE = "souther:typed-decode";

    /** The package what a program reaches out for is asked for in. */
    public static final String REACHED = "souther:typed-reached";

    /**
     * The version all four packages are at: the version of how a Souther value crosses as a typed
     * one, which is this backend's to move, and not of any model's types, which are the model's.
     */
    public static final String VERSION = "0.1.0";

    /** What a call the boundary refused is about: where, which rule, and what the rule says. */
    public static final String ISSUE = "issue";

    /** What a function imported for an injected behavior answered that was not of its type. */
    public static final String INVALID_ANSWER = "invalid-answer";

    /** Why a call ended rather than answered. */
    public static final String ENDED = "ended";

    /** The {@code err} of everything a program offers: refused at the boundary, or ended. */
    public static final String REFUSAL = "refusal";

    /**
     * What an interface whose functions can refuse declares beside them, each after what it names.
     *
     * <p>Declared in the interface and not used from one of the model's, because they are the
     * boundary's and not the model's. An {@code issue}'s {@code meta} stays JSON text: what it
     * holds depends on its {@code code}, and a type holding any value is a recursive one.
     */
    public static final List<WitType.Declaration> PROTOCOL = List.of(
            new WitType.Record(ISSUE, List.of(
                    new WitType.Field("path", WitType.STRING),
                    new WitType.Field("code", WitType.STRING),
                    new WitType.Field("message-key", WitType.STRING),
                    new WitType.Field("meta", WitType.STRING),
                    new WitType.Field("message", new WitType.OptionOf(WitType.STRING)))),
            new WitType.Record(INVALID_ANSWER, List.of(
                    new WitType.Field("behavior", WitType.STRING),
                    new WitType.Field("issues", new WitType.ListOf(new WitType.Named(null, ISSUE))))),
            new WitType.Record(ENDED, List.of(
                    new WitType.Field("reason", WitType.U32),
                    new WitType.Field(INVALID_ANSWER,
                            new WitType.OptionOf(new WitType.Named(null, INVALID_ANSWER))))),
            new WitType.Variant(REFUSAL, List.of(
                    new WitType.Case("issues", new WitType.ListOf(new WitType.Named(null, ISSUE))),
                    new WitType.Case(ENDED, new WitType.Named(null, ENDED)))));

    /**
     * A function of an interface.
     *
     * @param name its name, as WIT writes it
     * @param parameters what it takes, in order
     * @param answers what it answers when it answers; what a program offers answers this as the
     *     {@code ok} of a {@code result} whose {@code err} is a {@link #REFUSAL}, and what it is
     *     given answers this alone
     */
    public record Function(String name, List<WitType.Field> parameters, WitType answers) {

        public Function {
            parameters = List.copyOf(parameters);
        }

        /** What it answers where it can refuse: its answer as the {@code ok} of a result. */
        public WitType refusing() {
            return new WitType.ResultOf(answers, new WitType.Named(null, REFUSAL));
        }
    }

    /**
     * Copies what it is given in the order it was given, and refuses what WIT could not write.
     */
    public TypedOffering {
        types = inOrder(types, List::copyOf);
        behaviors = inOrder(behaviors, TypedOffering::functionsInOrder);
        readable = inOrder(readable, TypedOffering::functionsInOrder);
        reached = inOrder(reached, TypedOffering::functionsInOrder);

        apart(types.keySet(), Component::writable);
        apart(behaviors.keySet(), Component::writable);
        apart(readable.keySet(), Component::writable);
        apart(reached.keySet(), Component::writable);

        Map<String, Map<String, WitType.Declaration>> declared = new LinkedHashMap<>();
        for (Map.Entry<String, List<WitType.Declaration>> module : types.entrySet()) {
            Names names = new Names(TYPES + "/" + Component.writable(module.getKey()));
            Map<String, WitType.Declaration> byName = new LinkedHashMap<>();
            for (WitType.Declaration declaration : module.getValue()) {
                names.add(declaration.name(), "a type");
                byName.put(declaration.name(), declaration);
                inner(names.where(), declaration);
            }
            List<WitType> naming = new ArrayList<>();
            module.getValue().forEach(declaration -> naming.addAll(holds(declaration)));
            namesUsed(names, module.getKey(), naming, true);
            declared.put(module.getKey(), byName);
        }
        for (WitType.Declaration declaration : PROTOCOL) {
            inner(PROGRAM, declaration);
        }
        everyNamed(declared, types, behaviors, readable, reached);
        unrecursive(declared);

        interfaces(PROGRAM, behaviors, true);
        interfaces(DECODE, readable, true);
        interfaces(REACHED, reached, false);
    }

    /** The declarations of one module's types interface, or none. */
    public List<WitType.Declaration> declaredIn(String module) {
        return types.getOrDefault(module, List.of());
    }

    /**
     * The types from other interfaces what one interface writes names, by the module whose
     * interface declares them, each once, in the order they are first named: what it has to use.
     *
     * @param module the module the interface is of
     * @param typesInterface whether it is the module's types interface, which declares the
     *     module's own types rather than using them
     * @param written the types the interface writes, wherever in them a name stands
     */
    public static Map<String, Set<String>> used(String module, boolean typesInterface,
            Collection<WitType> written) {
        Map<String, Set<String>> used = new LinkedHashMap<>();
        for (WitType type : written) {
            named(type, found -> {
                if (found.module() != null
                        && !(typesInterface && found.module().equals(module))) {
                    used.computeIfAbsent(found.module(), held -> new LinkedHashSet<>())
                            .add(found.name());
                }
            });
        }
        return used;
    }

    /** Every type a declaration holds, where it holds one. */
    public static List<WitType> holds(WitType.Declaration declaration) {
        return switch (declaration) {
            case WitType.Record record -> record.fields().stream().map(WitType.Field::type).toList();
            case WitType.Variant variant -> variant.cases().stream()
                    .map(WitType.Case::carries)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            case WitType.Enumeration ignored -> List.of();
            case WitType.Alias alias -> List.of(alias.of());
        };
    }

    /** Every type a function names, what it takes and what it answers. */
    public static List<WitType> holds(Function function) {
        List<WitType> held = new ArrayList<>();
        function.parameters().forEach(parameter -> held.add(parameter.type()));
        held.add(function.answers());
        return held;
    }

    private static void named(WitType type, java.util.function.Consumer<WitType.Named> found) {
        switch (type) {
            case WitType.Primitive ignored -> {
            }
            case WitType.ListOf list -> named(list.element(), found);
            case WitType.OptionOf option -> named(option.present(), found);
            case WitType.ResultOf result -> {
                named(result.ok(), found);
                named(result.err(), found);
            }
            case WitType.TupleOf tuple -> tuple.members().forEach(member -> named(member, found));
            case WitType.Named name -> found.accept(name);
        }
    }

    /**
     * The interfaces of one package: each one's functions, the types it uses and, where its
     * functions can refuse, what {@link #PROTOCOL} declares — all of them names in one interface,
     * which WIT gives each one thing.
     */
    private static void interfaces(String held, Map<String, Map<String, Function>> modules,
            boolean refuses) {
        for (Map.Entry<String, Map<String, Function>> module : modules.entrySet()) {
            Names names = new Names(held + "/" + Component.writable(module.getKey()));
            if (refuses) {
                PROTOCOL.forEach(declaration ->
                        names.add(declaration.name(), "what a call that was refused answers"));
            }
            List<WitType> naming = new ArrayList<>();
            for (Map.Entry<String, Function> function : module.getValue().entrySet()) {
                Function crossing = function.getValue();
                names.add(crossing.name(), "the function for " + function.getKey());
                Names parameters = new Names(names.where() + " " + crossing.name());
                crossing.parameters().forEach(parameter ->
                        parameters.add(parameter.name(), "a parameter"));
                naming.addAll(holds(crossing));
            }
            namesUsed(names, module.getKey(), naming, false);
        }
    }

    /** Adds the names an interface uses, each a name of the interface as its own are. */
    private static void namesUsed(Names names, String module, List<WitType> naming,
            boolean typesInterface) {
        for (Map.Entry<String, Set<String>> from : used(module, typesInterface, naming).entrySet()) {
            for (String name : from.getValue()) {
                names.add(name, "a type of " + from.getKey());
            }
        }
    }

    /**
     * The names inside a declaration — a record's fields, a variant's or an enum's cases — which
     * WIT gives each one thing within it.
     */
    private static void inner(String where, WitType.Declaration declaration) {
        Names names = new Names(where + " " + declaration.name());
        switch (declaration) {
            case WitType.Record record -> {
                if (record.fields().isEmpty()) {
                    throw new IllegalArgumentException(names.where()
                            + " would be a record of nothing, which WIT does not write");
                }
                record.fields().forEach(field -> names.add(field.name(), "a field"));
            }
            case WitType.Variant variant ->
                    variant.cases().forEach(each -> names.add(each.name(), "a case"));
            case WitType.Enumeration enumeration ->
                    enumeration.cases().forEach(each -> names.add(each, "a case"));
            case WitType.Alias alias -> {
            }
        }
    }

    /** Refuses a name a module's types interface does not declare. */
    private static void everyNamed(Map<String, Map<String, WitType.Declaration>> declared,
            Map<String, List<WitType.Declaration>> types,
            Map<String, Map<String, Function>> behaviors, Map<String, Map<String, Function>> readable,
            Map<String, Map<String, Function>> reached) {
        List<WitType> everything = new ArrayList<>();
        types.values().forEach(each -> each.forEach(declaration ->
                everything.addAll(holds(declaration))));
        for (Map<String, Map<String, Function>> functions : List.of(behaviors, readable, reached)) {
            functions.values().forEach(each -> each.values().forEach(function ->
                    everything.addAll(holds(function))));
        }
        for (WitType type : everything) {
            named(type, found -> {
                if (found.module() == null) {
                    if (PROTOCOL.stream().noneMatch(each -> each.name().equals(found.name()))) {
                        throw new IllegalArgumentException(
                                found.name() + " is named and declared by no interface");
                    }
                } else if (!declared.getOrDefault(found.module(), Map.of())
                        .containsKey(found.name())) {
                    throw new IllegalArgumentException(found.name() + " is named and "
                            + found.module() + " declares nothing under that name");
                }
            });
        }
    }

    /**
     * Refuses a declaration that holds itself, however far round: WIT declares no recursive type.
     */
    private static void unrecursive(Map<String, Map<String, WitType.Declaration>> declared) {
        Set<WitType.Named> done = new HashSet<>();
        for (Map.Entry<String, Map<String, WitType.Declaration>> module : declared.entrySet()) {
            for (String name : module.getValue().keySet()) {
                visit(declared, new WitType.Named(module.getKey(), name), new ArrayList<>(), done);
            }
        }
    }

    private static void visit(Map<String, Map<String, WitType.Declaration>> declared,
            WitType.Named at, List<WitType.Named> path, Set<WitType.Named> done) {
        if (done.contains(at)) {
            return;
        }
        int seen = path.indexOf(at);
        if (seen >= 0) {
            List<String> round = new ArrayList<>();
            for (WitType.Named each : path.subList(seen, path.size())) {
                round.add(each.module() + "." + each.name());
            }
            round.add(at.module() + "." + at.name());
            throw new IllegalArgumentException("a typed interface cannot hold "
                    + at.module() + "." + at.name() + ", which holds itself ("
                    + String.join(" -> ", round) + "), and WIT declares no recursive type");
        }
        path.add(at);
        WitType.Declaration declaration = declared.get(at.module()).get(at.name());
        for (WitType held : holds(declaration)) {
            named(held, next -> {
                if (next.module() != null) {
                    visit(declared, next, path, done);
                }
            });
        }
        path.removeLast();
        done.add(at);
    }

    /** The names given within one scope, refusing one given twice and one WIT does not write. */
    private static final class Names {

        private final String where;
        private final Map<String, String> given = new LinkedHashMap<>();

        Names(String where) {
            this.where = where;
        }

        String where() {
            return where;
        }

        void add(String name, String what) {
            if (!WRITABLE.matcher(name).matches()) {
                throw new IllegalArgumentException(where + " would give " + what + " the name "
                        + name + ", which is not a name WIT writes");
            }
            String taken = given.putIfAbsent(name, what);
            if (taken != null) {
                throw new IllegalArgumentException(where + " would give the name " + name
                        + " to both " + taken + " and " + what
                        + ", and WIT gives a name one thing");
            }
        }
    }

    private static final Pattern WRITABLE = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    private static void apart(Collection<String> modules, UnaryOperator<String> named) {
        Map<String, String> already = new LinkedHashMap<>();
        for (String module : modules) {
            String interfaceName = named.apply(module);
            String taken = already.put(interfaceName, module);
            if (taken != null) {
                throw new IllegalArgumentException("the modules " + taken + " and " + module
                        + " would both be the interface " + interfaceName);
            }
        }
    }

    private static Map<String, Function> functionsInOrder(Map<String, Function> functions) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(functions));
    }

    private static <T> Map<String, T> inOrder(Map<String, T> byModule, UnaryOperator<T> copied) {
        Map<String, T> held = new LinkedHashMap<>();
        byModule.forEach((module, each) -> held.put(module, copied.apply(each)));
        return Collections.unmodifiableMap(held);
    }
}
