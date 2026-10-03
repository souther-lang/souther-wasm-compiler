package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A map a fold grows is what putting its entries in one at a time says, whatever its step reads of
 * it on the way.
 *
 * <p>A walk grows its map in the order the entries come and puts them in the map's order once, at
 * the end, so these hold the answer to a {@link TreeMap} filled the same way: keys in any order,
 * keys written more than once, enough of them that the walk has to make room several times, and a
 * step that asks the map what it holds so far.
 */
class AMapAWalkGrowsIsWhatItsStepsSayTest {

    private static final String PROGRAM = """
            module growing

            behavior put : (xs: List<String>) -> Map<String, Int>

            let put (xs) = List.fold(
                (acc, x) -> Map.insert(x, String.length(x), acc), Map.empty, xs)

            behavior counted : (xs: List<String>) -> Map<String, Int>

            let counted (xs) = List.fold(
                (acc, x) -> Map.insert(x, Option.withDefault(0, Map.get(x, acc)) + 1, acc),
                Map.empty, xs)

            behavior sized : (xs: List<String>) -> Map<String, Int>

            let sized (xs) = List.fold(
                (acc, x) -> Map.insert(x, Map.size(acc) * 1000 + List.length(Map.keys(acc)), acc),
                Map.empty, xs)

            behavior seen : (xs: List<String>) -> Map<String, Bool>

            let seen (xs) = List.fold(
                (acc, x) -> Map.insert(x, Map.containsKey(x, acc), acc), Map.empty, xs)

            behavior moments : (xs: List<Instant>) -> Map<Instant, Int>

            let moments (xs) = List.fold(
                (acc, x) -> Map.insert(x, Map.size(acc), acc), Map.empty, xs)
            """;

    @Test
    void holdsWhatTheEntriesPutInOneAtATimeSay() {
        Running module = compiled();
        Random random = new Random(20261003);

        for (int round = 0; round < 24; round++) {
            List<String> keys = keys(random, round < 12 ? 12 : 200);

            Map<String, Integer> put = new TreeMap<>();
            Map<String, Integer> counted = new TreeMap<>();
            Map<String, Integer> sized = new TreeMap<>();
            Map<String, Boolean> seen = new TreeMap<>();
            for (String key : keys) {
                sized.put(key, sized.size() * 1000 + sized.size());
                seen.put(key, seen.containsKey(key));
                put.put(key, key.length());
                counted.merge(key, 1, Integer::sum);
            }
            String arguments = "[" + quoted(keys) + "]";

            assertThat(answerOf(module, "growing.put", arguments)).isEqualTo(value(object(put)));
            assertThat(answerOf(module, "growing.counted", arguments))
                    .isEqualTo(value(object(counted)));
            assertThat(answerOf(module, "growing.sized", arguments)).isEqualTo(value(object(sized)));
            assertThat(answerOf(module, "growing.seen", arguments)).isEqualTo(value(object(seen)));
        }
    }

    @Test
    void holdsOneEntryForTwoSpellingsOfOneKey() {
        Running module = compiled();

        // One moment written to three places and to one: the second put finds the first.
        assertThat(answerOf(module, "growing.moments",
                        "[[\"2026-09-04T09:30:15.500Z\",\"2026-01-01T00:00:00Z\","
                                + "\"2026-09-04T09:30:15.5Z\"]]"))
                .isEqualTo(value("{\"2026-01-01T00:00:00Z\":1,\"2026-09-04T09:30:15.500Z\":2}"));
    }

    @Test
    void growsNothingIntoAnEmptyMap() {
        Running module = compiled();

        assertThat(answerOf(module, "growing.put", "[[]]")).isEqualTo(value("{}"));
    }

    @Test
    void isNotReadAsAMapByWhatAStepMayNotAsk() {
        // Laid out otherwise than a map, so a reader of a map's entries that is handed one ends the
        // call rather than reading its table as entries.
        Running runtime = Running.bareRuntime();
        int builder = runtime.call(RuntimeAbi.MAP_BUILDER, 0);

        assertThatThrownBy(() -> runtime.call("__souther_map_length", builder))
                .isInstanceOf(ChicoryException.class);
    }

    /** Keys from a small alphabet, so some come more than once, in no order. */
    private static List<String> keys(Random random, int many) {
        List<String> keys = new ArrayList<>();
        int alphabet = Math.max(2, many / 2);
        for (int i = 0; i < many; i++) {
            keys.add("k" + random.nextInt(alphabet) + "x".repeat(random.nextInt(3)));
        }
        return keys;
    }

    private static String quoted(List<String> keys) {
        return keys.stream().map(key -> "\"" + key + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private static String object(Map<String, ?> entries) {
        return entries.entrySet().stream()
                .map(each -> "\"" + each.getKey() + "\":" + each.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(PROGRAM))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }
}
