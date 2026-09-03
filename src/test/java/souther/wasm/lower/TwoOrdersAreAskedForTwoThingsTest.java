package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * Where a value stands, and where it is written — which are two questions.
 *
 * <p>A set of alternatives places its own in the order its declaration writes them, and each is
 * written as its name. So a sort puts them one way and a set of them is written another, and a
 * backend answering both from one comparison would be wrong about one of them.
 */
class TwoOrdersAreAskedForTwoThingsTest {

    private static final String SIGNALS = """
            module lighting

            data Red

            data Amber

            data Green

            data Signal = Red | Amber | Green

            data Sequence = { all: List<Signal> }

            data Seen = { all: Set<Signal> }

            behavior ordered : (s: Sequence) -> Sequence

            let ordered (s) = Sequence { all = List.sort(s.all) }

            behavior gathered : (s: Sequence) -> Seen

            let gathered (s) = Seen { all = Set.fromList(s.all) }
            """;

    @Test
    void sortsBySomethingsPlaceInWhatDeclaredIt() {
        Running module = compiled(SIGNALS);

        assertThat(answerOf(module, "lighting.ordered", "[{\"all\":[\"Green\",\"Red\",\"Amber\"]}]"))
                .isEqualTo("{\"value\":{\"all\":[\"Red\",\"Amber\",\"Green\"]}}");
    }

    @Test
    void writesASetInTheOrderItsMembersAreWritten() {
        Running module = compiled(SIGNALS);

        // Not the declaration's order: what a set is written in is what its members are written as.
        assertThat(answerOf(module, "lighting.gathered", "[{\"all\":[\"Green\",\"Red\",\"Amber\"]}]"))
                .isEqualTo("{\"value\":{\"all\":[\"Amber\",\"Green\",\"Red\"]}}");
    }

    @Test
    void sortsNumbersAndTextTheWayBothOrdersAgreeOn() {
        Running module = compiled("""
                module counting

                behavior ordered : (xs: List<Int>) -> List<Int>

                let ordered (xs) = List.sort(xs)

                behavior largest : (xs: List<Int>) -> Int

                let largest (xs) = match List.max(xs) with
                    | Some n -> n
                    | None -> 0

                behavior smallest : (xs: List<Int>) -> Int

                let smallest (xs) = match List.min(xs) with
                    | Some n -> n
                    | None -> 0
                """);

        assertThat(answerOf(module, "counting.ordered", "[[3,-1,2]]"))
                .isEqualTo("{\"value\":[-1,2,3]}");
        assertThat(answerOf(module, "counting.largest", "[[3,-1,2]]")).isEqualTo("{\"value\":3}");
        assertThat(answerOf(module, "counting.smallest", "[[3,-1,2]]")).isEqualTo("{\"value\":-1}");
        assertThat(answerOf(module, "counting.largest", "[[]]")).isEqualTo("{\"value\":0}");
    }

    @Test
    void sortsByWhatABlockAnswersOfEachElement() {
        Running module = compiled("""
                module drawing

                data Point = { x: Int, y: Int }

                behavior byY : (ps: List<Point>) -> List<Point>

                let byY (ps) = List.sortBy(p -> p.y, ps)
                """);

        assertThat(answerOf(module, "drawing.byY",
                "[[{\"x\":1,\"y\":3},{\"x\":2,\"y\":1},{\"x\":3,\"y\":2}]]"))
                .isEqualTo("{\"value\":[{\"x\":2,\"y\":1},{\"x\":3,\"y\":2},{\"x\":1,\"y\":3}]}");
    }

    private static Running compiled(String... sources) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
