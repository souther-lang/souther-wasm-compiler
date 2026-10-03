package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
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
        assertThat(shown).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void namesOnlyWhatTheRuntimeExports() {
        RuntimeLayout runtime = RuntimeLayout.of(Running.runtimeModule());

        assertThat(runtime.exports()).containsKeys(RuntimeAbi.HOST_EXPORTS.toArray(String[]::new));
    }

    @Test
    void leavesOutMostOfWhatTheProgramDoesNotReach() {
        WasmModule runtime = Parser.parse(Running.runtimeModule());
        WasmModule linked = Parser.parse(compiled());

        // The program adds a handful of functions of its own, and reaches reading and writing a
        // document, a list and arithmetic: a small part of what the runtime defines.
        assertThat(linked.functionSection().functionCount())
                .isLessThan(runtime.functionSection().functionCount() / 2);
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
        return WasmCompiler.compile(CheckedProgram.of(List.of(PROGRAM)));
    }
}
