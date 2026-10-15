package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * A list, a set or a map changed is a new one, and the one it was made from is still what it was.
 *
 * <p>A list joined on writes into its own array where nothing was made from it yet, and a set or a
 * map changed one member at a time shares all but a path of a tree with the one it was made from.
 * Both are only right while the one made from is never seen to change: these make two from one and
 * read all three. And a collection held as a tree is the same value, written the same way, as one
 * held as an array, which is what `==` and a document ask.
 */
class ACollectionChangedIsANewOneTest {

    private static final String SOURCE = """
            module changing

            data Joined = { base: List<Int>, one: List<Int>, other: List<Int> }

            data Grown = { base: List<Int>, one: List<Int>, other: List<Int>, gone: List<Int>,
                          holds: Bool, held: Bool }

            data Keyed = { base: Map<String, Int>, one: Map<String, Int>, other: Map<String, Int>,
                          gone: Map<String, Int>, found: Int, missing: Bool }

            data Alike = { sets: Bool, maps: Bool, set: Set<Int>, map: Map<String, Int> }

            behavior lists : (xs: List<Int>) -> Joined

            let lists (xs) = {
                let base = xs ++ [4]
                let one = base ++ [5]
                let other = base ++ [6, 7]
                Joined { base = base, one = one, other = other }
            }

            behavior sets : (xs: List<Int>) -> Grown

            let sets (xs) = {
                let base = Set.insert(4, Set.fromList(xs))
                let one = Set.insert(0, base)
                let other = Set.insert(9, base)
                let gone = Set.remove(2, one)
                Grown { base = Set.toList(base), one = Set.toList(one), other = Set.toList(other),
                       gone = Set.toList(gone), holds = Set.contains(0, one),
                       held = Set.contains(0, base) }
            }

            behavior maps : (xs: List<Int>) -> Keyed

            let maps (xs) = {
                let base = Map.insert("d", 4, Map.fromList(List.map(x -> (String.fromInt(x), x), xs)))
                let one = Map.insert("a", 0, base)
                let other = Map.insert("d", 40, base)
                let gone = Map.remove("1", one)
                Keyed { base = base, one = one, other = other, gone = gone,
                       found = Option.withDefault(-1, Map.get("d", other)),
                       missing = Map.containsKey("a", base) }
            }

            behavior same : (xs: List<Int>) -> Alike

            let same (xs) = {
                let grown = List.fold((s, x) -> Set.insert(x, s), Set.empty, xs)
                let keyed = List.fold((m, x) -> Map.insert(String.fromInt(x), x, m), Map.empty, xs)
                Alike { sets = grown == Set.fromList(xs),
                       maps = keyed == Map.fromList(List.map(x -> (String.fromInt(x), x), xs)),
                       set = grown, map = keyed }
            }

            behavior churned : (xs: List<Int>) -> List<Int>

            let churned (xs) = {
                let all = List.fold((s, x) -> Set.insert(x, s), Set.empty, xs)
                let odd = List.fold((s, x) -> if Int.floorMod(x, 2) == 0 then Set.remove(x, s) else s, all, xs)
                Set.toList(odd)
            }

            behavior walked : (xs: List<Int>) -> Int

            let walked (xs) = List.fold((acc, x) -> acc + x, 0,
                Set.toList(List.fold((s, x) -> Set.insert(x, s), Set.empty, xs)))
            """;

    @Test
    void joinsTwoListsOnOneWithoutTheOneChanging() {
        assertThat(answerOf("changing.lists", "[[1,2,3]]")).isEqualTo(
                "{\"value\":{\"base\":[1,2,3,4],\"one\":[1,2,3,4,5],\"other\":[1,2,3,4,6,7]}}");
    }

    @Test
    void putsMembersIntoASetWithoutTheSetChanging() {
        assertThat(answerOf("changing.sets", "[[3,1,2]]")).isEqualTo(
                "{\"value\":{\"base\":[1,2,3,4],\"one\":[0,1,2,3,4],\"other\":[1,2,3,4,9],"
                        + "\"gone\":[0,1,3,4],\"holds\":true,\"held\":false}}");
    }

    @Test
    void putsEntriesIntoAMapWithoutTheMapChanging() {
        assertThat(answerOf("changing.maps", "[[3,1,2]]")).isEqualTo(
                "{\"value\":{\"base\":{\"1\":1,\"2\":2,\"3\":3,\"d\":4},"
                        + "\"one\":{\"1\":1,\"2\":2,\"3\":3,\"a\":0,\"d\":4},"
                        + "\"other\":{\"1\":1,\"2\":2,\"3\":3,\"d\":40},"
                        + "\"gone\":{\"2\":2,\"3\":3,\"a\":0,\"d\":4},"
                        + "\"found\":40,\"missing\":false}}");
    }

    @Test
    void holdsACollectionGrownOneAtATimeAsTheOneMadeAtOnce() {
        // Out of order, so that the tree a set grows is balanced again on the way.
        String xs = scrambled(200);
        String sorted = IntStream.range(0, 200).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        String keyed = IntStream.range(0, 200).mapToObj(String::valueOf).sorted()
                .map(each -> "\"" + each + "\":" + each).collect(Collectors.joining(","));

        assertThat(answerOf("changing.same", "[" + xs + "]")).isEqualTo(
                "{\"value\":{\"sets\":true,\"maps\":true,\"set\":[" + sorted + "],\"map\":{"
                        + keyed + "}}}");
    }

    @Test
    void takesOutWhatItPutInWhateverOrderEitherCameIn() {
        String odd = IntStream.range(0, 500).filter(i -> i % 2 == 1).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        assertThat(answerOf("changing.churned", "[" + scrambled(500) + "]"))
                .isEqualTo("{\"value\":[" + odd + "]}");
    }

    @Test
    void walksASetHeldAsATree() {
        assertThat(answerOf("changing.walked", "[" + scrambled(100) + "]"))
                .isEqualTo("{\"value\":" + (99 * 100 / 2) + "}");
    }

    /** The numbers up to {@code n}, in an order no tree grows balanced from. */
    private static String scrambled(int n) {
        return "[" + IntStream.range(0, n).map(i -> (int) ((i * 7919L) % n))
                .mapToObj(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    private static String answerOf(String export, String arguments) {
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(SOURCE))));
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
