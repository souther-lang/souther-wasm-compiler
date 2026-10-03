package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.lower.WasmCompiler;

/**
 * A linked module carries what its program reaches of the runtime, and shows its host what a host
 * calls.
 *
 * <p>The runtime holds every kernel the library declares, and a program calls a few. What the link
 * keeps is read off the calls, the start and the table, so a body that reaches a function only
 * through a slot keeps it too.
 */
class ALinkedModuleCarriesWhatItsProgramReachesTest {

    private static final String PROGRAM = """
            module small

            behavior sum : (xs: List<Int>) -> Int

            let sum (xs) = List.fold((acc, x) -> acc + x, 0, xs)

            behavior over : (xs: List<Int>, n: Int) -> Int

            let over (xs, n) = match List.find(x -> x > n, xs) with
                | Some found -> found
                | None -> -1
            """;

    @Test
    void showsTheHostWhatAHostCallsAndTheProgramsBehaviors() {
        WasmModule linked = Parser.parse(compiled());

        List<String> shown = new ArrayList<>();
        for (int i = 0; i < linked.exportSection().exportCount(); i++) {
            shown.add(linked.exportSection().getExport(i).name());
        }
        List<String> expected = new ArrayList<>(RuntimeAbi.HOST_EXPORTS);
        expected.add("small.sum");
        expected.add("small.over");
        // Offered whether or not the program publishes a type: a caller holding a module calls the
        // same export of every one, and the surface says what it reads.
        expected.add(WasmCompiler.DECODE);
        assertThat(shown).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void namesOnlyWhatTheRuntimeExports() {
        RuntimeLayout runtime = RuntimeLayout.of(Running.runtimeModule());

        assertThat(runtime.exports()).containsKeys(RuntimeAbi.HOST_EXPORTS.toArray(String[]::new));
    }

    /**
     * What the program does not reach is left out, which is said of what it reaches and not of how
     * big the runtime is: the same program with one body also sorting — the same functions of its
     * own, one kernel more reached — carries more. Held to a share of the runtime instead, this
     * failed whenever the runtime lost a function it no longer needed, with nothing about the link
     * changed.
     */
    @Test
    void leavesOutWhatTheProgramDoesNotReach() {
        WasmModule runtime = Parser.parse(Running.runtimeModule());
        WasmModule linked = Parser.parse(compiled());
        WasmModule sorting = Parser.parse(Compiled.module(Compiled.program(List.of(PROGRAM.replace(
                "List.fold((acc, x) -> acc + x, 0, xs)",
                "List.fold((acc, x) -> acc + x, 0, List.sort(xs))")))));

        assertThat(linked.functionSection().functionCount())
                .isLessThan(sorting.functionSection().functionCount())
                .isLessThan(runtime.functionSection().functionCount());
    }

    @Test
    void runsWhatItReachesThroughTheTable() {
        Running module = Running.linked(compiled());

        // The block is handed to the runtime as a value and called from there through its slot,
        // so nothing calls it by its index.
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        String arguments = "[[1,5,2,7],3]";
        int address = module.staged(arguments);
        long[] answer = module.callWithString("small.over", address, arguments.length());
        assertThat(new String(module.read((int) answer[0], (int) answer[1])))
                .isEqualTo("{\"value\":5}");
        module.call(RuntimeAbi.ALLOC_RESET, mark);
    }

    private static byte[] compiled() {
        return Compiled.module(Compiled.program(List.of(PROGRAM)));
    }
}
