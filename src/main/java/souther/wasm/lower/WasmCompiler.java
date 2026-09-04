package souther.wasm.lower;

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
import souther.compiler.core.Composition;
import souther.compiler.core.Core;
import souther.compiler.core.Kernel;
import souther.compiler.core.ValueShape;
import souther.compiler.program.CheckedBehavior;
import souther.compiler.program.CheckedHelper;
import souther.compiler.program.CheckedImplementation;
import souther.compiler.program.CheckedModule;
import souther.compiler.program.CheckedProgram;
import souther.compiler.types.BinOp;
import souther.compiler.types.BindingId;
import souther.compiler.types.Refinement;
import souther.compiler.types.ResolvedCase;
import souther.compiler.types.TypeSymbol;
import souther.compiler.types.ValueName;
import souther.wasm.abi.AbortReason;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.Type;
import souther.wasm.link.Component;
import souther.wasm.link.LinkPlan;
import souther.wasm.link.Linker;
import souther.wasm.link.WasmFragment;

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
        return Component.around(written(program, runtime, true), offered(program));
    }

    /**
     * What a program offers, as {@link Component} and {@link WitText} are both given it.
     *
     * <p>A behavior supplied from outside is refused here rather than where a component is built,
     * because it is not offered either way: what it sends out is a call in this module's own
     * memory, which is not a thing a component carries, and writing it down as offered would say
     * otherwise.
     *
     * @param program what a Souther compile checked
     * @return every behavior's core export name, by the module that declares it
     */
    public static Map<String, Map<String, String>> offered(CheckedProgram program) {
        Map<String, Map<String, String>> behaviors = new LinkedHashMap<>();
        for (CheckedModule module : program.modules()) {
            Map<String, String> named = new LinkedHashMap<>();
            for (CheckedBehavior behavior : module.behaviors()) {
                if (behavior.implementation() instanceof CheckedImplementation.Injected) {
                    // What an injected behavior sends out is a call in this module's own memory,
                    // which is not a thing a component carries. A component that let one through
                    // would end the call where it was reached rather than where it was built.
                    throw new NotLowered(behavior.name()
                            + " is supplied from outside, and a component has no way to reach out"
                            + " for it yet");
                }
                named.put(behavior.name().name(), exportName(behavior.name()));
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
        List<Composed> composed = new ArrayList<>();
        for (CheckedModule module : program.modules()) {
            for (CheckedHelper helper : module.helpers()) {
                int index = fragment.declare(overCells(fragment, helper.parameters().size()));
                reached.put(helper.declares(), index);
                written.add(new Written(index, helper.parameters().stream()
                        .map(CheckedHelper.Parameter::binder).toList(), helper.body(),
                        helper.declares()));
            }
            for (CheckedBehavior behavior : module.behaviors()) {
                int arity = behavior.signature().takes().size();
                int index = fragment.declare(overCells(fragment, arity));
                reached.put(behavior.name(), index);
                if (behavior.implementation() instanceof CheckedImplementation.Injected) {
                    injected.add(new Crossing(index, behavior, injected.size()));
                    continue;
                }
                if (behavior.implementation() instanceof CheckedImplementation.Composed held) {
                    composed.add(new Composed(index, behavior, held.composition()));
                    continue;
                }
                Body body = bodyOf(behavior);
                written.add(new Written(index, body.parameters(), body.body(), behavior.name()));
            }
        }

        Emitter emitter = new Emitter(fragment, calls, shapes, reached);
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
                byte[] wrapper = emitter.crossing(behavior, reached.get(behavior.name()));
                fragment.export(exportName(behavior.name()), fragment.define(stringToString, wrapper));
            }
        }

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
            liftable(fragment, calls, program);
        }
        return Linker.link(fragment);
    }

    /**
     * The functions a component's lift calls, which a plain core module does not carry.
     *
     * <p>One per behavior, taking the argument string and answering where the answer's own two
     * words are, because the canonical ABI reads a string result out of memory rather than off the
     * stack. And one post-return for all of them: everything a call made is the arena, so what
     * each owes back is the same thing.
     */
    private static void liftable(WasmFragment fragment, Runtime calls, CheckedProgram program) {
        int overStrings = fragment.functionType(
                List.of(Type.I32, Type.I32), List.of(Type.I32));
        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
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
     * <p>The number is what the host is told: an ordinal of this build, which is enough for a host
     * holding the table this compiler also writes. Not a name — a name would have to travel as
     * bytes on every call for a number the host looks up once.
     */
    private record Crossing(int index, CheckedBehavior behavior, int ordinal) {
    }

    /** A behavior written as stages: where its function goes, which one it is, and the stages. */
    private record Composed(int index, CheckedBehavior behavior, Composition composition) {
    }

    /** One function to write: where it goes, what it binds, and what it answers. */
    private record Written(int index, List<Core.Binder> parameters, Core body, ValueName declares) {
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
            case CheckedImplementation.ImplementedElsewhere ignored -> throw new NotLowered(
                    behavior.name() + " is implemented by another build, and this backend links one program");
        };
    }

    /** The name a caller reaches a behavior by. */
    public static String exportName(ValueName.Behavior behavior) {
        return behavior.module() + "." + behavior.name();
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
        case INT_DIVIDE -> RuntimeAbi.Kernels.INT_DIVIDE;
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
        case DATE_YEAR, DATE_MONTH, DATE_DAY -> RuntimeAbi.Kernels.DATE_PART;
        case DATE_FROM_PARTS -> RuntimeAbi.Kernels.DATE_FROM_PARTS;
        case TIME_FROM_PARTS -> RuntimeAbi.Kernels.TIME_FROM_PARTS;
        case TIME_HOUR, TIME_MINUTE, TIME_SECOND -> RuntimeAbi.Kernels.TIME_PART;
        case DATETIME_ADD_MINUTES, DATETIME_ADD_HOURS -> RuntimeAbi.Kernels.DATETIME_ADD;
        case DATETIME_MINUTES_BETWEEN -> RuntimeAbi.Kernels.DATETIME_MINUTES_BETWEEN;
        case DATETIME_TO_DATE -> RuntimeAbi.Kernels.DATETIME_TO_DATE;
        case DATETIME_TO_TIME -> RuntimeAbi.Kernels.DATETIME_TO_TIME;
        case DATETIME_FROM_DATE_AND_TIME -> RuntimeAbi.Kernels.DATETIME_FROM_PARTS;
        case LIST_SORT -> RuntimeAbi.Kernels.LIST_SORT;
        case LIST_SORT_BY -> RuntimeAbi.Kernels.LIST_SORT_BY;
        case LIST_MAX, LIST_MIN -> RuntimeAbi.Kernels.LIST_FURTHEST;
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

        private final WasmFragment fragment;
        private final Runtime calls;
        private final Descriptors shapes;
        private final Map<ValueName, Integer> reached;

        private BodyWriter out;
        private Map<BindingId, Integer> locals;
        private Object writing;

        Emitter(WasmFragment fragment, Runtime calls, Descriptors shapes,
                Map<ValueName, Integer> reached) {
            this.fragment = fragment;
            this.calls = calls;
            this.shapes = shapes;
            this.reached = reached;
        }

        /** A declaration's own function: its parameters as cells, its answer as one. */
        byte[] overValues(Written written) {
            out = new BodyWriter(written.parameters().size(), 0);
            locals = new HashMap<>();
            writing = written.declares();
            for (int i = 0; i < written.parameters().size(); i++) {
                locals.put(written.parameters().get(i).binding(), i);
            }
            value(out, written.body());
            return out.body();
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
                value(out, invariants.get(i).condition());
                out.call(calls.of(RuntimeAbi.BOOL_VALUE)).ifZero().constant(i).localSet(answer).end();
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
                        .constant(fragment.place(path))
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

        /** The pointer the caller's JSON is at. */
        private static final int LOCAL_INPUT_POINTER = 0;
        /** How long the caller's JSON is. */
        private static final int LOCAL_INPUT_LENGTH = 1;

        /** Leaves the value of an expression on the stack, as the cell it is. */
        private void value(BodyWriter out, Core expression) {
            switch (expression) {
                case Core.Int number -> out.constant(number.value()).call(calls.of(RuntimeAbi.INT));
                case Core.Bool bool -> out.constant(bool.value() ? 1 : 0).call(calls.of(RuntimeAbi.BOOL));
                case Core.Str text -> {
                    byte[] utf8 = text.value().getBytes(StandardCharsets.UTF_8);
                    out.constant(fragment.place(utf8))
                            .constant(utf8.length)
                            .call(calls.of(RuntimeAbi.STRING));
                }
                case Core.Decimal amount -> {
                    // The text rather than the digits and the scale, because that is the one form
                    // both sides already agree on how to read, and what was written is what a
                    // scale is: a thousand written to two places carries two.
                    byte[] written = amount.value().toPlainString()
                            .getBytes(StandardCharsets.UTF_8);
                    out.constant(fragment.place(written))
                            .constant(written.length)
                            .call(calls.of(RuntimeAbi.DECIMAL_WRITTEN));
                }
                case Core.Temporal written -> {
                    byte[] utf8 = written.text().getBytes(StandardCharsets.UTF_8);
                    out.constant(fragment.place(utf8))
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
                case Core.OptionNone ignored -> out.call(calls.of(RuntimeAbi.NONE));
                case Core.UnitValue only -> out.constant(shapes.ofMember(only.data()))
                        .call(calls.of(RuntimeAbi.UNIT));
                case Core.Construct made -> {
                    int record = constructed(out, made);
                    // Construction re-checks what must hold. Here the value is the body's own, so
                    // a violation is a model bug rather than something a caller wrote, and it ends
                    // the call: there is no case for it and no value to answer with.
                    if (!shapes.invariantsOf(made.typeName()).isEmpty()) {
                        int broken = scratch();
                        out.localGet(record)
                                .constant(shapes.ofDeclared(made.typeName()))
                                .call(calls.of(RuntimeAbi.CHECK_INVARIANTS))
                                .localSet(broken)
                                .localGet(broken)
                                .constant(-1)
                                .compares(BodyWriter.Comparison.UNEQUAL)
                                .ifNotZero()
                                .constant(AbortReason.INVARIANT_VIOLATION.code())
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
                    out.constant(AbortReason.NOTHING_TO_ANSWER_WITH.code())
                            .constant(0)
                            .constant((long) fragment.place(why))
                            .constant((long) why.length)
                            .call(calls.of(RuntimeAbi.ABORT))
                            .unreachable();
                }
                case Core.IfConstructed attempted -> attempt(out, attempted);
                case Core.FieldAccess read -> {
                    value(out, read.target());
                    TypeSymbol.AtModule shape = shapeOf(read.target());
                    if (shapes.settlesWhereAFieldLies(shape)) {
                        out.constant(shapes.positionOf(shape, read.field()))
                                .call(calls.of(RuntimeAbi.RECORD_GET));
                    } else {
                        // Read off a set of alternatives, so the value says where the field is.
                        byte[] named = read.field().getBytes(StandardCharsets.UTF_8);
                        out.constant(fragment.place(named))
                                .constant(named.length)
                                .call(calls.of(RuntimeAbi.RECORD_NAMED));
                    }
                }
                case Core.Neg opposite -> {
                    boolean amount = opposite.operand().type()
                            == souther.compiler.types.Type.Prim.DECIMAL;
                    value(out, opposite.operand());
                    out.call(calls.of(amount
                            ? RuntimeAbi.Kernels.DECIMAL_NEGATE : RuntimeAbi.NEGATE));
                }
                case Core.Binary binary -> binary(out, binary);
                case Core.If chosen -> {
                    int answer = scratch();
                    value(out, chosen.cond());
                    out.call(calls.of(RuntimeAbi.BOOL_VALUE)).ifNotZero();
                    value(out, chosen.then());
                    out.localSet(answer).otherwise();
                    value(out, chosen.els());
                    out.localSet(answer).end().localGet(answer);
                }
                case Core.LetIn bound -> {
                    int local = scratch();
                    value(out, bound.value());
                    out.localSet(local);
                    locals.put(bound.binder().binding(), local);
                    value(out, bound.body());
                }
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
                case Core.Match chosen -> match(out, chosen);
                case Core.Block block -> closure(out, block);
                case Core.Apply applied -> apply(out, applied);
                case Core.Call call -> call(out, call);
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
            List<BindingId> captured = FreeReads.of(block).stream()
                    .filter(locals::containsKey)
                    .toList();
            int slot = fragment.slot(blockFunction(block, captured));
            int room = scratch();
            out.constant(slot)
                    .constant(shapes.of(anyList()))
                    .constant(captured.size())
                    .call(calls.of(RuntimeAbi.LIST))
                    .localSet(room);
            for (int i = 0; i < captured.size(); i++) {
                out.localGet(room)
                        .constant(i)
                        .localGet(locals.get(captured.get(i)))
                        .call(calls.of(RuntimeAbi.LIST_SET));
            }
            out.localGet(room).call(calls.of(RuntimeAbi.CLOSURE));
        }

        /**
         * The function a block's body becomes.
         *
         * <p>Written now and not put off, so that the emitter's own state — which local holds
         * which binding — belongs to one function at a time. What is around it is saved and put
         * back, because a block is written in the middle of writing the body it appears in.
         */
        private int blockFunction(Core.Block block, List<BindingId> captured) {
            int index = fragment.declare(overCells(fragment, 1 + block.params().size()));
            BodyWriter around = out;
            Map<BindingId, Integer> outer = locals;
            Object was = writing;

            out = new BodyWriter(1 + block.params().size(), 0);
            locals = new HashMap<>();
            for (int i = 0; i < block.params().size(); i++) {
                locals.put(block.params().get(i).binding(), 1 + i);
            }
            for (int i = 0; i < captured.size(); i++) {
                int local = out.narrow();
                out.localGet(0).constant(i).call(calls.of(RuntimeAbi.LIST_GET)).localSet(local);
                locals.put(captured.get(i), local);
            }
            value(out, block.body());
            byte[] body = out.body();

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
                    .call(calls.of(RuntimeAbi.CLOSURE_CAPTURED));
            applied.args().forEach(argument -> value(out, argument));
            out.localGet(closure)
                    .call(calls.of(RuntimeAbi.CLOSURE_SLOT))
                    .callSlot(overCells(fragment, 1 + applied.args().size()));
        }

        /** A list of values, whatever they are: what a closure carries what it read in. */
        private souther.compiler.types.Type anyList() {
            return new souther.compiler.types.Type.ListOf(souther.compiler.types.Type.Prim.INT);
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
            ValueName name = switch (declaration.reaches()) {
                case Core.Reaches.AHelper helper -> helper.declaration();
                case Core.Reaches.ABehavior reaches -> reaches.behavior();
            };
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
                    value(out, call.args().get(1));
                    out.call(calls.of(RuntimeAbi.GROW));
                }
                case BUILD_LIST -> walk(out, call, RuntimeAbi.BUILDER, RuntimeAbi.SEALED);
                case PUT_MAP -> {
                    // The walk carries the map it is growing first; the operation that grows one
                    // takes it last, after what is being put in it.
                    value(out, call.args().get(1));
                    value(out, call.args().get(2));
                    value(out, call.args().get(0));
                    out.constant(shapes.of(call.type())).call(calls.of(RuntimeAbi.Kernels.MAP_INSERT));
                }
                case BUILD_MAP -> walk(out, call, RuntimeAbi.Kernels.MAP_EMPTY, null);
                default -> throw new NotLowered(writing + " reaches " + operation
                        + ", which this backend does not write yet");
            }
        }

        /**
         * {@code $build(step, xs, from)}: the walk that grows a collection out of a list.
         *
         * @param start what makes the empty one the walk begins with
         * @param finish what turns what the walk grew into what it answers, or null where the walk
         *     grew the answer itself
         */
        private void walk(BodyWriter out, Core.Call call, String start, String finish) {
            int step = scratch();
            int over = scratch();
            int at = scratch();
            int held = scratch();
            int builder = scratch();

            value(out, call.args().get(0));
            out.localSet(step);
            value(out, call.args().get(1));
            out.localSet(over);
            value(out, call.args().get(2));
            out.call(calls.of(RuntimeAbi.INT_VALUE)).wrap().localSet(at);
            out.localGet(over).call(calls.of(RuntimeAbi.LIST_LENGTH_OF)).localSet(held);
            out.constant(shapes.of(call.type())).call(calls.of(start)).localSet(builder);

            out.block().loop()
                    .localGet(at).localGet(held).compares(BodyWriter.Comparison.AT_LEAST).leaveIf(1);
            out.localGet(step).call(calls.of(RuntimeAbi.CLOSURE_CAPTURED))
                    .localGet(builder)
                    .localGet(over).localGet(at).call(calls.of(RuntimeAbi.LIST_GET))
                    .localGet(step).call(calls.of(RuntimeAbi.CLOSURE_SLOT))
                    .callSlot(overCells(fragment, 3))
                    .localSet(builder);
            out.localGet(at).constant(1).add().localSet(at).leave(0);
            out.end().end();

            out.localGet(builder);
            if (finish != null) {
                out.call(calls.of(finish));
            }
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
            String operation = abiNameOf(kernel);
            Integer rounds = TAKES_A_MODE.get(kernel);
            for (int i = 0; i < call.args().size(); i++) {
                value(out, call.args().get(i));
                // A way of rounding goes over as its place among the ones the language declares.
                // Which argument that is comes from the operation's own declaration: a value of
                // one of them is typed as the case it is, not as the set it belongs to.
                if (rounds != null && rounds == i) {
                    out.constant(shapes.roundingModes()).call(calls.of(RuntimeAbi.CASE_OF));
                }
            }
            if (BUILDS_A_LIST.contains(kernel)) {
                out.constant(shapes.of(call.type()));
            }
            if (kernel == Kernel.LIST_SORT_BY) {
                // Sorting by what a block answers wants the type of what it answers, which is the
                // block's own result and not the list's element.
                out.constant(shapes.of(keyType(call)));
            }
            if (kernel == Kernel.LIST_MAX || kernel == Kernel.LIST_MIN) {
                out.constant(kernel == Kernel.LIST_MAX ? 1 : 0);
            }
            Integer which = PART_OF.get(kernel);
            if (which != null) {
                out.constant(which);
            }
            Integer each = SECONDS_OF.get(kernel);
            if (each != null) {
                out.constant(each);
            }
            String absent = ANSWERS_A_CASE.get(kernel);
            if (absent != null) {
                out.constant(shapes.ofMember(caseNamed(call.type(), absent)));
            }
            out.call(calls.of(operation));
        }

        /**
         * {@code String.matches}, whose pattern is a literal and so is read here.
         *
         * <p>What crosses is the machine that recognises the pattern rather than the pattern
         * itself, so the runtime holds no reader for one. A pattern written in a way this backend
         * does not read is refused where it is written, which is the only place a program that
         * would have been recognised differently can still be declined.
         */
        private void recognised(BodyWriter out, Core.Call call) {
            value(out, call.args().get(1));
            out.constant(Patterns.place(fragment, spelt(call.args().get(0))))
                    .call(calls.of(abiNameOf(Kernel.STRING_MATCHES)));
        }

        /**
         * The text an expression comes to, for a pattern that has to be settled where it is
         * written.
         *
         * <p>Written out is not the same as written in one piece. A pattern shared between several
         * types is named once and joined onto what tells them apart, and what reaches here is the
         * joining rather than the text — so the joining is done here, which is where the language
         * says it can be.
         */
        private String spelt(Core expression) {
            return switch (expression) {
                case Core.Str written -> written.value();
                case Core.Binary joined when joined.op() == BinOp.CONCAT ->
                        spelt(joined.left()) + spelt(joined.right());
                default -> throw new NotLowered(writing
                        + " matches against a pattern this backend cannot settle where it stands");
            };
        }

        /**
         * The kernels that answer either a value or a named case, and which case each names.
         *
         * <p>Written down because the library's own signature writes it: {@code Int.divide} answers
         * {@code Int | DivisionByZero} and the case is that one. Reading it off the result type as
         * "the member that is not the value" would be working out what the declaration already
         * says, and would say something else the day a kernel answers two cases.
         */
        private static final Map<Kernel, String> ANSWERS_A_CASE = Map.of(
                Kernel.INT_DIVIDE, "DivisionByZero",
                Kernel.INT_TRUNCATING_REMAINDER, "DivisionByZero",
                Kernel.DECIMAL_DIVIDE, "DivisionByZero",
                Kernel.DATE_FROM_PARTS, "NotADate",
                Kernel.TIME_FROM_PARTS, "NotATime",
                Kernel.STRING_TO_INT, "NotANumber",
                Kernel.STRING_TO_DECIMAL, "NotANumber");

        /** What a sort's key answers, which is what its order is asked of. */
        private souther.compiler.types.Type keyType(Core.Call call) {
            if (call.args().get(0).type() instanceof souther.compiler.types.Type.FnOf key) {
                return key.result();
            }
            throw new NotLowered(writing + " sorts by something that is not written as a function");
        }

        /**
         * The operations that read one part of a day or a time, and which part each reads.
         *
         * <p>One operation with a number saying which, because reading a year and reading a month
         * are one walk over what a day is and differ in the last step alone.
         */
        private static final Map<Kernel, Integer> PART_OF = Map.of(
                Kernel.DATE_YEAR, 0, Kernel.DATE_MONTH, 1, Kernel.DATE_DAY, 2,
                Kernel.TIME_HOUR, 0, Kernel.TIME_MINUTE, 1, Kernel.TIME_SECOND, 2);

        /** The operations that move a moment, and how many seconds each of their steps is. */
        private static final Map<Kernel, Integer> SECONDS_OF = Map.of(
                Kernel.DATETIME_ADD_MINUTES, 60, Kernel.DATETIME_ADD_HOURS, 3600);

        /** The operations told a way of rounding, and which of their arguments says it. */
        private static final Map<Kernel, Integer> TAKES_A_MODE = Map.of(
                Kernel.DECIMAL_TO_INT, 0,
                Kernel.DECIMAL_ROUND, 1,
                Kernel.DECIMAL_DIVIDE, 3);

        /** The alternative of a set that goes by a name. */
        private static TypeSymbol caseNamed(souther.compiler.types.Type answered, String name) {
            if (answered instanceof souther.compiler.types.Type.Union alternatives) {
                for (TypeSymbol member : alternatives.members()) {
                    if (member.name().equals(name)) {
                        return member;
                    }
                }
            }
            throw new NotLowered("a kernel answering " + name + " was typed as " + answered);
        }

        /**
         * The kernels told what they build.
         *
         * <p>A collection knows what it holds by the descriptor its cell carries, and one being
         * made has no cell yet. So an operation that makes one is handed the type it is making,
         * which the declaration answered and the values it was given may not have an example of.
         */
        private static final Set<Kernel> BUILDS_A_LIST = Set.of(
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

        /**
         * An attempted construction: what must hold of the value decides which way the body goes.
         *
         * <p>The same check the construction would have ended the call on, read as an answer
         * instead. Which departure a failure takes is settled by the clause that failed, and a
         * departure naming no clause takes any — the checker has established that one always
         * matches, so what follows every arm is the end of a call nothing written reaches.
         */
        private void attempt(BodyWriter out, Core.IfConstructed attempted) {
            Core.Construct made = attempted.construct();
            TypeSymbol.AtModule name = made.typeName();
            int record = constructed(out, made);
            int broken = scratch();
            int answer = scratch();
            out.localGet(record)
                    .constant(shapes.ofDeclared(name))
                    .call(calls.of(RuntimeAbi.CHECK_INVARIANTS))
                    .localSet(broken);

            out.localGet(broken).constant(-1).compares(BodyWriter.Comparison.EQUAL).ifNotZero();
            locals.put(attempted.binder().binding(), record);
            value(out, attempted.then());
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
                value(out, arm.body());
                out.localSet(answer).otherwise();
            }
            // What is left is the departure naming no clause, which any failure takes. Where there
            // is none the checker has established that a named one always matches, so nothing
            // written reaches what stands here.
            Optional<Core.ElseArm> any = attempted.els().stream()
                    .filter(arm -> arm.clause().isEmpty())
                    .findFirst();
            if (any.isPresent()) {
                value(out, any.get().body());
                out.localSet(answer);
            } else {
                out.constant(AbortReason.NO_ARM.code())
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
         */
        private void match(BodyWriter out, Core.Match chosen) {
            int subject = scratch();
            int answer = scratch();
            value(out, chosen.scrutinee());
            out.localSet(subject);

            int opened = 0;
            for (Core.Case arm : chosen.cases()) {
                condition(out, arm, subject);
                out.ifNotZero();
                bind(out, arm, subject);
                value(out, arm.body());
                out.localSet(answer).otherwise();
                opened++;
            }
            // The checker settles that one arm answers, so nothing written reaches this. What it
            // stands for is this backend having tested for the wrong thing.
            out.constant(AbortReason.NO_ARM.code())
                    .constant(0)
                    .constant(0L)
                    .constant(0L)
                    .call(calls.of(RuntimeAbi.ABORT))
                    .unreachable();
            for (int i = 0; i < opened; i++) {
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
                        RuntimeAbi.Kernels.DECIMAL_ADD);
                case SUB -> arithmetic(out, binary, RuntimeAbi.SUBTRACT,
                        RuntimeAbi.Kernels.DECIMAL_SUBTRACT);
                case MUL -> arithmetic(out, binary, RuntimeAbi.MULTIPLY,
                        RuntimeAbi.Kernels.DECIMAL_MULTIPLY);
                case DIV -> arithmetic(out, binary, RuntimeAbi.DIVIDE,
                        RuntimeAbi.Kernels.DECIMAL_DIVIDE_BY);
                case CONCAT -> arithmetic(out, binary, RuntimeAbi.CONCAT, null);
                case EQ -> comparison(out, binary, BodyWriter.Comparison.EQUAL);
                case NE -> comparison(out, binary, BodyWriter.Comparison.UNEQUAL);
                case LT -> comparison(out, binary, BodyWriter.Comparison.LESS);
                case LE -> comparison(out, binary, BodyWriter.Comparison.AT_MOST);
                case GT -> comparison(out, binary, BodyWriter.Comparison.GREATER);
                case GE -> comparison(out, binary, BodyWriter.Comparison.AT_LEAST);
                case AND, OR -> {
                    int answer = scratch();
                    value(out, binary.left());
                    out.localSet(answer)
                            .localGet(answer)
                            .call(calls.of(RuntimeAbi.BOOL_VALUE));
                    if (binary.op() == BinOp.AND) {
                        out.ifNotZero();
                    } else {
                        out.ifZero();
                    }
                    value(out, binary.right());
                    out.localSet(answer).end().localGet(answer);
                }
            }
        }

        private void comparison(BodyWriter out, Core.Binary binary, BodyWriter.Comparison how) {
            value(out, binary.left());
            value(out, binary.right());
            out.constant(shapes.of(binary.left().type()))
                    .call(calls.of(RuntimeAbi.COMPARE))
                    .constant(0)
                    .compares(how)
                    .call(calls.of(RuntimeAbi.BOOL));
        }

        private void arithmetic(
                BodyWriter out, Core.Binary binary, String whole, String amount) {
            boolean amounts = binary.left().type() == souther.compiler.types.Type.Prim.DECIMAL;
            if (amounts && amount == null) {
                throw new NotLowered(writing + " works out an amount with " + binary.op());
            }
            value(out, binary.left());
            value(out, binary.right());
            out.call(calls.of(amounts ? amount : whole));
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
    }
}