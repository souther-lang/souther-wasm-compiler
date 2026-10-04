package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import souther.compiler.abort.AbortKind;
import souther.compiler.abort.AbortSet;
import souther.compiler.core.BlockReaches;
import souther.compiler.core.Composition;
import souther.compiler.core.Core;
import souther.compiler.core.Kernel;
import souther.compiler.core.ValueShape;
import souther.compiler.program.CheckedBehavior;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedHelper;
import souther.compiler.program.CheckedImplementation;
import souther.compiler.program.CheckedModule;
import souther.compiler.program.CheckedProgram;
import souther.compiler.program.Publication;
import souther.compiler.types.BinOp;
import souther.compiler.types.BindingId;
import souther.compiler.types.Refinement;
import souther.compiler.types.ResolvedCase;
import souther.compiler.types.TypeSymbol;
import souther.compiler.types.ValueName;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.abi.RuntimeAbi.Cell;
import souther.wasm.abi.WasmAbortMapping;
import souther.wasm.abi.WasmFault;
import souther.wasm.emit.Type;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.Component;
import souther.wasm.link.LinkPlan;
import souther.wasm.link.Linker;
import souther.wasm.link.Offering;
import souther.wasm.link.WasmFragment;
import souther.wasm.link.WitText;

/**
 * Writes a checked program as a WebAssembly module.
 *
 * <p>Every behavior a module declares becomes an export named for the module and the behavior. It
 * is handed a pointer and a length at which its arguments are written as one JSON array, in the
 * order the behavior declares its parameters, and answers a pointer and a length at which its
 * answer is written. What reclaims those is the caller, on the runtime's contract.
 *
 * <p>The answer is either the value, under {@code value}, or what the decoder found wrong with the
 * input, under {@code issues}. Bad input is an expected outcome rather than a fault, so it comes
 * back as an answer and not as a trap; a call that ends without answering at all is a Souther
 * abort, which the failure record describes.
 *
 * <p>What this writes today is a body that is a literal or a read of a parameter, over the scalar
 * types. Everything else a checked program can hold is met by name, in {@link NotLowered}, rather
 * than by emitting something that would run and answer the wrong thing.
 */
public final class WasmCompiler {

    private WasmCompiler() {
    }

    /**
     * Compiles a checked program against the runtime this build carries.
     *
     * @param program what a Souther compile checked
     * @return the linked module
     */
    public static byte[] compile(CheckedProgram program) {
        return compile(program, runtimeModule());
    }

    /**
     * Compiles a checked program against a runtime.
     *
     * @param program what a Souther compile checked
     * @param runtime the compiled runtime to link onto
     * @return the linked module
     */
    public static byte[] compile(CheckedProgram program, byte[] runtime) {
        return written(program, runtime, false);
    }

    /**
     * Compiles a checked program as a component.
     *
     * <p>The same core module, wrapped so that each behavior crosses as
     * {@code func(arguments: string) -> string} and the memory it crosses in has an owner the
     * format states. The wrappers a lift needs are written only here, because a core module built
     * for a host that holds its own bracket has no use for them.
     *
     * @param program what a Souther compile checked
     * @return the component
     */
    public static byte[] compileAsComponent(CheckedProgram program) {
        return compileAsComponent(program, runtimeModule());
    }

    /**
     * Compiles a checked program as a component against a runtime.
     *
     * @param program what a Souther compile checked
     * @param runtime the compiled runtime to link onto
     * @return the component
     */
    public static byte[] compileAsComponent(CheckedProgram program, byte[] runtime) {
        return Component.around(written(program, runtime, true), offering(program));
    }

    /**
     * What a program offers and has to be given, as {@link Component} and {@link WitText} are
     * both given it.
     *
     * @param program what a Souther compile checked
     * @return the program's offering
     */
    public static Offering offering(CheckedProgram program) {
        return new Offering(offered(program), readable(program), reachedOutFor(program));
    }

    /**
     * Each type a caller may read a value of on its own, by the module that declares it: its name,
     * and the name {@link Component.Lifted#reading} is given for it.
     */
    private static Map<String, Map<String, String>> readable(CheckedProgram program) {
        Map<String, Map<String, String>> types = new LinkedHashMap<>();
        for (TypeSymbol.AtModule type : decodable(program)) {
            types.computeIfAbsent(type.module(), held -> new LinkedHashMap<>())
                    .put(type.name(), readAs(type));
        }
        return types;
    }

    /** What the lifted function reading a value of a type is named by: its module and its name. */
    private static String readAs(TypeSymbol.AtModule type) {
        return type.key().qualified();
    }

    /**
     * What a program reaches out for, in the order it numbers them.
     *
     * <p>The order the module numbers them in, because it is the same list: a component asks for
     * each of these as an interface, and which one a call is for is the number the program was
     * compiled with.
     *
     * @param program what a Souther compile checked
     * @return each behavior the program declares and does not implement
     */
    private static List<Component.Reach> reachedOutFor(CheckedProgram program) {
        return reachingOut(program).stream()
                .map(behavior -> new Component.Reach(
                        behavior.name().module(), behavior.name().name()))
                .toList();
    }

