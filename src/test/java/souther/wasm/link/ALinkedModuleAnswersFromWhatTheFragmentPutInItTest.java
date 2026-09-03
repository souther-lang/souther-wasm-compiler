package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.Type;
import souther.wasm.emit.WasmWriter;

/**
 * A fragment linked onto the runtime, and run.
 *
 * <p>The behavior here answers a constant, which is the smallest thing that needs every part of a
 * link at once: a type, a function, its body, an export to reach it by, a data segment the body
 * points into, a memory large enough to hold that segment, and a start thunk placing the arena
 * above it.
 */
class ALinkedModuleAnswersFromWhatTheFragmentPutInItTest {

    private static final String GREETING = "\"ごきげんよう\"";

    @Test
    void answersTheConstantABehaviorWasLoweredTo() {
        Running module = Running.linked(moduleAnswering(GREETING));

        long[] answer = module.callWithString("greeting", 0, 0);

        assertThat(new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8))
                .isEqualTo(GREETING);
    }

    @Test
    void keepsTheConstantWhereAResetCannotReachIt() {
        Running module = Running.linked(moduleAnswering(GREETING));
        int mark = module.call(RuntimeAbi.ALLOC_MARK);

        long[] answer = module.callWithString("greeting", 0, 0);
        module.call(RuntimeAbi.ALLOC_RESET, mark);

        assertThat(module.read((int) answer[0], (int) answer[1]))
                .containsExactly(GREETING.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void placesTheArenaAboveWhatTheLinkPutInStaticMemory() {
        byte[] linked = moduleAnswering(GREETING);
        Running module = Running.linked(linked);
        int constantEnd = RuntimeLayout.of(linked).activeDataEnd(linked);

        int first = module.call(RuntimeAbi.ALLOC, 8);

        assertThat(constantEnd).isGreaterThan(RuntimeLayout.of(Running.runtimeModule())
                .heapBase(Running.runtimeModule()));
        assertThat(first).isGreaterThanOrEqualTo(constantEnd);
    }

    @Test
    void asksForEnoughMemoryToHoldWhatItPlacedThere() {
        byte[] linked = moduleAnswering("\"" + "x".repeat(200_000) + "\"");

        RuntimeLayout layout = RuntimeLayout.of(linked);

        assertThat(layout.memoryMinimumPages() * 65536)
                .isGreaterThanOrEqualTo(layout.activeDataEnd(linked));
    }

    @Test
    void answersTheRuntimesOwnExportsAsWellAsTheGeneratedOne() {
        Running module = Running.linked(moduleAnswering(GREETING));

        assertThat(module.call(RuntimeAbi.ABI_VERSION)).isEqualTo(RuntimeAbi.VERSION);
    }

    @Test
    void refusesTwoExportsUnderOneName() {
        WasmFragment fragment = new WasmFragment(LinkPlan.reading(Running.runtimeModule()));
        int type = fragment.functionType(List.of(Type.I32, Type.I32), List.of(Type.I32, Type.I32));
        int first = fragment.define(type, constantBody(fragment.place("a".getBytes(StandardCharsets.UTF_8)), 1));
        int second = fragment.define(type, constantBody(fragment.place("b".getBytes(StandardCharsets.UTF_8)), 1));
        fragment.export("greeting", first);

        assertThatThrownBy(() -> fragment.export("greeting", second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exports");
    }

    /** A module whose one behavior answers that text, whatever it is handed. */
    private static byte[] moduleAnswering(String text) {
        WasmFragment fragment = new WasmFragment(LinkPlan.reading(Running.runtimeModule()));
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int address = fragment.place(utf8);
        int type = fragment.functionType(List.of(Type.I32, Type.I32), List.of(Type.I32, Type.I32));
        fragment.export("greeting", fragment.define(type, constantBody(address, utf8.length)));
        return Linker.link(fragment);
    }

    /** A body that answers one pointer and one length, reading neither of its arguments. */
    private static byte[] constantBody(int address, int length) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        new WasmWriter(body)
                .writeUnsignedLeb128(0)
                .write((byte) 0x41).writeSignedLeb128(address)
                .write((byte) 0x41).writeSignedLeb128(length)
                .write((byte) 0x0b);
        return body.toByteArray();
    }
}
