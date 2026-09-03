package souther.wasm.lower;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import souther.compiler.core.Core;
import souther.compiler.program.CheckedBehavior;
import souther.compiler.program.CheckedImplementation;
import souther.compiler.program.CheckedModule;
import souther.compiler.program.CheckedProgram;
import souther.compiler.types.BindingId;
import souther.compiler.types.TypeSymbol;
import souther.compiler.types.ValueName;
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
        Descriptors shapes = new Descriptors(program, fragment);
        int stringToString = fragment.functionType(
                List.of(Type.I32, Type.I32), List.of(Type.I32, Type.I32));

        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                byte[] body = new Behavior(fragment, calls, shapes, behavior).write();
                fragment.export(exportName(behavior.name()), fragment.define(stringToString, body));
            }
        }
        return Linker.link(fragment);
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

    /** One behavior's export, written. */
    private static final class Behavior {

        /** The pointer the caller's JSON is at. */
        private static final int LOCAL_INPUT_POINTER = 0;
        /** How long the caller's JSON is. */
        private static final int LOCAL_INPUT_LENGTH = 1;

        private final WasmFragment fragment;
        private final Runtime calls;
        private final Descriptors shapes;
        private final CheckedBehavior behavior;
        private final Map<BindingId, Integer> locals = new HashMap<>();
        private BodyWriter out;
        private final List<Core.Binder> parameters;
        private final Core body;

        Behavior(WasmFragment fragment, Runtime calls, Descriptors shapes, CheckedBehavior behavior) {
            this.fragment = fragment;
            this.calls = calls;
            this.shapes = shapes;
            this.behavior = behavior;
            CheckedImplementation implementation = behavior.implementation();
            CheckedImplementation.Body written = switch (implementation) {
                case CheckedImplementation.Body it -> it;
                case CheckedImplementation.Composed ignored -> throw new NotLowered(
                        behavior.name() + " is composed, and this backend does not write a composition yet");
                case CheckedImplementation.Injected ignored -> throw new NotLowered(
                        behavior.name() + " is injected, and this backend does not reach out of the module yet");
                case CheckedImplementation.Unwritten ignored -> throw new NotLowered(
                        behavior.name() + " is not written, so there is nothing to emit for it");
                case CheckedImplementation.ImplementedElsewhere ignored -> throw new NotLowered(
                        behavior.name() + " is implemented by another build, and this backend links one program");
            };
            this.parameters = written.parameters();
            this.body = written.body();
        }

        byte[] write() {
            int arity = parameters.size();
            BodyWriter out = new BodyWriter(2, 1);
            this.out = out;
            int packed = out.wide(0);
            int document = out.narrow();

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
            out.call(calls.of(RuntimeAbi.ISSUES_COUNT)).ifZero();
            var takes = behavior.signature().takes();
            for (int i = 0; i < arity; i++) {
                int local = out.narrow();
                locals.put(parameters.get(i).binding(), local);
                byte[] path = ("/" + i).getBytes(StandardCharsets.UTF_8);
                out.localGet(document)
                        .constant(i)
                        .call(calls.of(RuntimeAbi.ARGUMENT))
                        .constant(shapes.of(takes.get(i)))
                        .constant(fragment.place(path))
                        .constant(path.length)
                        .call(calls.of(RuntimeAbi.READ))
                        .localSet(local);
            }
            out.end();

            // A body runs on what was read, and only where everything was. A refused place leaves
            // nothing behind, and nothing is not a value to run a behavior on.
            out.call(calls.of(RuntimeAbi.ISSUES_COUNT)).ifNotZero()
                    .call(calls.of(RuntimeAbi.ISSUES_WRITTEN))
                    .localSet(packed)
                    .otherwise();
            value(out, body);
            out.constant(shapes.of(behavior.signature().answers()))
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
                    out.localGet(record);
                }
                case Core.FieldAccess read -> {
                    value(out, read.target());
                    out.constant(shapes.positionOf(shapeOf(read.target()), read.field()))
                            .call(calls.of(RuntimeAbi.RECORD_GET));
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
                case Core.Read read -> {
                    Integer local = locals.get(read.binding());
                    if (local == null) {
                        throw new NotLowered(behavior.name() + " reads " + read.name()
                                + ", which is bound by something this backend does not write yet");
                    }
                    out.localGet(local);
                }
                default -> throw new NotLowered(behavior.name() + " answers with "
                        + expression.getClass().getSimpleName()
                        + ", and this backend writes a literal or a read of a parameter");
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
            throw new NotLowered(behavior.name() + " reads a field off a "
                    + expression.type() + ", and this backend reads one off a shape");
        }

        /** A local nothing else is using, for a value that outlives one instruction. */
        private int scratch() {
            return out.narrow();
        }
    }
}