    /**
     * Each behavior the program declares and does not implement, in the order of the numbers a
     * call out carries for them: a call out names what it reaches by its place here, and
     * {@code souther:surface} says each one's place as its {@code reachOut}.
     */
    private static List<CheckedBehavior> reachingOut(CheckedProgram program) {
        List<CheckedBehavior> reaching = new ArrayList<>();
        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                if (isReachedOutFor(behavior)) {
                    reaching.add(behavior);
                }
            }
        }
        return List.copyOf(reaching);
    }

    /**
     * Whether a caller outside the program may call a behavior: whether its module publishes it.
     *
     * <p>The one answer to what the module offers, read by everything that offers: a core export, a
     * component's interface, the lift a component calls through. A behavior its module keeps is
     * no other module's to name (spec §a-module-publishes-what-it-declares), and a module that
     * exported it anyway would be offering a caller outside Souther what Souther does not offer one
     * inside — as the native backend does not, which links a kept behavior locally.
     */
    static boolean publishes(CheckedModule module, CheckedBehavior behavior) {
        return module.publicationOf(behavior.name()) == Publication.PUBLISHED;
    }

    /** Whether what stands for a behavior is a call out of the program rather than a body. */
    private static boolean isReachedOutFor(CheckedBehavior behavior) {
        return behavior.implementation() instanceof CheckedImplementation.Injected
                || behavior.implementation() instanceof CheckedImplementation.ImplementedElsewhere;
    }

    /**
     * The behaviors a program offers, as its {@link #offering} holds them.
     *
     * <p>A behavior the program does not implement is not among them. Nothing in the module
     * answers it, so what it crosses as is asked for rather than offered, and that is
     * {@link #reachedOutFor} rather than this. Nor is one its module keeps, which is no module's
     * to call from outside ({@link #publishes}).
     *
     * @param program what a Souther compile checked
     * @return every behavior's core export name, by the module that declares it
     */
    private static Map<String, Map<String, String>> offered(CheckedProgram program) {
        Map<String, Map<String, String>> behaviors = new LinkedHashMap<>();
        for (CheckedModule module : program.modules()) {
            Map<String, String> named = new LinkedHashMap<>();
            for (CheckedBehavior behavior : module.behaviors()) {
                if (publishes(module, behavior) && !isReachedOutFor(behavior)) {
                    named.put(behavior.name().name(), exportName(behavior.name()));
                }
            }
            behaviors.put(module.name(), named);
        }
        return behaviors;
    }

    private static byte[] written(CheckedProgram program, byte[] runtime, boolean lifted) {
        LinkPlan plan = LinkPlan.reading(runtime);
        WasmFragment fragment = new WasmFragment(plan);
        Runtime calls = new Runtime(plan);
        Map<ValueName, Integer> reached = new HashMap<>();
        // A type's check is declared as its descriptor is written, because the descriptor holds
        // the slot the check sits in and a body of the check may make a value of the type.
        Map<TypeSymbol.AtModule, Integer> checks = new LinkedHashMap<>();
        Descriptors[] holder = new Descriptors[1];
        Descriptors shapes = new Descriptors(program, fragment, name -> {
            if (holder[0].invariantsOf(name).isEmpty()) {
                return 0;
            }
            int index = fragment.declare(overCells(fragment, 1));
            checks.put(name, index);
            return fragment.slot(index);
        });
        holder[0] = shapes;

        // Every index is settled before the first body is written, because a call writes the index
        // of what it reaches and a body may reach one written after it, or itself.
        // A helper is here where the checker left one. It expands a call to a helper where it was
        // written, so most are gone by now — but not one that reaches itself, which cannot be.
        List<Written> written = new ArrayList<>();
        List<Crossing> injected = new ArrayList<>();
        // The number a call out carries for each behavior reached out for, worked out once for the
        // calls and for what the module says of them.
        Map<ValueName.Behavior, Integer> reachOut = new LinkedHashMap<>();
        for (CheckedBehavior each : reachingOut(program)) {
            reachOut.put(each.name(), reachOut.size());
        }
        List<Composed> composed = new ArrayList<>();
        for (CheckedModule module : program.modules()) {
            for (CheckedHelper helper : module.helpers()) {
                int index = fragment.declare(overCells(fragment, helper.parameters().size()));
                reached.put(helper.declares(), index);
                written.add(new Written(index, helper.parameters().stream()
                        .map(CheckedHelper.Parameter::binder).toList(), helper.body(),
                        helper.declares(), Set.of()));
            }
            for (CheckedBehavior behavior : module.behaviors()) {
                int arity = behavior.signature().takes().size();
                int index = fragment.declare(overCells(fragment, arity));
                reached.put(behavior.name(), index);
                // Reached out for, and the two reasons are one call. What a module holds for a
                // behavior nobody in this program wrote is the same either way; which of them it
                // is is what the module says about itself, not how the call is made.
                if (isReachedOutFor(behavior)) {
                    injected.add(new Crossing(index, behavior, reachOut.get(behavior.name())));
                    continue;
                }
                if (behavior.implementation() instanceof CheckedImplementation.Composed held) {
                    composed.add(new Composed(index, behavior, held.composition()));
                    continue;
                }
                Body body = bodyOf(behavior);
                written.add(new Written(index, body.parameters(), body.body(), behavior.name(),
                        Set.copyOf(behavior.requirements())));
            }
        }

        Emitter emitter = new Emitter(program, fragment, calls, shapes, reached, name -> {
            // Asking for the descriptor is what declares the check, so it is asked first.
            shapes.ofDeclared(name);
            Integer check = checks.get(name);
            if (check == null) {
                throw new IllegalStateException(name + " is checked, and no check was declared for it");
            }
            return check;
        });
        for (Written each : written) {
            fragment.write(each.index(), emitter.overValues(each));
        }
        for (Crossing each : injected) {
            fragment.write(each.index(), emitter.reachingOut(each));
        }
        for (Composed each : composed) {
            fragment.write(each.index(), emitter.composing(each));
        }
        int stringToString = fragment.functionType(
                List.of(Type.I32, Type.I32), List.of(Type.I32, Type.I32));
        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                if (!publishes(module, behavior)) {
                    continue;
                }
                byte[] wrapper = emitter.crossing(behavior, reached.get(behavior.name()));
                fragment.export(exportName(behavior.name()), fragment.define(stringToString, wrapper));
            }
        }
        // Asking for each one's descriptor is what declares its check, so these are asked before
        // the checks below are written, whether or not anything else here reaches the type.
        List<TypeSymbol.AtModule> decodable = decodable(program);
        ByteArrayOutputStream descriptors = new ByteArrayOutputStream();
        WasmWriter writing = new WasmWriter(descriptors);
        for (TypeSymbol.AtModule each : decodable) {
            writing.writeLittleEndian4(shapes.ofDeclared(each));
        }
        int table = decodable.isEmpty() ? 0 : fragment.place(descriptors.toByteArray());
        fragment.export(DECODE, fragment.define(
                fragment.functionType(List.of(Type.I32, Type.I32, Type.I32),
                        List.of(Type.I32, Type.I32)),
                emitter.decoding(table, decodable.size())));

        // Last, because everything before it asks for descriptors and asking for one is what
        // declares a check. Writing a check asks for them too, so this goes round until a round
        // declares nothing new.
        Set<TypeSymbol.AtModule> already = new HashSet<>();
        while (already.size() < checks.size()) {
            for (Map.Entry<TypeSymbol.AtModule, Integer> each : List.copyOf(checks.entrySet())) {
                if (already.add(each.getKey())) {
                    fragment.write(each.getValue(), emitter.checking(each.getKey()));
                }
            }
        }
        if (lifted) {
            liftable(fragment, calls, program, decodable);
        }
        fragment.offers(Surface.of(program, decodable, reachOut));
        return Linker.link(fragment);
    }

    /**
     * The functions a component's lift calls, which a plain core module does not carry.
     *
     * <p>One per behavior, taking the argument string and answering where the answer's own two
     * words are, because the canonical ABI reads a string result out of memory rather than off the
     * stack. One per type a caller may read a value of, the same over {@link #DECODE} with the
     * type's number put in front. And one post-return for all of them: everything a call made is
     * the arena, so what each owes back is the same thing.
     */
    private static void liftable(WasmFragment fragment, Runtime calls, CheckedProgram program,
            List<TypeSymbol.AtModule> decodable) {
        int overStrings = fragment.functionType(
                List.of(Type.I32, Type.I32), List.of(Type.I32));
        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                if (!publishes(module, behavior)) {
                    continue;
                }
                String crossing = exportName(behavior.name());
                byte[] body = new BodyWriter(2, 0)
                        .localGet(0)
                        .localGet(1)
                        .call(fragment.exported(crossing))
                        .call(calls.of(RuntimeAbi.LIFT_AREA))
                        .body();
                fragment.export(Component.Lifted.wrapping(crossing),
                        fragment.define(overStrings, body));
            }
        }
        for (int number = 0; number < decodable.size(); number++) {
            byte[] body = new BodyWriter(2, 0)
                    .constant(number)
                    .localGet(0)
                    .localGet(1)
                    .call(fragment.exported(DECODE))
                    .call(calls.of(RuntimeAbi.LIFT_AREA))
                    .body();
            fragment.export(Component.Lifted.reading(readAs(decodable.get(number))),
                    fragment.define(overStrings, body));
        }
        byte[] rewind = new BodyWriter(1, 0)
                .call(calls.of(RuntimeAbi.ARENA_REWIND))
                .body();
        fragment.export(Component.Lifted.POST_RETURN, fragment.define(
                fragment.functionType(List.of(Type.I32), List.of()), rewind));
    }

    /** The shape of a generated function over values: a cell per parameter, and a cell answered. */
    private static int overCells(WasmFragment fragment, int arity) {
        List<Type> takes = new ArrayList<>();
        for (int i = 0; i < arity; i++) {
            takes.add(Type.I32);
        }
        return fragment.functionType(takes, List.of(Type.I32));
    }

    /**
     * A behavior supplied from outside: where its function goes, which one it is, and what it was
     * declared to take and answer.
     *
     * <p>The number is what the caller is told: an ordinal of this build. Not a name — a name would
     * travel as bytes on every call for something a caller looks up once — so the module says what
     * the numbers are, as each behavior's {@code reachOut} in {@code souther:surface}.
     */
    private record Crossing(int index, CheckedBehavior behavior, int ordinal) {
    }

    /** A behavior written as stages: where its function goes, which one it is, and the stages. */
    private record Composed(int index, CheckedBehavior behavior, Composition composition) {
    }

    /**
     * One function to write: where it goes, what it binds, what it answers, and the behaviors
     * its construction requires injected, which a block written inside it is asked what it reaches
     * against.
     */
    private record Written(int index, List<Core.Binder> parameters, Core body, ValueName declares,
            Set<ValueName.Behavior> requirements) {
    }

    /** What a behavior's implementation says, or why this backend cannot write it. */
    private record Body(List<Core.Binder> parameters, Core body) {
    }

    private static Body bodyOf(CheckedBehavior behavior) {
        return switch (behavior.implementation()) {
            case CheckedImplementation.Body it -> new Body(it.parameters(), it.body());
            case CheckedImplementation.Composed ignored -> throw new IllegalStateException(
                    behavior.name() + " is composed and is written as stages, not as a body");
            case CheckedImplementation.Injected ignored -> throw new IllegalStateException(
                    behavior.name() + " is injected and is written as a crossing, not as a body");
            case CheckedImplementation.Unwritten ignored -> throw new NotLowered(
                    behavior.name() + " is not written, so there is nothing to emit for it");
            case CheckedImplementation.ImplementedElsewhere ignored -> throw new IllegalStateException(
                    behavior.name() + " is implemented by another build and is written as a"
                            + " crossing, not as a body");
        };
    }

    /** The name a caller reaches a behavior by. */
    public static String exportName(ValueName.Behavior behavior) {
        return behavior.module() + "." + behavior.name();
    }

    /**
     * The export a caller reads a value of one type through, on its own and outside any behavior:
     * {@code (number, pointer, length) -> (pointer, length)}, answering what a behavior's export
     * answers, {@code {"value": ...}} or {@code {"issues": [...]}}.
     */
    public static final String DECODE = "__souther_decode";

    /**
     * The types a caller may read a value of on its own, in the order their numbers run.
     *
     * <p>What a module of this program declares and publishes, and nothing else. A type a module
     * keeps is one no caller may make a value of, and one a module on the path declares is that
     * build's to offer. The number is this module's own: a caller looks it up in
     * {@code souther:surface} when it loads the module, and never carries it to another.
     */
    static List<TypeSymbol.AtModule> decodable(CheckedProgram program) {
        List<TypeSymbol.AtModule> offered = new ArrayList<>();
        for (CheckedModule module : program.modules()) {
            for (CheckedData data : module.data()) {
                if (module.publicationOf(data.name()) == Publication.PUBLISHED) {
                    offered.add(data.name());
                }
            }
        }
        return List.copyOf(offered);
    }

    /** The runtime module this build carries. */
    public static byte[] runtimeModule() {
        try (InputStream in = WasmCompiler.class.getResourceAsStream("/souther/wasm/runtime.wasm")) {
            if (in == null) {
                throw new IllegalStateException("this build carries no runtime module");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The runtime function an intrinsic is a call of.
     *
     * <p>Written out rather than worked out from the name, because an intrinsic's name belongs to
     * the library and an export's to the runtime, and neither is derivable from the other. What
     * holds them together is that every constant the library declares is named here and every name
     * here is one the runtime exports — which is asked of the two lists rather than of a call.
     */
    static String abiNameOf(Kernel kernel) {
    return switch (kernel) {
        case STRING_LENGTH -> RuntimeAbi.Kernels.STRING_LENGTH;
        case STRING_SLICE -> RuntimeAbi.Kernels.STRING_SLICE;
        case STRING_APPEND -> RuntimeAbi.Kernels.STRING_APPEND;
        case STRING_REVERSE -> RuntimeAbi.Kernels.STRING_REVERSE;
        case STRING_REPEAT -> RuntimeAbi.Kernels.STRING_REPEAT;
        case STRING_CONTAINS -> RuntimeAbi.Kernels.STRING_CONTAINS;
        case STRING_MATCHES -> RuntimeAbi.Kernels.STRING_MATCHES;
        case STRING_STARTS_WITH -> RuntimeAbi.Kernels.STRING_STARTS_WITH;
        case STRING_ENDS_WITH -> RuntimeAbi.Kernels.STRING_ENDS_WITH;
        case STRING_TRIM -> RuntimeAbi.Kernels.STRING_TRIM;
        case STRING_FROM_INT -> RuntimeAbi.Kernels.STRING_FROM_INT;
        case STRING_SPLIT -> RuntimeAbi.Kernels.STRING_SPLIT;
        case STRING_JOIN -> RuntimeAbi.Kernels.STRING_JOIN;
        case STRING_CONCAT -> RuntimeAbi.Kernels.STRING_CONCAT;
        case STRING_REPLACE -> RuntimeAbi.Kernels.STRING_REPLACE;
        case STRING_LOWERCASE -> RuntimeAbi.Kernels.STRING_LOWERCASE;
        case STRING_UPPERCASE -> RuntimeAbi.Kernels.STRING_UPPERCASE;
        case STRING_WORDS -> RuntimeAbi.Kernels.STRING_WORDS;
        case STRING_LINES -> RuntimeAbi.Kernels.STRING_LINES;
        case STRING_PAD_LEFT -> RuntimeAbi.Kernels.STRING_PAD_LEFT;
        case STRING_PAD_RIGHT -> RuntimeAbi.Kernels.STRING_PAD_RIGHT;
        case STRING_CHARACTERS -> RuntimeAbi.Kernels.STRING_CHARACTERS;
        case STRING_CODE_POINTS -> RuntimeAbi.Kernels.STRING_CODE_POINTS;
        case INT_ADD -> RuntimeAbi.Kernels.INT_ADD;
        case INT_SUBTRACT -> RuntimeAbi.Kernels.INT_SUBTRACT;
        case INT_MULTIPLY -> RuntimeAbi.Kernels.INT_MULTIPLY;
        case INT_COMPARE -> RuntimeAbi.Kernels.INT_COMPARE;
        case INT_FLOOR_MOD -> RuntimeAbi.Kernels.INT_FLOOR_MOD;
        case INT_TRUNCATING_DIVIDE -> RuntimeAbi.Kernels.INT_DIVIDE;
        case INT_TRUNCATING_REMAINDER -> RuntimeAbi.Kernels.INT_TRUNCATING_REMAINDER;
        case STRING_TO_INT -> RuntimeAbi.Kernels.STRING_TO_INT;
        case STRING_FROM_DECIMAL -> RuntimeAbi.Kernels.STRING_FROM_DECIMAL;
        case STRING_TO_DECIMAL -> RuntimeAbi.Kernels.STRING_TO_DECIMAL;
        case OPTION_MAP -> RuntimeAbi.Kernels.OPTION_MAP;
        case LIST_LENGTH -> RuntimeAbi.Kernels.LIST_LENGTH;
        case LIST_GET -> RuntimeAbi.Kernels.LIST_GET;
        case LIST_FIND -> RuntimeAbi.Kernels.LIST_FIND;
        case DECIMAL_ADD -> RuntimeAbi.Kernels.DECIMAL_ADD;
        case DECIMAL_SUBTRACT -> RuntimeAbi.Kernels.DECIMAL_SUBTRACT;
        case DECIMAL_MULTIPLY -> RuntimeAbi.Kernels.DECIMAL_MULTIPLY;
        case DECIMAL_DIVIDE -> RuntimeAbi.Kernels.DECIMAL_DIVIDE;
        case DECIMAL_ROUND -> RuntimeAbi.Kernels.DECIMAL_ROUND;
        case DECIMAL_TO_INT -> RuntimeAbi.Kernels.DECIMAL_TO_INT;
        case DECIMAL_FROM_INT -> RuntimeAbi.Kernels.DECIMAL_FROM_INT;
        case DECIMAL_COMPARE -> RuntimeAbi.Kernels.DECIMAL_COMPARE;
        case DATE_ADD_DAYS, DATETIME_ADD_DAYS -> RuntimeAbi.Kernels.DATE_ADD_DAYS;
        case DATE_ADD_MONTHS -> RuntimeAbi.Kernels.DATE_ADD_MONTHS;
        case DATE_ADD_YEARS -> RuntimeAbi.Kernels.DATE_ADD_YEARS;
        case DATE_DAYS_BETWEEN -> RuntimeAbi.Kernels.DATE_DAYS_BETWEEN;
        case DATE_YEAR -> RuntimeAbi.Kernels.DATE_YEAR;
        case DATE_MONTH -> RuntimeAbi.Kernels.DATE_MONTH;
        case DATE_DAY -> RuntimeAbi.Kernels.DATE_DAY;
        case DATE_FROM_PARTS -> RuntimeAbi.Kernels.DATE_FROM_PARTS;
        case TIME_FROM_PARTS -> RuntimeAbi.Kernels.TIME_FROM_PARTS;
        case TIME_HOUR -> RuntimeAbi.Kernels.TIME_HOUR;
        case TIME_MINUTE -> RuntimeAbi.Kernels.TIME_MINUTE;
        case TIME_SECOND -> RuntimeAbi.Kernels.TIME_SECOND;
        case DATETIME_ADD_MINUTES -> RuntimeAbi.Kernels.DATETIME_ADD_MINUTES;
        case DATETIME_ADD_HOURS -> RuntimeAbi.Kernels.DATETIME_ADD_HOURS;
        case DATETIME_MINUTES_BETWEEN -> RuntimeAbi.Kernels.DATETIME_MINUTES_BETWEEN;
        case DATETIME_TO_DATE -> RuntimeAbi.Kernels.DATETIME_TO_DATE;
        case DATETIME_TO_TIME -> RuntimeAbi.Kernels.DATETIME_TO_TIME;
        case DATETIME_FROM_DATE_AND_TIME -> RuntimeAbi.Kernels.DATETIME_FROM_PARTS;
        case LIST_SORT -> RuntimeAbi.Kernels.LIST_SORT;
        case LIST_SORT_BY -> RuntimeAbi.Kernels.LIST_SORT_BY;
        case LIST_MAX -> RuntimeAbi.Kernels.LIST_MAX;
        case LIST_MIN -> RuntimeAbi.Kernels.LIST_MIN;
        case LIST_REVERSE -> RuntimeAbi.Kernels.LIST_REVERSE;
        case LIST_SUM -> RuntimeAbi.Kernels.LIST_SUM;
        case LIST_PRODUCT -> RuntimeAbi.Kernels.LIST_PRODUCT;
        case LIST_RANGE_INCLUSIVE -> RuntimeAbi.Kernels.LIST_RANGE_INCLUSIVE;
        case SET_EMPTY -> RuntimeAbi.Kernels.SET_EMPTY;
        case SET_SINGLETON -> RuntimeAbi.Kernels.SET_SINGLETON;
        case SET_INSERT -> RuntimeAbi.Kernels.SET_INSERT;
        case SET_REMOVE -> RuntimeAbi.Kernels.SET_REMOVE;
        case SET_CONTAINS -> RuntimeAbi.Kernels.SET_CONTAINS;
        case SET_UNION -> RuntimeAbi.Kernels.SET_UNION;
        case SET_INTERSECTION -> RuntimeAbi.Kernels.SET_INTERSECTION;
        case SET_DIFFERENCE -> RuntimeAbi.Kernels.SET_DIFFERENCE;
        case SET_TO_LIST -> RuntimeAbi.Kernels.SET_TO_LIST;
        case SET_FROM_LIST -> RuntimeAbi.Kernels.SET_FROM_LIST;
        case SET_IS_EMPTY, MAP_IS_EMPTY -> RuntimeAbi.Kernels.IS_EMPTY;
        case SET_SIZE, MAP_SIZE -> RuntimeAbi.Kernels.SIZE;
        case MAP_EMPTY -> RuntimeAbi.Kernels.MAP_EMPTY;
        case MAP_GET -> RuntimeAbi.Kernels.MAP_GET;
        case MAP_CONTAINS_KEY -> RuntimeAbi.Kernels.MAP_CONTAINS_KEY;
        case MAP_KEYS -> RuntimeAbi.Kernels.MAP_KEYS;
        case MAP_VALUES -> RuntimeAbi.Kernels.MAP_VALUES;
        case MAP_SINGLETON -> RuntimeAbi.Kernels.MAP_SINGLETON;
        case MAP_INSERT -> RuntimeAbi.Kernels.MAP_INSERT;
        case MAP_REMOVE -> RuntimeAbi.Kernels.MAP_REMOVE;
        case MAP_TO_LIST -> RuntimeAbi.Kernels.MAP_TO_LIST;
        case MAP_FROM_LIST -> RuntimeAbi.Kernels.MAP_FROM_LIST;
        case RATIONAL_FROM_INT -> RuntimeAbi.Kernels.RATIONAL_FROM_INT;
        case RATIONAL_FROM_DECIMAL -> RuntimeAbi.Kernels.RATIONAL_FROM_DECIMAL;
        case RATIONAL_TO_WHOLE_NUMBER -> RuntimeAbi.Kernels.RATIONAL_TO_WHOLE_NUMBER;
        case RATIONAL_TO_FINITE_DECIMAL -> RuntimeAbi.Kernels.RATIONAL_TO_FINITE_DECIMAL;
        case RATIONAL_TO_INT -> RuntimeAbi.Kernels.RATIONAL_TO_INT;
        case RATIONAL_TO_DECIMAL -> RuntimeAbi.Kernels.RATIONAL_TO_DECIMAL;
        case RATIONAL_ADD -> RuntimeAbi.Kernels.RATIONAL_ADD;
        case RATIONAL_SUBTRACT -> RuntimeAbi.Kernels.RATIONAL_SUBTRACT;
        case RATIONAL_MULTIPLY -> RuntimeAbi.Kernels.RATIONAL_MULTIPLY;
        case RATIONAL_DIVIDE -> RuntimeAbi.Kernels.RATIONAL_DIVIDE;
        case RATIONAL_COMPARE -> RuntimeAbi.Kernels.RATIONAL_COMPARE;
        default -> throw new NotLowered(kernel + " is an intrinsic this backend does not"
                + " write yet, which the library declared after this switch was last read");
    };
    }

    /** The runtime's functions, by the index a generated call writes. */
    private record Runtime(LinkPlan plan) {

        int of(String export) {
            return plan.functionIndexOf(export);
        }
    }

    /**
     * Writes the bodies of what a program declares.
     *
     * <p>Two shapes of function. One is what a declaration is: a cell per parameter, and a cell
     * answered, which is what a call from another body reaches. The other is the crossing an
     * export is — a document in and a document out — which reads the arguments, calls the first,
     * and writes what came back.
     */
    private static final class Emitter {

        private final CheckedProgram program;
        private final WasmFragment fragment;
        private final Patterns patterns;
        private final Runtime calls;
        private final Descriptors shapes;
        private final Map<ValueName, Integer> reached;
        private final Cells cells;
        /** The function checking what must hold of a type, by the type. */
        private final java.util.function.ToIntFunction<TypeSymbol.AtModule> checkOf;

        private BodyWriter out;
        private Map<BindingId, Integer> locals;
        private Object writing;
        private Set<ValueName.Behavior> requirementsInScope = Set.of();

        Emitter(CheckedProgram program, WasmFragment fragment, Runtime calls, Descriptors shapes,
                Map<ValueName, Integer> reached,
                java.util.function.ToIntFunction<TypeSymbol.AtModule> checkOf) {
            this.program = program;
            this.fragment = fragment;
            this.patterns = shapes.patterns();
            this.cells = new Cells(fragment);
            this.calls = calls;
            this.shapes = shapes;
            this.reached = reached;
            this.checkOf = checkOf;
        }

        /**
         * A declaration's own function: its parameters as cells, its answer as one.
         *
         * <p>A call of the declaration to itself where its answer is the declaration's answer is not
         * a call: the arguments are put where the parameters are and the body runs again from the
         * top. So a walk written as a recursion, which is how {@code List.fold} is written, runs in
         * one frame however long the list is, where a call per element would run out of stack a few
         * thousand elements in.
         */
        byte[] overValues(Written written) {
            int arity = written.parameters().size();
            out = new BodyWriter(arity, 0);
            locals = new HashMap<>();
            writing = written.declares();
            requirementsInScope = written.requirements();
            for (int i = 0; i < arity; i++) {
                locals.put(written.parameters().get(i).binding(), i);
            }
            int answer = scratch();
            out.block().loop();
            again = new Again(written.declares(), arity, out.depth());
            answer(out, written.body());
            again = null;
            out.localSet(answer).leave(1).end().end().localGet(answer);
            return out.body();
        }

        /**
         * Where a call of the declaration being written to itself goes back to instead: the loop
         * opened at that depth around its body, whose parameters are its first locals.
         */
        private record Again(Object declaration, int arity, int depth) {}

        /** The declaration being written and its loop, or null where nothing goes back. */
        private Again again;

        /** How a part of an expression is written: as a value, or as the function's answer. */
        private interface Writing {
            void write(BodyWriter out, Core expression);
        }

        /**
         * An expression that is the answer of the function being written.
         *
         * <p>Where it chooses or binds on the way to its answer, each part its answer is is the
         * function's answer too, and is written here again; a call of the declaration to itself
         * there goes back to the top of the body. Everything else is written as a value.
         *
         * <p>Every construct is named and none falls through, so a construct added to the language
         * is a question this compiler has to answer, not one it answers by not asking: a construct
         * whose answer is one of its parts that fell to the value side would leave a recursion
         * through it a call per step, which is the stack running out for a long enough walk.
         */
        private void answer(BodyWriter out, Core expression) {
            switch (expression) {
                case Core.If chosen -> chosen(out, chosen, this::answer);
                case Core.IfConstructed attempted -> attempt(out, attempted, this::answer);
                case Core.LetIn bound -> bound(out, bound, this::answer);
                case Core.Match chosen -> match(out, chosen, this::answer);
                case Core.Widen widened -> answer(out, widened.value());
                case Core.Call call -> {
                    if (goesBack(call)) {
                        goBack(out, call);
                    } else {
                        call(out, call);
                    }
                }
                case Core.Int _, Core.Decimal _, Core.Str _, Core.Bool _, Core.Temporal _,
                        Core.Read _, Core.UnitValue _, Core.MaterialisedValue _, Core.Neg _,
                        Core.FieldAccess _, Core.FieldProjection _, Core.Binary _,
                        Core.PreservedCall _, Core.Apply _, Core.Block _, Core.ListLit _,
                        Core.OptionSome _, Core.OptionNone _, Core.Tuple _, Core.TupleGet _,
                        Core.Construct _, Core.Unreachable _ -> value(out, expression);
            }
        }

        /**
         * A behavior written as stages, each applied to what the one before answered.
         *
         * <p>A stage offered part of what is running takes only the cases it accepts; anything
         * else has left the main line, and the composition answers with it rather than offering it
         * to what follows. Which cases those are is the checker's answer and is read off the
         * stage — a backend working it out again would be working out what is already settled.
         */
        byte[] composing(Composed held) {
            var takes = held.behavior().signature().takes();
            out = new BodyWriter(takes.size(), 0);
            locals = new HashMap<>();
            writing = held.behavior().name();
            requirementsInScope = Set.copyOf(held.behavior().requirements());
            int running = out.narrow();

            List<Composition.Stage> stages = held.composition().stages();
            for (int i = 0; i < takes.size(); i++) {
                out.localGet(i);
            }
            out.call(reached.get(stages.get(0).behavior())).localSet(running);

            out.block();
            for (int i = 1; i < stages.size(); i++) {
                Composition.Stage stage = stages.get(i);
                if (stage.routing() instanceof Composition.Routing.OnCases accepted) {
                    accepts(out, running, accepted.accepted());
                    out.ifZero().leave(1).end();
                }
                out.localGet(running).call(reached.get(stage.behavior())).localSet(running);
            }
            out.end();

            return out.localGet(running).body();
        }

        /** Leaves on the stack whether the running value is one of the cases a stage accepts. */
        private void accepts(BodyWriter out, int running, List<TypeSymbol> cases) {
            for (int i = 0; i < cases.size(); i++) {
                out.localGet(running)
                        .constant(shapes.ofMember(cases.get(i)))
                        .call(calls.of(RuntimeAbi.IS));
                if (i > 0) {
                    out.or();
                }
            }
        }

        /**
         * A behavior supplied from outside, as the crossing it is.
         *
         * <p>Its arguments are written as the array a call is, handed over, and what came back is
         * read as the type the declaration answers. So a host implements one the way a caller
         * calls one: a document in, a document out, and nothing of how this module holds a value.
         */
        byte[] reachingOut(Crossing crossing) {
            var takes = crossing.behavior().signature().takes();
            out = new BodyWriter(takes.size(), 1);
            locals = new HashMap<>();
            writing = crossing.behavior().name();
            requirementsInScope = Set.copyOf(crossing.behavior().requirements());
            int written = out.wide(0);
            int document = out.narrow();

            // The arguments as one array, which is what a call is written as at either edge.
            out.constant(takes.size()).call(calls.of(RuntimeAbi.WRITE_ARGUMENTS)).localSet(document);
            for (int i = 0; i < takes.size(); i++) {
                out.localGet(document)
                        .localGet(i)
                        .constant(shapes.of(takes.get(i)))
                        .call(calls.of(RuntimeAbi.WRITE_ARGUMENT));
            }
            out.localGet(document).call(calls.of(RuntimeAbi.SEAL_ARGUMENTS)).localSet(document);

            out.constant(crossing.ordinal())
                    .localGet(document)
                    .call(calls.of(RuntimeAbi.ARGUMENTS_BYTES))
                    .localGet(document)
                    .call(calls.of(RuntimeAbi.ARGUMENTS_LENGTH))
                    .call(calls.of(RuntimeAbi.HOST_CALL))
                    .localSet(written);

            out.localGet(written).wrap()
                    .localGet(written).shiftRight(32).wrap()
                    .call(calls.of(RuntimeAbi.JSON_PARSE))
                    .constant(shapes.of(crossing.behavior().signature().answers()))
                    .constant(0)
                    .constant(0)
                    .call(calls.of(RuntimeAbi.READ));
            return out.body();
        }

        /**
         * What checks a type's invariants: the value, and which of them it breaks.
         *
         * <p>Answers the clause's place among the ones the type writes, or minus one where the
         * value breaks none. Which clause it is rather than that one was broken, because a caller
         * told only that something is wrong has to work out what from the value it already had.
         *
         * <p>One function for both places it is asked from. The boundary runs it on what a
         * document said, and a body runs it on what the body made, and the answer is the same
         * question — what differs is what is done with it.
         */
        byte[] checking(TypeSymbol.AtModule name) {
            out = new BodyWriter(1, 0);
            locals = new HashMap<>();
            writing = name;
            requirementsInScope = Set.of();
            List<ValueShape.Field> fields = shapes.fieldsOf(name);
            for (int i = 0; i < fields.size(); i++) {
                int local = out.narrow();
                out.localGet(0).constant(i).call(calls.of(RuntimeAbi.RECORD_GET)).localSet(local);
                locals.put(fields.get(i).binding(), local);
            }
            int answer = out.narrow();
            out.constant(-1).localSet(answer);

            List<ValueShape.Invariant> invariants = shapes.invariantsOf(name);
            for (int i = invariants.size() - 1; i >= 0; i--) {
                truth(out, invariants.get(i).condition());
                out.ifZero().constant(i).localSet(answer).end();
            }
            return out.localGet(answer).body();
        }

        /**
         * The export a behavior is reached from outside by.
         *
         * <p>The arguments are one JSON array in the order the behavior declares its parameters,
         * and the answer is either the value under {@code value} or what the decoder found wrong
         * under {@code issues}.
         */
        byte[] crossing(CheckedBehavior behavior, int declaration) {
            out = new BodyWriter(2, 1);
            locals = new HashMap<>();
            writing = behavior.name();
            requirementsInScope = Set.copyOf(behavior.requirements());
            int packed = out.wide(0);
            int document = out.narrow();
            var takes = behavior.signature().takes();
            int arity = takes.size();

            out.call(calls.of(RuntimeAbi.ISSUES_BEGIN))
                    .localGet(LOCAL_INPUT_POINTER)
                    .localGet(LOCAL_INPUT_LENGTH)
                    .call(calls.of(RuntimeAbi.JSON_PARSE))
                    .localSet(document)
                    .localGet(document)
                    .constant(arity)
                    .call(calls.of(RuntimeAbi.CHECK_ARGUMENTS));

            // Only where the arguments are there at all: a place that is not in the document has
            // nowhere to be read from, and reading it would be reading past what the caller wrote.
            int[] read = new int[arity];
            out.call(calls.of(RuntimeAbi.ISSUES_COUNT)).ifZero();
            for (int i = 0; i < arity; i++) {
                read[i] = out.narrow();
                byte[] path = ("/" + i).getBytes(StandardCharsets.UTF_8);
                out.localGet(document)
                        .constant(i)
                        .call(calls.of(RuntimeAbi.ARGUMENT))
                        .constant(shapes.of(takes.get(i)))
                        .constant(fragment.intern(path))
                        .constant(path.length)
                        .call(calls.of(RuntimeAbi.READ))
                        .localSet(read[i]);
            }
            out.end();

            // A body runs on what was read, and only where everything was. A refused place leaves
            // nothing behind, and nothing is not a value to run a behavior on.
            out.call(calls.of(RuntimeAbi.ISSUES_COUNT)).ifNotZero()
                    .call(calls.of(RuntimeAbi.ISSUES_WRITTEN))
                    .localSet(packed)
                    .otherwise();
            for (int i = 0; i < arity; i++) {
                out.localGet(read[i]);
            }
            out.call(declaration)
                    .constant(shapes.of(behavior.signature().answers()))
                    .call(calls.of(RuntimeAbi.WRITE))
                    .localSet(packed)
                    .end();

            return out.localGet(packed)
                    .wrap()
                    .localGet(packed)
                    .shiftRight(32)
                    .wrap()
                    .body();
        }

        /**
         * What reads a value of one of the types a caller may read on its own, by its number.
         *
         * <p>The reading is the one a behavior's argument is read with, the rules a value is held
         * to included, from the root of the document rather than from a place among the
         * arguments; and what it answers is what a behavior's export answers, the value written
         * back as the type writes it, or the issues found. A number naming no type is the caller
         * misusing the module rather than writing a bad document, so it ends the call.
         *
         * @param table where the descriptors are, one four-byte address to a number
         * @param count how many numbers there are
         */
        byte[] decoding(int table, int count) {
            out = new BodyWriter(3, 1);
            locals = new HashMap<>();
            int packed = out.wide(0);
            int document = out.narrow();
            int descriptor = out.narrow();
            int read = out.narrow();

            out.localGet(DECODE_NUMBER).constant(0).compares(BodyWriter.Comparison.LESS)
                    .localGet(DECODE_NUMBER).constant(count)
                    .compares(BodyWriter.Comparison.AT_LEAST)
                    .or()
                    .ifNotZero()
                    .constant(WasmFault.NO_SUCH_TYPE.code())
                    .constant(0)
                    .localGet(DECODE_NUMBER).extendToWide()
                    .constant((long) count)
                    .call(calls.of(RuntimeAbi.ABORT))
                    .unreachable()
                    .end();

            out.localGet(DECODE_NUMBER).shiftLeft(2).constant(table).add().load(0)
                    .localSet(descriptor)
                    .call(calls.of(RuntimeAbi.ISSUES_BEGIN))
                    .localGet(DECODE_POINTER)
                    .localGet(DECODE_LENGTH)
                    .call(calls.of(RuntimeAbi.JSON_PARSE))
                    .localSet(document)
                    .localGet(document)
                    .localGet(descriptor)
                    // The root, which a JSON pointer writes as nothing at all.
                    .constant(0)
                    .constant(0)
                    .call(calls.of(RuntimeAbi.READ))
                    .localSet(read);

            out.call(calls.of(RuntimeAbi.ISSUES_COUNT)).ifNotZero()
                    .call(calls.of(RuntimeAbi.ISSUES_WRITTEN))
                    .localSet(packed)
                    .otherwise()
                    .localGet(read)
                    .localGet(descriptor)
                    .call(calls.of(RuntimeAbi.WRITE))
                    .localSet(packed)
                    .end();

            return out.localGet(packed)
                    .wrap()
                    .localGet(packed)
                    .shiftRight(32)
                    .wrap()
                    .body();
        }

        /** Which type a caller asks a value to be read as. */
        private static final int DECODE_NUMBER = 0;
        /** The pointer the caller's JSON is at, beside the number. */
        private static final int DECODE_POINTER = 1;
        /** How long the caller's JSON is, beside the number. */
        private static final int DECODE_LENGTH = 2;

        /** The pointer the caller's JSON is at. */
        private static final int LOCAL_INPUT_POINTER = 0;
        /** How long the caller's JSON is. */
        private static final int LOCAL_INPUT_LENGTH = 1;

        /** Leaves the value of an expression on the stack, as the cell it is. */
        private void value(BodyWriter out, Core expression) {
            switch (expression) {
                // A literal is a cell in static memory, made once, rather than one made per reach.
                case Core.Int number -> out.constant(cells.ofInt(number.value()));
                case Core.Bool bool -> out.constant(cells.ofBool(bool.value()));
                case Core.Str text ->
                        out.constant(cells.ofString(text.value().getBytes(StandardCharsets.UTF_8)));
                case Core.Decimal amount -> {
                    // The text rather than the digits and the scale, because that is the one form
                    // both sides already agree on how to read, and what was written is what a
                    // scale is: a thousand written to two places carries two.
                    byte[] written = amount.value().toPlainString()
                            .getBytes(StandardCharsets.UTF_8);
                    out.constant(fragment.intern(written))
                            .constant(written.length)
                            .call(calls.of(RuntimeAbi.DECIMAL_WRITTEN));
                }
                case Core.Temporal written -> {
                    byte[] utf8 = written.text().getBytes(StandardCharsets.UTF_8);
                    out.constant(fragment.intern(utf8))
                            .constant(utf8.length)
                            .call(calls.of(switch (written.kind()) {
                                case DATE -> RuntimeAbi.DATE_WRITTEN;
                                case TIME -> RuntimeAbi.TIME_WRITTEN;
                                case DATETIME -> RuntimeAbi.DATETIME_WRITTEN;
                                case INSTANT -> RuntimeAbi.INSTANT_WRITTEN;
                                default -> throw new NotLowered(writing + " writes down a "
                                        + written.kind() + ", which is no day and no time");
                            }));
                }
                case Core.ListLit made -> {
                    int list = scratch();
                    out.constant(shapes.of(made.type()))
                            .constant(made.elements().size())
                            .call(calls.of(RuntimeAbi.LIST))
                            .localSet(list);
                    for (int i = 0; i < made.elements().size(); i++) {
                        out.localGet(list).constant(i);
                        value(out, made.elements().get(i));
                        out.call(calls.of(RuntimeAbi.LIST_SET));
                    }
                    out.localGet(list);
                }
                case Core.OptionSome some -> {
                    value(out, some.value());
                    out.call(calls.of(RuntimeAbi.SOME));
                }
                case Core.OptionNone ignored -> out.constant(cells.none());
                case Core.UnitValue only -> out.constant(cells.unit(shapes.ofMember(only.data())));
                case Core.Construct made -> {
                    int record = constructed(out, made);
                    // Construction re-checks what must hold. Here the value is the body's own, so
                    // a violation is a model bug rather than something a caller wrote, and it ends
                    // the call: there is no case for it and no value to answer with.
                    if (!shapes.invariantsOf(made.typeName()).isEmpty()) {
                        int broken = scratch();
                        out.localGet(record)
                                .call(checkOf.applyAsInt(made.typeName()))
                                .localSet(broken)
                                .localGet(broken)
                                .constant(-1)
                                .compares(BodyWriter.Comparison.UNEQUAL)
                                .ifNotZero()
                                .constant(WasmAbortMapping.representationOf(onlyKindOf(made)))
                                .constant(shapes.ofDeclared(made.typeName()))
                                .localGet(broken)
                                .extendToWide()
                                .constant(0L)
                                .call(calls.of(RuntimeAbi.ABORT))
                                .unreachable()
                                .end();
                    }
                    out.localGet(record);
                }
                case Core.Unreachable nothing -> {
                    // The reason lives in static memory, so a host reading the record after the
                    // arena has been reset still has it.
                    byte[] why = nothing.reason().getBytes(StandardCharsets.UTF_8);
                    out.constant(WasmAbortMapping.representationOf(onlyKindOf(nothing)))
                            .constant(0)
                            .constant((long) fragment.intern(why))
                            .constant((long) why.length)
                            .call(calls.of(RuntimeAbi.ABORT))
                            .unreachable();
                }
                case Core.IfConstructed attempted -> attempt(out, attempted, this::value);
                case Core.FieldAccess read -> {
                    value(out, read.target());
                    TypeSymbol.AtModule shape = shapeOf(read.target());
                    if (shapes.settlesWhereAFieldLies(shape)) {
                        out.constant(shapes.positionOf(shape, read.field()))
                                .call(calls.of(RuntimeAbi.RECORD_GET));
                    } else {
                        // Read off a set of alternatives, so the value says where the field is.
                        byte[] named = read.field().getBytes(StandardCharsets.UTF_8);
                        out.constant(fragment.intern(named))
                                .constant(named.length)
                                .call(calls.of(RuntimeAbi.RECORD_NAMED));
                    }
                }
                case Core.Neg opposite -> {
                    if (worksOutAWholeNumber(opposite)) {
                        wide(out, opposite);
                        out.call(calls.of(RuntimeAbi.INT));
                    } else {
                        value(out, opposite.operand());
                        out.call(calls.of(switch (opposite.type()) {
                            case souther.compiler.types.Type.Prim.DECIMAL ->
                                    RuntimeAbi.Kernels.DECIMAL_NEGATE;
                            case souther.compiler.types.Type.Prim.RATIONAL ->
                                    RuntimeAbi.Kernels.RATIONAL_NEGATE;
                            default -> RuntimeAbi.NEGATE;
                        }));
                    }
                }
                case Core.Binary binary -> binary(out, binary);
                case Core.If chosen -> chosen(out, chosen, this::value);
                case Core.LetIn bound -> bound(out, bound, this::value);
                case Core.Tuple together -> {
                    int pair = scratch();
                    out.constant(together.elements().size())
                            .call(calls.of(RuntimeAbi.TUPLE))
                            .localSet(pair);
                    for (int i = 0; i < together.elements().size(); i++) {
                        out.localGet(pair).constant(i);
                        value(out, together.elements().get(i));
                        out.call(calls.of(RuntimeAbi.TUPLE_SET));
                    }
                    out.localGet(pair);
                }
                case Core.TupleGet place -> {
                    value(out, place.tuple());
                    out.constant(place.index()).call(calls.of(RuntimeAbi.TUPLE_GET));
                }
                case Core.Match chosen -> match(out, chosen, this::value);
                case Core.Block block -> closure(out, block);
                case Core.Apply applied -> apply(out, applied);
                case Core.Call call -> call(out, call);
                // Every value here is a cell that says what it is, so a value standing as a wider
                // type is the same cell, and nothing is written for the widening.
                case Core.Widen widened -> value(out, widened.value());
                case Core.Read read -> {
                    Integer local = locals.get(read.binding());
                    if (local == null) {
                        throw new NotLowered(writing + " reads " + read.name()
                                + ", which is bound by something this backend does not write yet");
                    }
                    out.localGet(local);
                }
                default -> throw new NotLowered(writing + " answers with "
                        + expression.getClass().getSimpleName()
                        + ", which this backend does not write yet");
            }
        }

        /**
         * A block written where a value goes: where its body is, and what it reads from around it.
         *
         * <p>The body becomes a function of its own, taking what the block was written among ahead
         * of what it is applied to. What it reads from around it is copied in as the block is made,
         * so the block answers the same afterwards however the body it left goes on.
         */
        private void closure(BodyWriter out, Core.Block block) {
            List<Core.Read> captured = capturedBy(block);
            int slot = fragment.slot(blockFunction(block, captured));
            // A block reading nothing from around it is the same value wherever it is made, so it
            // is one cell in static memory.
            if (captured.isEmpty()) {
                out.constant(cells.closure(slot));
                return;
            }
            out.constant(slot);
            room(out, captured);
            out.call(calls.of(RuntimeAbi.CLOSURE));
        }

        /** What a block reads from around it, each of which the frame it is written in holds. */
        private List<Core.Read> capturedBy(Core.Block block) {
            List<Core.Read> captured = BlockReaches.of(block, requirementsInScope).bindings();
            for (Core.Read read : captured) {
                if (!locals.containsKey(read.binding())) {
                    throw new IllegalStateException(writing + ": a block reaches " + read.name()
                            + " but the Wasm frame around it does not hold it");
                }
            }
            return captured;
        }

        /**
         * Leaves on the stack the room holding what a block reads from around it, copied now; or
         * nothing at all where it reads nothing, which its body then never asks for.
         */
        private void room(BodyWriter out, List<Core.Read> captured) {
            if (captured.isEmpty()) {
                out.constant(0);
                return;
            }
            int room = scratch();
            out.constant(captured.size())
                    .call(calls.of(RuntimeAbi.CAPTURES))
                    .localSet(room);
            for (int i = 0; i < captured.size(); i++) {
                out.localGet(room)
                        .constant(i)
                        .localGet(locals.get(captured.get(i).binding()))
                        .call(calls.of(RuntimeAbi.CAPTURE_SET));
            }
            out.localGet(room);
        }

        /**
         * The function a block's body becomes.
         *
         * <p>Written now and not put off, so that the emitter's own state — which local holds
         * which binding — belongs to one function at a time. What is around it is saved and put
         * back, because a block is written in the middle of writing the body it appears in.
         */
        private int blockFunction(Core.Block block, List<Core.Read> captured) {
            int index = fragment.declare(overCells(fragment, 1 + block.params().size()));
            BodyWriter around = out;
            Map<BindingId, Integer> outer = locals;
            Object was = writing;
            // The block is a function of its own, so a call in it is never the declaration around
            // it going back to its top.
            Again goingBack = again;
            again = null;

            out = new BodyWriter(1 + block.params().size(), 0);
            locals = new HashMap<>();
            for (int i = 0; i < block.params().size(); i++) {
                locals.put(block.params().get(i).binding(), 1 + i);
            }
            for (int i = 0; i < captured.size(); i++) {
                int local = out.narrow();
                out.localGet(0).constant(i).call(calls.of(RuntimeAbi.CAPTURE_GET)).localSet(local);
                locals.put(captured.get(i).binding(), local);
            }
            value(out, block.body());
            byte[] body = out.body();

            again = goingBack;
            out = around;
            locals = outer;
            writing = was;
            fragment.write(index, body);
            return index;
        }

        /** Applies a block to arguments, through the slot the block's own value carries. */
        private void apply(BodyWriter out, Core.Apply applied) {
            int closure = scratch();
            value(out, applied.fn());
            out.localSet(closure)
                    .localGet(closure)
                    .load(Cell.PAYLOAD);
            applied.args().forEach(argument -> value(out, argument));
            out.localGet(closure)
                    .load(Cell.SECOND)
                    .callSlot(overCells(fragment, 1 + applied.args().size()));
        }

        /**
         * A call, which is the declaration it reaches with its arguments before it.
         *
         * <p>What it reaches is read off the call rather than worked out from a name: the checker
         * typed it against a declaration and says which, and a spelling this resolved again would
         * be resolving what was resolved already.
         */
        private void call(BodyWriter out, Core.Call call) {
            if (call.fn() instanceof Core.Emitted operation) {
                emitted(out, call, operation);
                return;
            }
            if (call.fn() instanceof Core.Reached.OfKernel intrinsic) {
                kernel(out, call, intrinsic.kernel());
                return;
            }
            if (!(call.fn() instanceof Core.Reached.OfDeclaration declaration)) {
                throw new NotLowered(writing + " reaches " + call.fn()
                        + ", and this backend reaches a helper and a behavior");
            }
            // A helper, a value and a behavior are each a body this program reaches by the name it
            // was declared under, so which of them it is decides nothing here.
            ValueName name = declaration.reaches().declaration();
            Integer index = reached.get(name);
            if (index == null) {
                throw new NotLowered(writing + " reaches " + name
                        + ", which this program declares no body for here");
            }
            for (Core argument : call.args()) {
                value(out, argument);
            }
            out.call(index);
        }

        /** Whether a call is the declaration being written calling itself. */
        private boolean goesBack(Core.Call call) {
            return again != null
                    && call.fn() instanceof Core.Reached.OfDeclaration declaration
                    && declaration.reaches().declaration().equals(again.declaration())
                    && call.args().size() == again.arity();
        }

        /**
         * A call of the declaration to itself, as its answer: every argument is worked out before
         * any parameter is put back, since an argument may read the parameter it replaces, and then
         * the body runs again. Nothing follows that is reached, so what the arm it is in goes on to
         * write is not run.
         */
        private void goBack(BodyWriter out, Core.Call call) {
            for (Core argument : call.args()) {
                value(out, argument);
            }
            for (int i = again.arity() - 1; i >= 0; i--) {
                out.localSet(i);
            }
            out.leave(out.depth() - again.depth());
        }

        /**
         * An operation this compiler minted for a shape a backend lowers whole.
         *
         * <p>A fold that grows a list is one walk with one answer, so it is written as a walk: a
         * builder, an element at a time from where the walk starts, and the step applied to both.
         * What the step adds is added to the builder it was handed, which is why the step answers
         * one — the builder may have had to move to hold what it was given.
         */
        private void emitted(BodyWriter out, Core.Call call, Core.Emitted operation) {
            switch (operation) {
                case GROW_LIST -> {
                    value(out, call.args().get(0));
                    // What a step adds is written `acc ++ [x]`, and where it is written out like
                    // that, each value is added as it is rather than in a list of one made for it.
                    if (unwidened(call.args().get(1)) instanceof Core.ListLit added) {
                        for (Core element : added.elements()) {
                            value(out, element);
                            out.call(calls.of(RuntimeAbi.GROW_ONE));
                        }
                    } else {
                        value(out, call.args().get(1));
                        out.call(calls.of(RuntimeAbi.GROW));
                    }
                }
                case BUILD_LIST -> walk(out, call, RuntimeAbi.BUILDER, RuntimeAbi.SEALED);
                case PUT_MAP -> {
                    // The walk carries the map it is growing first; the operation that grows one
                    // takes it last, after what is being put in it. Nothing else holds that map,
                    // so the entry is put in it where it goes rather than in a copy.
                    value(out, call.args().get(1));
                    value(out, call.args().get(2));
                    value(out, call.args().get(0));
                    out.call(calls.of(RuntimeAbi.MAP_PUT));
                }
                case BUILD_MAP -> walk(out, call, RuntimeAbi.MAP_BUILDER, RuntimeAbi.MAP_SEALED);
                default -> throw new NotLowered(writing + " reaches " + operation
                        + ", which this backend does not write yet");
            }
        }

        /**
         * {@code $build(step, xs, from)}: the walk that grows a collection out of a list.
         *
         * @param start what makes the empty one the walk begins with
         * @param finish what turns what the walk grew into what it answers: what grows is never
         *     the collection itself, since it has room past what it holds
         */
        private void walk(BodyWriter out, Core.Call call, String start, String finish) {
            int step = scratch();
            int over = scratch();
            int at = scratch();
            int held = scratch();
            int builder = scratch();
            int elements = scratch();

            // A step written where the walk is, which is what `List.map(f, xs)` leaves once `f` is
            // written in, is a function this compiler knows: it is called as itself, with what it
            // reads from around it, rather than made a value and reached through the table.
            int direct = -1;
            if (unwidened(call.args().get(0)) instanceof Core.Block block) {
                List<Core.Read> captured = capturedBy(block);
                direct = blockFunction(block, captured);
                room(out, captured);
            } else {
                value(out, call.args().get(0));
            }
            out.localSet(step);
            value(out, call.args().get(1));
            out.localSet(over);
            wide(out, call.args().get(2));
            out.wrap().localSet(at);
            out.localGet(over).call(calls.of(RuntimeAbi.LIST_LENGTH_OF)).localSet(held);
            // Where the elements are is asked once, of the runtime: a list's cell points at them,
            // and a set held as a tree is laid out for the asking.
            out.localGet(over).call(calls.of(RuntimeAbi.LIST_ELEMENTS)).localSet(elements);
            out.constant(shapes.of(call.type())).call(calls.of(start)).localSet(builder);

            out.block().loop()
                    .localGet(at).localGet(held).compares(BodyWriter.Comparison.AT_LEAST).leaveIf(1);
            out.localGet(step);
            if (direct < 0) {
                out.load(Cell.PAYLOAD);
            }
            out.localGet(builder);
            out.localGet(elements).localGet(at).shiftLeft(2).add().load(0);
            if (direct < 0) {
                out.localGet(step).load(Cell.SECOND).callSlot(overCells(fragment, 3));
            } else {
                out.call(direct);
            }
            out.localSet(builder);
            out.localGet(at).constant(1).add().localSet(at).leave(0);
            out.end().end();

            out.localGet(builder);
            out.call(calls.of(finish));
        }

        /**
         * An operation the standard library declares as intrinsic.
         *
         * <p>The arguments go over in the order the library's signature writes them. Where the
         * operation builds a list, the descriptor of what it builds follows: what a `List.reverse`
         * answers is a list of the same type it was handed, and the runtime is told which rather
         * than working it out from a value it may have none of.
         */
        private void kernel(BodyWriter out, Core.Call call, Kernel kernel) {
            if (kernel == Kernel.STRING_MATCHES) {
                recognised(out, call);
                return;
            }
            // A total of exact quotients is an entry of its own, so that the total every program
            // reaches carries no exact arithmetic into one that has none.
            if ((kernel == Kernel.LIST_SUM || kernel == Kernel.LIST_PRODUCT)
                    && call.type() == souther.compiler.types.Type.Prim.RATIONAL) {
                value(out, call.args().get(0));
                out.call(calls.of(kernel == Kernel.LIST_SUM
                        ? RuntimeAbi.RATIONAL_SUM : RuntimeAbi.RATIONAL_PRODUCT));
                return;
            }
            String operation = abiNameOf(kernel);
            var parameters = program.kernel(kernel).signature().parameters();
            for (int i = 0; i < call.args().size(); i++) {
                value(out, call.args().get(i));
                // A way of rounding goes over as its place among the ones the language declares.
                // Which argument that is comes from the operation's own declaration: a value of
                // one of them is typed as the case it is, not as the set it belongs to.
                if (Descriptors.isRoundingMode(parameters.get(i))) {
                    out.constant(shapes.roundingModes()).call(calls.of(RuntimeAbi.CASE_OF));
                }
            }
            if (TAKES_RESULT_DESCRIPTOR.contains(kernel)) {
                out.constant(shapes.of(call.type()));
            }
            if (kernel == Kernel.LIST_SUM || kernel == Kernel.LIST_PRODUCT) {
                // What a total is a total of. It is the same as the list's element, and it is what
                // a total of nothing is too — which is the one a list with nothing in it cannot
                // say, so it is taken from the type rather than from a value.
                out.constant(shapes.of(call.type()));
            }
            if (PLACES_ITS_VALUES.contains(kernel)) {
                placing(out, call);
            }
            TypeSymbol.LanguageCase absent = answeredCase(kernel);
            if (absent != null) {
                out.constant(shapes.ofMember(absent));
            }
            out.call(calls.of(operation));
        }

        /**
         * {@code String.matches}, whose pattern text the checker has already settled.
         *
         * <p>What crosses is the machine that recognises the pattern rather than the pattern
         * itself, so the runtime holds no reader for one. What the pattern says is not decided
         * here: the call carries the text the checker proved, and this only places it as a machine.
         * A settled pattern containing a construct this backend does not lower is refused before
         * the program runs, which is the only place a program that would have been recognised
         * differently can still be declined.
         */
        private void recognised(BodyWriter out, Core.Call call) {
            // The call cannot be built without this settlement, so a different one is the
            // checker's contract broken and not something this backend lacks.
            Core.KernelFact.StringMatches settled = (Core.KernelFact.StringMatches) factOf(call);
            value(out, call.args().get(1));
            out.constant(patterns.of(settled))
                    .call(calls.of(abiNameOf(Kernel.STRING_MATCHES)));
        }

        /** What the checker settled about a kernel's application, beside what it takes. A call
         *  reaching a kernel is one the checker built as such, so any other settlement is its
         *  contract broken. */
        private Core.KernelFact factOf(Core.Call call) {
            return ((Core.CallSettlement.AtKernel) call.settlement()).fact();
        }

        /**
         * What a sort, a max or a min places its values by, as the checker settled it, left as two
         * arguments: how many newtypes each value is opened through, and the descriptor of the
         * order the opened values are placed on. Two, because they are two answers — a
         * {@code data BetaN = Beta} is opened to a case with no order of its own and placed by the
         * sum listing it — and a comparison keeps them apart the same way.
         *
         * <p>Read off {@link Core.KernelFact.OrderingSubject}, whose type is what the values were
         * held to — a {@code sortBy} block's result, not the list's element — rather than
         * re-derived here or read off a value at run time.
         */
        private void placing(BodyWriter out, Core.Call call) {
            // CallElaborator cannot produce one of these applications without this settlement, so
            // a different one here is the checker's contract broken and not a capability this
            // backend lacks — the same distinction `recognised` draws for String.matches's pattern.
            Core.KernelFact.OrderingSubject settled = (Core.KernelFact.OrderingSubject) factOf(call);
            souther.compiler.types.Type as = shapes.orderedAs(settled.type(), settled.ordering());
            out.constant(shapes.namesWornBy(settled.type())).constant(shapes.of(as));
        }

        /**
         * The case a kernel answers with, as the kernel's declaration carries it.
         *
         * <p>The runtime takes one case descriptor, so a kernel declaring more than one is refused
         * here. What the language can say is wider than what this runtime can carry, and the
         * difference is kept in this method.
         */
        private TypeSymbol.LanguageCase answeredCase(Kernel kernel) {
            java.util.Set<TypeSymbol.LanguageCase> declared =
                    program.kernel(kernel).signature().languageCaseMembers();
            return switch (declared.size()) {
                case 0 -> null;
                case 1 -> declared.iterator().next();
                default -> throw new NotLowered(writing + " reaches " + kernel
                        + ", which declares more than one case; this runtime carries one");
            };
        }

        /**
         * The kernels whose runtime call takes an extra operand: the descriptor of what it builds.
         *
         * <p>A collection knows what it holds by the descriptor its cell carries, and one being
         * made has no cell yet, so its runtime function is handed one as an argument rather than
         * reading it off a value it may have none of. Which type that descriptor names is a
         * semantic fact this backend never restates — it is {@code call.type()}, read at the one
         * site below that pushes it. What this set answers instead is a fact of this runtime's own
         * ABI: which operations were built to take that extra operand at all.
         */
        /** The kernels that place values on an order, each taking how many newtypes a value is
         *  opened through and the descriptor of the order, after everything else. */
        private static final Set<Kernel> PLACES_ITS_VALUES = Set.of(
                Kernel.LIST_SORT, Kernel.LIST_SORT_BY, Kernel.LIST_MAX, Kernel.LIST_MIN);

        private static final Set<Kernel> TAKES_RESULT_DESCRIPTOR = Set.of(
                Kernel.STRING_SPLIT, Kernel.STRING_CHARACTERS, Kernel.STRING_CODE_POINTS,
                Kernel.STRING_WORDS, Kernel.STRING_LINES,
                Kernel.LIST_REVERSE, Kernel.LIST_RANGE_INCLUSIVE,
                Kernel.LIST_SORT, Kernel.LIST_SORT_BY,
                Kernel.SET_EMPTY, Kernel.SET_SINGLETON, Kernel.SET_INSERT, Kernel.SET_REMOVE,
                Kernel.SET_UNION, Kernel.SET_INTERSECTION, Kernel.SET_DIFFERENCE,
                Kernel.SET_TO_LIST, Kernel.SET_FROM_LIST,
                Kernel.MAP_EMPTY, Kernel.MAP_KEYS, Kernel.MAP_VALUES, Kernel.MAP_SINGLETON,
                Kernel.MAP_INSERT, Kernel.MAP_REMOVE, Kernel.MAP_TO_LIST, Kernel.MAP_FROM_LIST);

        /**
         * A value of a declared shape, with its fields filled and nothing checked.
         *
         * <p>The list is the order the fields are evaluated in. Which slot each goes in is asked of
         * the shape rather than taken from that order: the two agree in what this compiler is
         * handed today, and one of them is the answer to the question being asked.
         */
        private int constructed(BodyWriter out, Core.Construct made) {
            int record = scratch();
            out.constant(shapes.ofDeclared(made.typeName()))
                    .call(calls.of(RuntimeAbi.RECORD))
                    .localSet(record);
            for (Core.FieldValue field : made.values()) {
                out.localGet(record).constant(shapes.positionOf(made.typeName(), field.field()));
                value(out, field.value());
                out.call(calls.of(RuntimeAbi.RECORD_SET));
            }
            return record;
        }

        /** An {@code if}: the condition as a value, and each branch written the way {@code ways}
         *  writes what the whole is. */
        private void chosen(BodyWriter out, Core.If chosen, Writing ways) {
            int answer = scratch();
            truth(out, chosen.cond());
            out.ifNotZero();
            ways.write(out, chosen.then());
            out.localSet(answer).otherwise();
            ways.write(out, chosen.els());
            out.localSet(answer).end().localGet(answer);
        }

        /** A {@code let}: what is bound as a value, and the body the way {@code body} writes what
         *  the whole is. */
        private void bound(BodyWriter out, Core.LetIn bound, Writing body) {
            int local = scratch();
            value(out, bound.value());
            out.localSet(local);
            locals.put(bound.binder().binding(), local);
            body.write(out, bound.body());
        }

        /**
         * An attempted construction: what must hold of the value decides which way the body goes.
         *
         * <p>The same check the construction would have ended the call on, read as an answer
         * instead. Which departure a failure takes is settled by the clause that failed, and a
         * departure naming no clause takes any — the checker has established that one always
         * matches, so what follows every arm is the end of a call nothing written reaches.
         */
        private void attempt(BodyWriter out, Core.IfConstructed attempted, Writing ways) {
            Core.Construct made = attempted.construct();
            TypeSymbol.AtModule name = made.typeName();
            int record = constructed(out, made);
            int broken = scratch();
            int answer = scratch();
            // A shape with nothing that must hold of it breaks nothing, and has no check to call.
            if (shapes.invariantsOf(name).isEmpty()) {
                out.constant(-1);
            } else {
                out.localGet(record).call(checkOf.applyAsInt(name));
            }
            out.localSet(broken);

            out.localGet(broken).constant(-1).compares(BodyWriter.Comparison.EQUAL).ifNotZero();
            locals.put(attempted.binder().binding(), record);
            ways.write(out, attempted.then());
            out.localSet(answer).otherwise();

            List<ValueShape.Invariant> clauses = shapes.invariantsOf(name);
            int opened = 0;
            for (Core.ElseArm arm : attempted.els()) {
                if (arm.clause().isEmpty()) {
                    continue;
                }
                out.localGet(broken)
                        .constant(placeOf(clauses, arm.clause().get(), name))
                        .compares(BodyWriter.Comparison.EQUAL)
                        .ifNotZero();
                opened++;
                ways.write(out, arm.body());
                out.localSet(answer).otherwise();
            }
            // What is left is the departure naming no clause, which any failure takes. Where there
            // is none the checker has established that a named one always matches, so nothing
            // written reaches what stands here.
            Optional<Core.ElseArm> any = attempted.els().stream()
                    .filter(arm -> arm.clause().isEmpty())
                    .findFirst();
            if (any.isPresent()) {
                ways.write(out, any.get().body());
                out.localSet(answer);
            } else {
                out.constant(WasmFault.BACKEND_INVARIANT_BROKEN.code())
                        .constant(shapes.ofDeclared(name))
                        .constant(0L)
                        .constant(0L)
                        .call(calls.of(RuntimeAbi.ABORT))
                        .unreachable();
            }
            for (int i = 0; i < opened; i++) {
                out.end();
            }
            out.end().localGet(answer);
        }

        /** Which of a shape's clauses goes by a name. */
        private int placeOf(List<ValueShape.Invariant> clauses, String named, Object shape) {
            for (int i = 0; i < clauses.size(); i++) {
                if (clauses.get(i).name().map(named::equals).orElse(false)) {
                    return i;
                }
            }
            throw new NotLowered(shape + " has no clause called " + named);
        }

        /**
         * A match, as one condition per arm over the value it is given.
         *
         * <p>Which arm a value takes is asked of the value: a cell holds the descriptor of the
         * type it was made as, and a sum's cases are its leaves, so the arms are told apart by
         * which leaves each answers for.
         *
         * <p>Where an arm selects on an option, what it tests is whether the option holds
         * something, and what it binds is what the option holds — not the option.
         *
         * <p>The last arm is not tested. The checker settles that one arm answers for every value,
         * so a value no arm before the last answered for is one the last answers for.
         */
        private void match(BodyWriter out, Core.Match chosen, Writing arms) {
            int subject = scratch();
            int answer = scratch();
            value(out, chosen.scrutinee());
            out.localSet(subject);

            List<Core.Case> cases = chosen.cases();
            for (int i = 0; i < cases.size() - 1; i++) {
                Core.Case arm = cases.get(i);
                condition(out, arm, subject);
                out.ifNotZero();
                bind(out, arm, subject);
                arms.write(out, arm.body());
                out.localSet(answer).otherwise();
            }
            Core.Case last = cases.get(cases.size() - 1);
            bind(out, last, subject);
            arms.write(out, last.body());
            out.localSet(answer);
            for (int i = 0; i < cases.size() - 1; i++) {
                out.end();
            }
            out.localGet(answer);
        }

        /** Leaves on the stack whether an arm is the one a value takes. */
        private void condition(BodyWriter out, Core.Case arm, int subject) {
            Optional<ResolvedCase> selected = arm.selectedCase();
            if (selected.isPresent()
                    && selected.get().refinement() instanceof Refinement.OptionPresent) {
                out.localGet(subject).call(calls.of(RuntimeAbi.IS_SOME));
                return;
            }
            if (selected.isPresent()
                    && selected.get().refinement() instanceof Refinement.OptionAbsent) {
                out.localGet(subject).call(calls.of(RuntimeAbi.IS_SOME)).constant(0)
                        .compares(BodyWriter.Comparison.EQUAL);
                return;
            }
            List<TypeSymbol> atoms = arm.caseTypes();
            if (atoms.isEmpty()) {
                throw new NotLowered(writing
                        + " has an arm selecting nothing this backend can tell a value by");
            }
            for (int i = 0; i < atoms.size(); i++) {
                out.localGet(subject)
                        .constant(shapes.ofMember(atoms.get(i)))
                        .call(calls.of(RuntimeAbi.IS));
                if (i > 0) {
                    // Either of them: an or-pattern answers for each of the leaves it names.
                    out.or();
                }
            }
        }

        /** Puts what an arm binds where its body reads it. */
        private void bind(BodyWriter out, Core.Case arm, int subject) {
            if (arm.binder() == null) {
                return;
            }
            int local = scratch();
            boolean present = arm.selectedCase()
                    .map(each -> each.refinement() instanceof Refinement.OptionPresent)
                    .orElse(false);
            out.localGet(subject);
            if (present) {
                out.call(calls.of(RuntimeAbi.HELD));
            }
            out.localSet(local);
            locals.put(arm.binder().binding(), local);
        }

        /**
         * An operator, over the values its operands are.
         *
         * <p>A comparison is answered by where one value is written relative to the other, whatever
         * the two are: that is one question with one answer, and asking it per type would be as
         * many answers as there are types to disagree about.
         *
         * <p>{@code &&} and {@code ||} are the two that decide whether their second operand runs at
         * all, so they are written as a condition rather than as a call taking both.
         */
        private void binary(BodyWriter out, Core.Binary binary) {
            switch (binary.op()) {
                case ADD -> arithmetic(out, binary, RuntimeAbi.ADD,
                        RuntimeAbi.Kernels.DECIMAL_ADD, RuntimeAbi.Kernels.RATIONAL_ADD);
                case SUB -> arithmetic(out, binary, RuntimeAbi.SUBTRACT,
                        RuntimeAbi.Kernels.DECIMAL_SUBTRACT, RuntimeAbi.Kernels.RATIONAL_SUBTRACT);
                case MUL -> arithmetic(out, binary, RuntimeAbi.MULTIPLY,
                        RuntimeAbi.Kernels.DECIMAL_MULTIPLY, RuntimeAbi.Kernels.RATIONAL_MULTIPLY);
                // An exact quotient, over two Ints and over two Decimals alike.
                case DIV -> arithmetic(out, binary, null, null, RuntimeAbi.Kernels.RATIONAL_DIVIDE);
                case CONCAT -> concatenation(out, binary);
                // Decided as one or zero, and answered as whichever of the two cells that is.
                case EQ, NE, LT, LE, GT, GE, AND, OR -> {
                    out.constant(cells.ofBool(true)).constant(cells.ofBool(false));
                    truth(out, binary);
                    out.select();
                }
            }
        }

        /**
         * Leaves whether a {@code Bool} holds on the stack, as one or zero.
         *
         * <p>A condition is asked of what it decides and not of a cell: a comparison is the number
         * its operands stand in, and {@code &&} and {@code ||} are conditions of conditions, so a
         * condition made of them makes no cell. Anything else is a value, and the {@code Bool} it
         * holds is read off its cell.
         */
        private void truth(BodyWriter out, Core expression) {
            switch (expression) {
                case Core.Bool bool -> out.constant(bool.value() ? 1 : 0);
                case Core.Binary binary when comparisonOf(binary.op()) != null ->
                        compared(out, binary, comparisonOf(binary.op()));
                case Core.Binary binary when binary.op() == BinOp.AND || binary.op() == BinOp.OR -> {
                    // The second is asked only where the first has not decided the whole.
                    int answer = scratch();
                    truth(out, binary.left());
                    out.localSet(answer).localGet(answer);
                    if (binary.op() == BinOp.AND) {
                        out.ifNotZero();
                    } else {
                        out.ifZero();
                    }
                    truth(out, binary.right());
                    out.localSet(answer).end().localGet(answer);
                }
                default -> {
                    value(out, expression);
                    out.load(Cell.PAYLOAD);
                }
            }
        }

        /** How a comparison operator stands one operand to the other, or null for any other. */
        private static BodyWriter.Comparison comparisonOf(BinOp op) {
            return switch (op) {
                case EQ -> BodyWriter.Comparison.EQUAL;
                case NE -> BodyWriter.Comparison.UNEQUAL;
                case LT -> BodyWriter.Comparison.LESS;
                case LE -> BodyWriter.Comparison.AT_MOST;
                case GT -> BodyWriter.Comparison.GREATER;
                case GE -> BodyWriter.Comparison.AT_LEAST;
                default -> null;
            };
        }

        /**
         * Leaves an {@code Int}'s number on the stack, as sixty-four bits.
         *
         * <p>Arithmetic over whole numbers is worked out on the numbers, so what it makes in the
         * middle of an expression is never a cell; anything else is a value, whose number is read
         * off its cell.
         */
        private void wide(BodyWriter out, Core expression) {
            switch (expression) {
                case Core.Int number -> out.constant(number.value());
                case Core.Binary binary when worksOutAWholeNumber(binary) -> {
                    wide(out, binary.left());
                    wide(out, binary.right());
                    out.call(calls.of(switch (binary.op()) {
                        case ADD -> RuntimeAbi.INT_SUM;
                        case SUB -> RuntimeAbi.INT_DIFFERENCE;
                        case MUL -> RuntimeAbi.INT_PRODUCT;
                        default -> throw new IllegalStateException(
                                binary.op() + " is not worked out on whole numbers");
                    }));
                }
                // Negating wraps at the one number whose opposite is not one, as the JVM's does.
                case Core.Neg opposite when worksOutAWholeNumber(opposite) -> {
                    out.constant(0L);
                    wide(out, opposite.operand());
                    out.subtractWide();
                }
                default -> {
                    value(out, expression);
                    out.loadWide(Cell.PAYLOAD);
                }
            }
        }

        /** Whether an expression is arithmetic over whole numbers, answering one. */
        private boolean worksOutAWholeNumber(Core expression) {
            return switch (expression) {
                case Core.Binary binary -> switch (binary.op()) {
                    case ADD, SUB, MUL -> isWhole(binary.left()) && isWhole(binary.right())
                            && isWhole(binary);
                    default -> false;
                };
                case Core.Neg opposite -> isWhole(opposite.operand()) && isWhole(opposite);
                default -> false;
            };
        }

        private static boolean isWhole(Core expression) {
            return expression.type() == souther.compiler.types.Type.Prim.INT;
        }

        /** An expression as what it evaluates, with any widening of it set aside. */
        private static Core unwidened(Core expression) {
            Core held = expression;
            while (held instanceof Core.Widen widened) {
                held = widened.value();
            }
            return held;
        }

        /**
         * {@code ++}, joined by what its operands are: two strings as {@code String.append} joins
         * them and two lists as {@code List.append} does. They are two runtime operations because
         * the cells they join do not share a layout, so a type that is neither is not given either
         * of them: one reaching the string join was how two lists came to answer the left one.
         */
        private void concatenation(BodyWriter out, Core.Binary binary) {
            souther.compiler.types.Type operand = binary.left().type();
            if (operand == souther.compiler.types.Type.Prim.STRING) {
                value(out, binary.left());
                value(out, binary.right());
                out.call(calls.of(RuntimeAbi.Kernels.STRING_APPEND));
            } else if (operand instanceof souther.compiler.types.Type.ListOf) {
                value(out, binary.left());
                value(out, binary.right());
                out.constant(shapes.of(binary.type())).call(calls.of(RuntimeAbi.LIST_APPEND));
            } else {
                throw new NotLowered(writing + " joins a " + operand + " with ++");
            }
        }

        /**
         * Leaves whether the two operands of a comparison stand that way, as one or zero.
         *
         * <p>Compared as what the checker read the pair as, which neither operand's type says
         * where the two differ: a literal beside a newtype is read as the newtype, a case beside
         * the sum listing it as that sum, an Int beside a Rational at its exact value. Taken from
         * one side, the descriptor reads the other side's cell as what it is not, and the answer
         * turns on which side was written first.
         *
         * <p>What the operands are read as and what orders them are two answers, and the checker
         * gives both. The reading says how each operand is taken and how far it is opened ({@link
         * #namesOpened}), and which type sameness is asked in. The order of {@code <} and the
         * others is the {@link Core.OrderingBasis}, which no reading says: cases of one sum held as
         * a union of them read as they stand, and the union keeps its cases in the order of their
         * names while the sum orders them as it declares them.
         */
        private void compared(BodyWriter out, Core.Binary binary, BodyWriter.Comparison how) {
            Core left = binary.left();
            Core right = binary.right();
            Core.BinaryReading reading = binary.reading();
            if (reading instanceof Core.BinaryReading.ExactNumbers) {
                exactly(out, left);
                exactly(out, right);
                ordered(out, souther.compiler.types.Type.Prim.RATIONAL, how);
                return;
            }
            souther.compiler.types.Type in = binary.op().ordersItsOperands()
                    ? shapes.madeOf(binary.ordering().orElseThrow(() -> new IllegalStateException(
                            writing + " orders a " + left.type() + " and a " + right.type()
                                    + " by nothing the checker settled")).type())
                    : switch (reading) {
                        case Core.BinaryReading.In read -> read.type();
                        case Core.BinaryReading.Opened read -> read.base();
                        case Core.BinaryReading.AsTheyStand _ -> shapes.madeOf(left.type());
                        case Core.BinaryReading.ExactNumbers _ -> throw new IllegalStateException(
                                writing + " reads two exact values as something else");
                    };
            // Two whole numbers stand as their numbers do, which is one instruction.
            if (in == souther.compiler.types.Type.Prim.INT) {
                wholeOpened(out, left, reading);
                wholeOpened(out, right, reading);
                out.comparesWide(how);
                return;
            }
            opened(out, left, reading);
            opened(out, right, reading);
            ordered(out, in, how);
        }

        /**
         * How many newtypes one operand of a comparison is opened through, which its reading says
         * and its type does not.
         *
         * <p>Read as they stand, the two are one type and a newtype compares and orders as what it
         * wraps, so each is opened through every name it wears. Opened beside a literal, the
         * newtype's side is, and the literal is what it wraps already. Read in a type, neither
         * is: a {@code Code} beside the {@code Key} listing it is that {@code Key}, and a value
         * beside one that states nothing about its own type is a value of the reading as it
         * stands.
         */
        private int namesOpened(Core operand, Core.BinaryReading reading) {
            return switch (reading) {
                case Core.BinaryReading.AsTheyStand _ -> shapes.namesWornBy(operand.type());
                case Core.BinaryReading.Opened read -> operand.type().equals(read.newtype())
                        ? shapes.namesWornBy(operand.type()) : 0;
                case Core.BinaryReading.In _, Core.BinaryReading.ExactNumbers _ -> 0;
            };
        }

        /** Compares the two values on the stack as values of {@code in}. */
        private void ordered(BodyWriter out, souther.compiler.types.Type in,
                BodyWriter.Comparison how) {
            out.constant(shapes.of(in))
                    .call(calls.of(RuntimeAbi.COMPARE))
                    .constant(0)
                    .compares(how);
        }

        /** Leaves an operand opened as far as the pair's reading says ({@link #namesOpened}). */
        private void opened(BodyWriter out, Core operand, Core.BinaryReading reading) {
            value(out, operand);
            for (int i = namesOpened(operand, reading); i > 0; i--) {
                out.constant(0).call(calls.of(RuntimeAbi.RECORD_GET));
            }
        }

        /** Leaves an operand the pair's reading opens to a whole number as that number. */
        private void wholeOpened(BodyWriter out, Core operand, Core.BinaryReading reading) {
            if (operand.type() == souther.compiler.types.Type.Prim.INT) {
                wide(out, operand);
                return;
            }
            opened(out, operand, reading);
            out.loadWide(Cell.PAYLOAD);
        }

        /**
         * An arithmetic operator, worked out over what it answers.
         *
         * <p>Decided by the answer and not by an operand, because the two part company: a quotient
         * of two Ints is a Rational while its operands stay Ints, and an Int beside a Rational is
         * read at its exact value. An operator answering a Rational is exact arithmetic over both
         * operands read that way, whatever each was; one answering a whole number or an amount
         * was handed two of what it answers, newtype arithmetic having been opened to the numbers
         * it wraps before it reached here.
         *
         * @param whole the runtime's operator on two whole numbers, or null where the operator
         *     answers none
         * @param amount the runtime's operator on two amounts, or null where it answers none
         * @param exact the runtime's operator on two exact quotients
         */
        private void arithmetic(BodyWriter out, Core.Binary binary, String whole, String amount,
                String exact) {
            souther.compiler.types.Type answer = binary.type();
            if (answer == souther.compiler.types.Type.Prim.RATIONAL) {
                exactly(out, binary.left());
                exactly(out, binary.right());
                out.call(calls.of(exact));
                return;
            }
            if (worksOutAWholeNumber(binary)) {
                wide(out, binary);
                out.call(calls.of(RuntimeAbi.INT));
                return;
            }
            String operation = answer == souther.compiler.types.Type.Prim.DECIMAL ? amount : whole;
            if (operation == null) {
                // The checker answers every `/` with a Rational, so a quotient of another type is
                // its contract broken and not something this backend lacks.
                throw new IllegalStateException(writing + " answers " + binary.op()
                        + " with a " + answer + ", which the operator does not answer");
            }
            value(out, binary.left());
            value(out, binary.right());
            out.call(calls.of(operation));
        }

        /**
         * Leaves an operand read at its exact value: a Rational as it is, and an Int or a Decimal
         * as the Rational it is. A reading of the operator and not a conversion the language
         * offers, so it is written here, where the operator is, and nowhere a value is placed.
         */
        private void exactly(BodyWriter out, Core operand) {
            value(out, operand);
            souther.compiler.types.Type type = operand.type();
            if (type == souther.compiler.types.Type.Prim.INT) {
                out.call(calls.of(RuntimeAbi.Kernels.RATIONAL_FROM_INT));
            } else if (type == souther.compiler.types.Type.Prim.DECIMAL) {
                out.call(calls.of(RuntimeAbi.Kernels.RATIONAL_FROM_DECIMAL));
            } else if (type != souther.compiler.types.Type.Prim.RATIONAL) {
                // Only a number has an exact value, and the checker reads nothing else as one.
                throw new IllegalStateException(writing + " reads a " + type
                        + " at its exact value, which only a number has");
            }
        }

        /**
         * The shape an expression's type names.
         *
         * <p>Asked of the type the checker settled rather than of the value at run time: which
         * field a name is depends on the shape, and a shape read off the value would be a shape
         * this compiler had not checked the read against.
         */
        private TypeSymbol.AtModule shapeOf(Core expression) {
            if (expression.type() instanceof souther.compiler.types.Type.Ref reference
                    && reference.name() instanceof TypeSymbol.AtModule named) {
                return named;
            }
            throw new NotLowered(writing + " reads a field off a "
                    + expression.type() + ", and this backend reads one off a shape");
        }

        /** A local nothing else is using, for a value that outlives one instruction. */
        private int scratch() {
            return out.narrow();
        }

        /**
         * The one {@link AbortKind} {@code site} can end a run without a value for.
         *
         * <p>Read off {@link CheckedProgram#abortsAt}, not decided here: this backend picks how an
         * {@link AbortKind} is represented on the wasm ABI ({@link WasmAbortMapping}), never which
         * one a site aborts for — that is the language's own answer, and a second reading of
         * {@code Core}'s shape to re-derive it is exactly what issue #23 exists to end.
         *
         * @throws IllegalStateException where the program answers with other than exactly one
         *     {@link AbortKind} for {@code site} — every call site this method is used from emits
         *     one unconditional abort and expects the program to have settled on the one reason
         *     that abort is for
         */
        private AbortKind onlyKindOf(Core site) {
            AbortSet reasons = program.abortsAt(site);
            if (reasons.kinds().size() != 1) {
                throw new IllegalStateException(
                        "this backend emits one abort for " + site + ", which the program answers "
                                + reasons.kinds().size() + " reasons for: " + reasons);
            }
            return reasons.kinds().iterator().next();
        }
    }
}