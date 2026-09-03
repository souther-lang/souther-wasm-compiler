package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import souther.compiler.core.Core;
import souther.compiler.program.CheckedBehavior;
import souther.compiler.program.CheckedImplementation;
import souther.compiler.program.CheckedModule;
import souther.compiler.program.CheckedProgram;
import souther.compiler.types.ValueName;
import souther.wasm.emit.Type;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.LinkPlan;
import souther.wasm.link.Linker;
import souther.wasm.link.WasmFragment;

/**
 * Writes a checked program as a WebAssembly module.
 *
 * <p>Every behavior a module declares becomes an export named for the module and the behavior. It
 * takes a pointer and a length at which its argument is written as JSON, and answers a pointer and
 * a length at which its answer is. What reclaims those is the caller, on the runtime's contract.
 *
 * <p>What this writes today is a behavior that takes nothing and answers a literal. Everything else
 * a checked program can hold is met by name, in {@link NotLowered}, rather than by emitting
 * something that would run and answer the wrong thing.
 */
public final class WasmCompiler {

    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_END = 0x0b;

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
        WasmFragment fragment = new WasmFragment(LinkPlan.reading(runtime));
        int stringToString = fragment.functionType(
                List.of(Type.I32, Type.I32), List.of(Type.I32, Type.I32));

        for (CheckedModule module : program.modules()) {
            for (CheckedBehavior behavior : module.behaviors()) {
                byte[] answer = answerOf(behavior).getBytes(StandardCharsets.UTF_8);
                int address = fragment.place(answer);
                int function = fragment.define(stringToString, answering(address, answer.length));
                fragment.export(exportName(behavior.name()), function);
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

    /**
     * What a behavior answers, as the text a caller reads.
     *
     * <p>An implementation is met as the kind it is rather than by asking whether it has a body:
     * a behavior implemented by another build has a callee somewhere and no {@code Core} here, and
     * an injected one is a crossing out of the program. Reaching either of those needs something
     * this backend does not write yet, and they are different things, so they are refused apart.
     */
    private static String answerOf(CheckedBehavior behavior) {
        CheckedImplementation implementation = behavior.implementation();
        return switch (implementation) {
            case CheckedImplementation.Body body -> {
                if (!body.parameters().isEmpty()) {
                    throw new NotLowered(behavior.name()
                            + " takes an input, and this backend writes only a behavior that takes none");
                }
                yield literal(behavior, body.body());
            }
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

    private static String literal(CheckedBehavior behavior, Core body) {
        return switch (body) {
            case Core.Str str -> JsonText.of(str.value());
            case Core.Int number -> JsonText.of(number.value());
            case Core.Bool bool -> JsonText.of(bool.value());
            default -> throw new NotLowered(behavior.name()
                    + " answers with " + body.getClass().getSimpleName()
                    + ", and this backend writes only a literal");
        };
    }

    /**
     * A body answering where its constant is and how long it is, reading neither of its arguments.
     *
     * <p>The constant is in static memory rather than the arena, so what a caller reads is still
     * there after the reset that ends the call.
     */
    private static byte[] answering(int address, int length) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        new WasmWriter(body)
                .writeUnsignedLeb128(0)
                .write((byte) OPCODE_I32_CONST).writeSignedLeb128(address)
                .write((byte) OPCODE_I32_CONST).writeSignedLeb128(length)
                .write((byte) OPCODE_END);
        return body.toByteArray();
    }
}
