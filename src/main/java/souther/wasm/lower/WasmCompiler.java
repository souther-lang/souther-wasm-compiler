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
import souther.compiler.types.ValueName;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.link.LinkPlan;
import souther.wasm.emit.Type;
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
        int stringToString = fragment.functionType(
                List.of(Type.I32, Type.I32), List.of(Type.I32, Type.I32));

        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                byte[] body = new Behavior(fragment, calls, behavior).write();
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
        /** The parsed document. */
        private static final int LOCAL_DOCUMENT = 2;
        /** The first parameter's cell. */
        private static final int LOCAL_FIRST_PARAMETER = 3;

        private final WasmFragment fragment;
        private final Runtime calls;
        private final CheckedBehavior behavior;
        private final Map<BindingId, Integer> locals = new HashMap<>();
        private final List<Core.Binder> parameters;
        private final Core body;

        Behavior(WasmFragment fragment, Runtime calls, CheckedBehavior behavior) {
            this.fragment = fragment;
            this.calls = calls;
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
            // The document, the parameters' cells, and one i64 for the packed answer.
            BodyWriter out = new BodyWriter(1 + arity, 1);
            int packed = LOCAL_FIRST_PARAMETER + arity;

            out.localGet(LOCAL_INPUT_POINTER)
                    .localGet(LOCAL_INPUT_LENGTH)
                    .call(calls.of(RuntimeAbi.JSON_PARSE))
                    .localSet(LOCAL_DOCUMENT);

            var takes = behavior.signature().takes();
            for (int i = 0; i < arity; i++) {
                int local = LOCAL_FIRST_PARAMETER + i;
                locals.put(parameters.get(i).binding(), local);
                out.localGet(LOCAL_DOCUMENT)
                        .constant(i)
                        .constant(arity)
                        .call(calls.of(RuntimeAbi.ARGUMENT))
                        .call(calls.of(readerFor(takes.get(i))))
                        .localSet(local);
            }

            value(out, body);

            out.call(calls.of(RuntimeAbi.WRITE))
                    .localSet(packed)
                    .localGet(packed)
                    .wrap()
                    .localGet(packed)
                    .shiftRight(32)
                    .wrap();
            return out.body();
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
         * The runtime function that reads a declared type out of the neutral source.
         *
         * <p>Asked for by the type that was declared rather than settled by what the document
         * looks like: a place declared {@code Int} holding a string is bad input, and a reader
         * picked from the input would call it a string and be right about nothing.
         */
        private String readerFor(souther.compiler.types.Type declared) {
            return switch (declared) {
                case souther.compiler.types.Type.Prim.INT -> RuntimeAbi.READ_INT;
                case souther.compiler.types.Type.Prim.BOOL -> RuntimeAbi.READ_BOOL;
                case souther.compiler.types.Type.Prim.STRING -> RuntimeAbi.READ_STRING;
                default -> throw new NotLowered(behavior.name() + " takes a " + declared
                        + ", and this backend reads only a scalar");
            };
        }
    }
}
