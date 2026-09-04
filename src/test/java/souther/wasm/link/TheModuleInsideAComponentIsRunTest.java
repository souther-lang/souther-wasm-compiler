package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.lower.WasmCompiler;

/**
 * The core module a component wraps, taken back out and run.
 *
 * <p>Everything a component adds to a program is core wasm — a function per behavior putting the
 * answer where a result is read from, and one giving the arena back — and none of it was ever
 * given to a machine. A component is not something this repository can run, but the module inside
 * one is, and running it is what says those functions are wasm at all: what loads a module checks
 * every body against the types it declares, not only the bodies a call reaches.
 *
 * <p>What is left over is the marshalling: a host lowering a string in and reading one out through
 * the canonical ABI. That is asked of the three runtime functions it goes through, beside the ABI
 * they belong to.
 */
class TheModuleInsideAComponentIsRunTest {

    @Test
    void loadsAndAnswersThroughTheFunctionAComponentLifts() {
        Running module = Running.linked(insideComponentFor("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """));

        // What a lift calls: the arguments in, and an address where the answer's own two words are.
        String arguments = "[21]";
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int area = module.call(Component.Lifted.wrapping("counting.doubled"),
                module.staged(arguments), arguments.getBytes(StandardCharsets.UTF_8).length);

        assertThat(area % 4).describedAs("where a result begins").isZero();
        assertThat(new String(module.read(wordAt(module, area), wordAt(module, area + 4)),
                StandardCharsets.UTF_8)).isEqualTo("{\"value\":42}");

        module.run(Component.Lifted.POST_RETURN);
        assertThat(module.call(RuntimeAbi.ALLOC_MARK))
                .describedAs("what the call made, given back").isLessThanOrEqualTo(mark);
    }

    @Test
    void answersEveryBehaviorThroughItsOwnLiftedFunction() {
        byte[] core = insideComponentFor("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n

                behavior withVat : (n: Int) -> String

                let withVat (n) = String.fromDecimal(Decimal.fromInt(n) * 1.10m)
                """, """
                module greeting

                behavior hello : (name: String) -> String

                let hello (name) = String.append("hello ", name)
                """);
        Running module = Running.linked(core);

        // Every one of them, because a lift names a core function by an index and naming the wrong
        // one answers perfectly well — with another behavior's answer.
        assertThat(answerOf(module, "counting.doubled", "[21]")).isEqualTo("{\"value\":42}");
        assertThat(answerOf(module, "counting.withVat", "[100]"))
                .isEqualTo("{\"value\":\"110.00\"}");
        assertThat(answerOf(module, "greeting.hello", "[\"world\"]"))
                .isEqualTo("{\"value\":\"hello world\"}");
    }

    @Test
    void goesOnAnsweringAfterTheArenaHasBeenGivenBack() {
        Running module = Running.linked(insideComponentFor("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """));

        // A component gives the whole arena back between calls, so the second call and the tenth
        // are the first: nothing a call made outlives the post-return, including what it read in.
        for (int i = 0; i < 10; i++) {
            assertThat(answerOf(module, "counting.doubled", "[" + i + "]"))
                    .describedAs("call " + i).isEqualTo("{\"value\":" + i * 2 + "}");
        }
    }

    private static String answerOf(Running module, String behavior, String arguments) {
        int area = module.call(Component.Lifted.wrapping(behavior), module.staged(arguments),
                arguments.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(module.read(wordAt(module, area), wordAt(module, area + 4)),
                StandardCharsets.UTF_8);
        module.run(Component.Lifted.POST_RETURN);
        return held;
    }

    private static int wordAt(Running module, int address) {
        byte[] held = module.read(address, 4);
        return (held[0] & 0xff) | (held[1] & 0xff) << 8 | (held[2] & 0xff) << 16
                | (held[3] & 0xff) << 24;
    }

    /** The program's own module, taken back out of the component that carries it. */
    private static byte[] insideComponentFor(String... sources) {
        byte[] component = WasmCompiler.compileAsComponent(CheckedProgram.of(List.of(sources)));
        List<byte[]> modules = new ArrayList<>();
        int at = 8;
        while (at < component.length) {
            int id = component[at++] & 0xff;
            int length = 0;
            int shift = 0;
            while (true) {
                int piece = component[at++] & 0xff;
                length |= (piece & 0x7f) << shift;
                if ((piece & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            if (id == SEC_CORE_MODULE) {
                byte[] held = new byte[length];
                System.arraycopy(component, at, held, 0, length);
                modules.add(held);
            }
            at += length;
        }
        // The program's, and after it the one standing in for the crossing out of it.
        assertThat(modules).hasSize(2);
        return modules.get(0);
    }

    private static final int SEC_CORE_MODULE = 1;
}
