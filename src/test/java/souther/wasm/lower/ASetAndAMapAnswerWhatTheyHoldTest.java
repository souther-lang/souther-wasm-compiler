package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * The operations a program asks of a set and of a map.
 *
 * <p>Every one of them answers a collection in the order the collection is written in, so what
 * comes back is one document however it was built up.
 */
class ASetAndAMapAnswerWhatTheyHoldTest {

    private static final String SETS = """
            module wording

            data Words = { all: Set<String> }

            behavior including : (w: Words, s: String) -> Words

            let including (w, s) = Words { all = Set.insert(s, w.all) }

            behavior without : (w: Words, s: String) -> Words

            let without (w, s) = Words { all = Set.remove(s, w.all) }

            behavior holds : (w: Words, s: String) -> Bool

            let holds (w, s) = Set.contains(s, w.all)

            behavior counted : (w: Words) -> Int

            let counted (w) = Set.size(w.all)

            behavior listed : (w: Words) -> List<String>

            let listed (w) = Set.toList(w.all)

            behavior bothOf : (a: Words, b: Words) -> Words

            let bothOf (a, b) = Words { all = Set.union(a.all, b.all) }

            behavior sharedBy : (a: Words, b: Words) -> Words

            let sharedBy (a, b) = Words { all = Set.intersection(a.all, b.all) }
            """;

    private static final String MAPS = """
            module counting

            data Tally = { by: Map<String, Int> }

            behavior recorded : (t: Tally, k: String, n: Int) -> Tally

            let recorded (t, k, n) = Tally { by = Map.insert(k, n, t.by) }

            behavior dropped : (t: Tally, k: String) -> Tally

            let dropped (t, k) = Tally { by = Map.remove(k, t.by) }

            behavior holds : (t: Tally, k: String) -> Bool

            let holds (t, k) = Map.containsKey(k, t.by)

            behavior counted : (t: Tally) -> Int

            let counted (t) = Map.size(t.by)

            behavior names : (t: Tally) -> List<String>

            let names (t) = Map.keys(t.by)

            behavior orZero : (t: Tally, k: String) -> Int

            let orZero (t, k) = match Map.get(k, t.by) with
                | Some n -> n
                | None -> 0
            """;

    @Test
    void keepsASetInItsOwnOrderThroughEveryChange() {
        Running module = compiled(SETS);

        assertThat(answerOf(module, "wording.including", "[{\"all\":[\"b\"]},\"a\"]"))
                .isEqualTo("{\"value\":{\"all\":[\"a\",\"b\"]}}");
        assertThat(answerOf(module, "wording.including", "[{\"all\":[\"a\",\"b\"]},\"a\"]"))
                .isEqualTo("{\"value\":{\"all\":[\"a\",\"b\"]}}");
        assertThat(answerOf(module, "wording.without", "[{\"all\":[\"a\",\"b\"]},\"a\"]"))
                .isEqualTo("{\"value\":{\"all\":[\"b\"]}}");
    }

    @Test
    void answersWhatASetHoldsAndHowMany() {
        Running module = compiled(SETS);

        assertThat(answerOf(module, "wording.holds", "[{\"all\":[\"a\"]},\"a\"]"))
                .isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "wording.holds", "[{\"all\":[\"a\"]},\"b\"]"))
                .isEqualTo("{\"value\":false}");
        assertThat(answerOf(module, "wording.counted", "[{\"all\":[\"a\",\"b\"]}]"))
                .isEqualTo("{\"value\":2}");
        assertThat(answerOf(module, "wording.listed", "[{\"all\":[\"b\",\"a\"]}]"))
                .isEqualTo("{\"value\":[\"a\",\"b\"]}");
    }

    @Test
    void putsTwoSetsTogetherAndTakesWhatTheyShare() {
        Running module = compiled(SETS);

        assertThat(answerOf(module, "wording.bothOf", "[{\"all\":[\"a\"]},{\"all\":[\"b\"]}]"))
                .isEqualTo("{\"value\":{\"all\":[\"a\",\"b\"]}}");
        assertThat(answerOf(module, "wording.sharedBy",
                "[{\"all\":[\"a\",\"b\"]},{\"all\":[\"b\",\"c\"]}]"))
                .isEqualTo("{\"value\":{\"all\":[\"b\"]}}");
    }

    @Test
    void keepsAMapInKeyOrderThroughEveryChange() {
        Running module = compiled(MAPS);

        assertThat(answerOf(module, "counting.recorded", "[{\"by\":{\"b\":2}},\"a\",1]"))
                .isEqualTo("{\"value\":{\"by\":{\"a\":1,\"b\":2}}}");
        assertThat(answerOf(module, "counting.recorded", "[{\"by\":{\"a\":1}},\"a\",9]"))
                .isEqualTo("{\"value\":{\"by\":{\"a\":9}}}");
        assertThat(answerOf(module, "counting.dropped", "[{\"by\":{\"a\":1,\"b\":2}},\"a\"]"))
                .isEqualTo("{\"value\":{\"by\":{\"b\":2}}}");
    }

    @Test
    void goesOutToPairsAndComesBackFromThem() {
        Running module = compiled("""
                module counting

                data Tally = { by: Map<String, Int> }

                behavior halved : (t: Tally) -> Tally

                let halved (t) = Tally { by = Map.fromList(
                    List.map(pair -> {
                        let (k, n) = pair
                        (k, n + n)
                    }, Map.toList(t.by))) }

                behavior first : (t: Tally) -> String

                let first (t) = match List.get(0, Map.toList(t.by)) with
                    | Some pair -> {
                        let (k, _) = pair
                        k
                    }
                    | None -> ""
                """);

        assertThat(answerOf(module, "counting.halved", "[{\"by\":{\"b\":2,\"a\":1}}]"))
                .isEqualTo("{\"value\":{\"by\":{\"a\":2,\"b\":4}}}");
        assertThat(answerOf(module, "counting.first", "[{\"by\":{\"b\":2,\"a\":1}}]"))
                .isEqualTo("{\"value\":\"a\"}");
    }

    @Test
    void answersWhatAMapHoldsAtAKey() {
        Running module = compiled(MAPS);

        assertThat(answerOf(module, "counting.orZero", "[{\"by\":{\"a\":7}},\"a\"]"))
                .isEqualTo("{\"value\":7}");
        assertThat(answerOf(module, "counting.orZero", "[{\"by\":{\"a\":7}},\"b\"]"))
                .isEqualTo("{\"value\":0}");
        assertThat(answerOf(module, "counting.holds", "[{\"by\":{\"a\":7}},\"a\"]"))
                .isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "counting.counted", "[{\"by\":{\"a\":1,\"b\":2}}]"))
                .isEqualTo("{\"value\":2}");
        assertThat(answerOf(module, "counting.names", "[{\"by\":{\"b\":2,\"a\":1}}]"))
                .isEqualTo("{\"value\":[\"a\",\"b\"]}");
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
