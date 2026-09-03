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
import souther.compiler.core.Core;
import souther.compiler.core.ValueShape;
import souther.compiler.program.CheckedBehavior;
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
        // Behaviors and nothing else. A helper's body is expanded where it was called, so a
        // checked program carries no helper for a call to reach.
        List<Written> written = new ArrayList<>();
        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                Body body = bodyOf(behavior);
                int index = fragment.declare(overCells(fragment, body.parameters().size()));
                reached.put(behavior.name(), index);
                written.add(new Written(index, body.parameters(), body.body(), behavior.name()));
            }
        }

        Emitter emitter = new Emitter(fragment, calls, shapes, reached);
        for (Written each : written) {
            fragment.write(each.index(), emitter.overValues(each));
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
        return Linker.link(fragment);
    }

    /** The shape of a generated function over values: a cell per parameter, and a cell answered. */
    private static int overCells(WasmFragment fragment, int arity) {
        List<Type> takes = new ArrayList<>();
        for (int i = 0; i < arity; i++) {
            takes.add(Type.I32);
        }
        return fragment.functionType(takes, List.of(Type.I32));
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
            case CheckedImplementation.Composed ignored -> throw new NotLowered(
                    behavior.name() + " is composed, and this backend does not write a composition yet");
            case CheckedImplementation.Injected ignored -> throw new NotLowered(
                    behavior.name() + " is injected, and this backend does not reach out of the module yet");
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
                case Core.Construct made -> {
                    int record = scratch();
                    out.constant(shapes.ofDeclared(made.typeName()))
                            .call(calls.of(RuntimeAbi.RECORD))
                            .localSet(record);
                    // The list is the order the fields are evaluated in. Which slot each goes in
                    // is asked of the shape rather than taken from that order: the two agree in
                    // what this compiler is handed today, and one of them is the answer to the
                    // question being asked.
                    for (Core.FieldValue field : made.values()) {
                        out.localGet(record)
                                .constant(shapes.positionOf(made.typeName(), field.field()));
                        value(out, field.value());
                        out.call(calls.of(RuntimeAbi.RECORD_SET));
                    }
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
                case Core.FieldAccess read -> {
                    value(out, read.target());
                    out.constant(shapes.positionOf(shapeOf(read.target()), read.field()))
                            .call(calls.of(RuntimeAbi.RECORD_GET));
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
                case Core.Match chosen -> match(out, chosen);
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
         * A call, which is the declaration it reaches with its arguments before it.
         *
         * <p>What it reaches is read off the call rather than worked out from a name: the checker
         * typed it against a declaration and says which, and a spelling this resolved again would
         * be resolving what was resolved already.
         */
        private void call(BodyWriter out, Core.Call call) {
            if (!(call.fn() instanceof Core.Reached.OfDeclaration declaration)) {
                throw new NotLowered(writing + " reaches " + call.fn()
                        + ", and this backend reaches a helper and a behavior");
            }
            if (!(declaration.reaches() instanceof Core.Reaches.ABehavior reaches)) {
                // A helper: expanded where it was written, so nothing reaches one here. Said
                // rather than assumed, because a checker that stopped expanding them would
                // otherwise reach whatever this happened to do next.
                throw new NotLowered(writing + " reaches " + declaration.name()
                        + ", and this backend reaches a behavior");
            }
            Integer index = reached.get(reaches.behavior());
            if (index == null) {
                throw new NotLowered(writing + " reaches " + reaches.behavior()
                        + ", which this program declares no body for here");
            }
            for (Core argument : call.args()) {
                value(out, argument);
            }
            out.call(index);
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
                        .constant(shapes.ofDeclared(asDeclared(atoms.get(i))))
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

        private static TypeSymbol.AtModule asDeclared(TypeSymbol name) {
            if (name instanceof TypeSymbol.AtModule named) {
                return named;
            }
            throw new NotLowered(name + " selects an arm and is not declared by a module");
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
                case ADD -> arithmetic(out, binary, RuntimeAbi.ADD);
                case SUB -> arithmetic(out, binary, RuntimeAbi.SUBTRACT);
                case MUL -> arithmetic(out, binary, RuntimeAbi.MULTIPLY);
                case DIV -> arithmetic(out, binary, RuntimeAbi.DIVIDE);
                case CONCAT -> arithmetic(out, binary, RuntimeAbi.CONCAT);
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

        private void arithmetic(BodyWriter out, Core.Binary binary, String operation) {
            value(out, binary.left());
            value(out, binary.right());
            out.call(calls.of(operation));
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