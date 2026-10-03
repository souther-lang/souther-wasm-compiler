package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A map a body holds is keyed by any value, and two keys are one where {@code ==} says they are
 * one value.
 *
 * <p>Only a key written as text crosses a boundary, so a map keyed by anything else is made and
 * read inside a body, and its entries are handed back as text here. Its keys stand in the order
 * {@code ==} and {@code <} put them in, which for whole numbers is a {@link TreeMap}'s.
 */
class AMapIsKeyedByAnyValueABodyHoldsTest {

    private static final String PROGRAM = """
            module keyed

            data Point = { x: Int, y: Int }

            behavior grown : (xs: List<Int>) -> List<String>

            let grown (xs) = List.map(pair -> {
                let (k, v) = pair
                String.fromInt(k) ++ ":" ++ String.fromInt(v)
            }, Map.toList(List.fold((acc, x) -> Map.insert(x, x * 10 + Map.size(acc), acc), Map.empty, xs)))

            behavior listed : (xs: List<Int>, gone: Int) -> List<String>

            let listed (xs, gone) = List.map(pair -> {
                let (k, v) = pair
                String.fromInt(k) ++ ":" ++ String.fromInt(v)
            }, Map.toList(Map.remove(gone, Map.fromList(List.map(x -> (x, x * 10), xs)))))

            behavior found : (xs: List<Int>, k: Int) -> Int

            let found (xs, k) = Option.withDefault(-1,
                Map.get(k, Map.insert(1000, 1, Map.fromList(List.map(x -> (x, x * 10), xs)))))

            behavior same : (xs: List<Int>) -> Bool

            let same (xs) = Map.fromList(List.map(x -> (x, 1), xs))
                == Map.fromList(List.map(x -> (x, 1), List.reverse(xs)))

            behavior amounts : (n: Int) -> Int

            let amounts (n) = Map.size(Map.insert(1.00m, n, Map.insert(1.0m, 1, Map.insert(2.5m, 2, Map.empty))))

            behavior truths : (xs: List<Int>) -> Int

            let truths (xs) = Map.size(List.fold((acc, x) -> Map.insert(x > 0, x, acc), Map.empty, xs))

            behavior points : (xs: List<Int>) -> Int

            let points (xs) = Map.size(List.fold(
                (acc, x) -> Map.insert(Point { x = x, y = 0 - x }, x, acc), Map.empty, xs))
            """;

    @Test
    void growsAMapKeyedByWholeNumbersInTheirOrder() {
        Running module = compiled();
        Random random = new Random(1003);

        for (int round = 0; round < 12; round++) {
            List<Long> xs = numbers(random, round < 6 ? 10 : 150);
            Map<Long, Long> grown = new TreeMap<>();
            for (long x : xs) {
                grown.put(x, x * 10 + grown.size());
            }

            assertThat(answerOf(module, "keyed.grown", "[" + json(xs) + "]"))
                    .describedAs(xs.toString()).isEqualTo(value(texts(grown)));
        }
    }

    @Test
    void putsInFindsAndTakesOutAKeyThatIsAWholeNumber() {
        Running module = compiled();
        Random random = new Random(310);

        for (int round = 0; round < 12; round++) {
            List<Long> xs = numbers(random, 40);
            long gone = xs.isEmpty() ? 0 : xs.get(random.nextInt(xs.size()));
            Map<Long, Long> listed = new TreeMap<>();
            xs.forEach(x -> listed.put(x, x * 10));
            listed.remove(gone);

            assertThat(answerOf(module, "keyed.listed", "[" + json(xs) + "," + gone + "]"))
                    .isEqualTo(value(texts(listed)));
            long k = random.nextInt(60) - 30;
            assertThat(answerOf(module, "keyed.found", "[" + json(xs) + "," + k + "]"))
                    .isEqualTo(value(Long.toString(xs.contains(k) ? k * 10 : -1)));
            assertThat(answerOf(module, "keyed.found", "[" + json(xs) + ",1000]"))
                    .isEqualTo(value("1"));
            assertThat(answerOf(module, "keyed.same", "[" + json(xs) + "]")).isEqualTo(value("true"));
        }
    }

    @Test
    void holdsOneEntryForTwoKeysThatAreOneValue() {
        Running module = compiled();

        // 1.0 and 1.00 are one amount, as == says.
        assertThat(answerOf(module, "keyed.amounts", "[7]")).isEqualTo(value("2"));
        assertThat(answerOf(module, "keyed.truths", "[[3,-1,4,-1,5]]")).isEqualTo(value("2"));
        assertThat(answerOf(module, "keyed.points", "[[3,1,4,1,5,9,2,6,5,3]]")).isEqualTo(value("7"));
    }

    private static List<Long> numbers(Random random, int many) {
        List<Long> held = new ArrayList<>();
        for (int i = 0; i < many; i++) {
            held.add((long) random.nextInt(many * 2) - many);
        }
        return held;
    }

    private static String json(List<Long> xs) {
        return xs.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private static String texts(Map<Long, Long> entries) {
        return entries.entrySet().stream()
                .map(each -> "\"" + each.getKey() + ":" + each.getValue() + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(PROGRAM))));
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
