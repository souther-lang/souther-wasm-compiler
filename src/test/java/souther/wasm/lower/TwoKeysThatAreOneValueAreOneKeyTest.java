package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * Two keys are one key where {@code ==} says they are one value, for every kind of value a map can
 * be keyed by — however each was written, and wherever in the value the difference in writing is.
 *
 * <p>A map a walk grows finds a key by its hash first, so two keys that are one value and hash
 * apart would be two entries. Each case hands a walk three keys: two that are one value written two
 * ways, and one that is not. The first two are one entry and the third is another. A kind a value
 * can be is a case here; one added to the runtime without a hash of its own ends the call rather
 * than hashing like every other value of it, which the cases below would then show.
 */
class TwoKeysThatAreOneValueAreOneKeyTest {

    private static final String PROGRAM = """
            module keys

            data Kind = Red | Amber

            data Held = { d: Decimal }

            data Maybe = { d: Decimal? }

            data First = { d: Decimal }

            data Second = { d: Decimal }

            data Either = First | Second

            data Amount = Decimal

            data Nothing

            behavior ints : (xs: List<Int>) -> Int
            let ints (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior bools : (xs: List<Bool>) -> Int
            let bools (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior texts : (xs: List<String>) -> Int
            let texts (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior amounts : (xs: List<Decimal>) -> Int
            let amounts (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior days : (xs: List<Date>) -> Int
            let days (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior times : (xs: List<Time>) -> Int
            let times (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior stamps : (xs: List<DateTime>) -> Int
            let stamps (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior moments : (xs: List<Instant>) -> Int
            let moments (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior kinds : (xs: List<Kind>) -> Int
            let kinds (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior helds : (xs: List<Held>) -> Int
            let helds (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior maybes : (xs: List<Maybe>) -> Int
            let maybes (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior eithers : (xs: List<Either>) -> Int
            let eithers (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior named : (xs: List<Amount>) -> Int
            let named (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior lists : (xs: List<List<Decimal>>) -> Int
            let lists (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior sets : (xs: List<Set<Decimal>>) -> Int
            let sets (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior maps : (xs: List<Map<String, Decimal>>) -> Int
            let maps (xs) = Map.size(List.fold((acc, x) -> Map.insert(x, 1, acc), Map.empty, xs))

            behavior tuples : (xs: List<Decimal>) -> Int
            let tuples (xs) = Map.size(List.fold(
                (acc, x) -> Map.insert((x, x), 1, acc), Map.empty, xs))

            behavior units : (xs: List<Int>) -> Int
            let units (xs) = Map.size(List.fold(
                (acc, x) -> Map.insert(Nothing, x, acc), Map.empty, xs))
            """;

    @Test
    void holdsOneEntryForTwoWritingsOfOneValueOfEveryKind() {
        Running module = compiled();

        assertThat(sizeOf(module, "ints", "[1,1,2]")).isEqualTo(2);
        assertThat(sizeOf(module, "bools", "[true,true,false]")).isEqualTo(2);
        // Composed and decomposed, which are one String once it is let in.
        assertThat(sizeOf(module, "texts", "[\"é\",\"é\",\"e\"]")).isEqualTo(2);
        assertThat(sizeOf(module, "amounts", "[1.0,1.00,2]")).isEqualTo(2);
        assertThat(sizeOf(module, "days", "[\"2026-01-01\",\"2026-01-01\",\"2026-01-02\"]"))
                .isEqualTo(2);
        assertThat(sizeOf(module, "times", "[\"10:00\",\"10:00:00\",\"10:01\"]")).isEqualTo(2);
        assertThat(sizeOf(module, "stamps",
                "[\"2026-01-01T10:00\",\"2026-01-01T10:00:00\",\"2026-01-01T10:01\"]"))
                .isEqualTo(2);
        assertThat(sizeOf(module, "moments",
                "[\"2026-09-04T09:30:15.5Z\",\"2026-09-04T09:30:15.500Z\",\"2026-09-04T09:30:15Z\"]"))
                .isEqualTo(2);
        assertThat(sizeOf(module, "kinds", "[\"Red\",\"Red\",\"Amber\"]")).isEqualTo(2);
        assertThat(sizeOf(module, "helds", "[{\"d\":1.0},{\"d\":1.00},{\"d\":2}]")).isEqualTo(2);
        assertThat(sizeOf(module, "maybes", "[{\"d\":1.0},{\"d\":1.00},{}]")).isEqualTo(2);
        assertThat(sizeOf(module, "eithers", "[{\"type\":\"First\",\"d\":1.0},"
                + "{\"type\":\"First\",\"d\":1.00},{\"type\":\"Second\",\"d\":1.0}]")).isEqualTo(2);
        assertThat(sizeOf(module, "named", "[1.0,1.00,2]")).isEqualTo(2);
        assertThat(sizeOf(module, "lists", "[[1.0,2],[1.00,2.0],[2,1]]")).isEqualTo(2);
        assertThat(sizeOf(module, "sets", "[[1.0,2],[2,1.00],[3]]")).isEqualTo(2);
        assertThat(sizeOf(module, "maps", "[{\"a\":1.0,\"b\":2},{\"b\":2.0,\"a\":1.00},{\"a\":2}]"))
                .isEqualTo(2);
        assertThat(sizeOf(module, "tuples", "[1.0,1.00,2]")).isEqualTo(2);
        assertThat(sizeOf(module, "units", "[1,2,3]")).isEqualTo(1);
    }

    private static int sizeOf(Running module, String behavior, String list) {
        String arguments = "[" + list + "]";
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                "keys." + behavior, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        assertThat(held).describedAs(behavior).startsWith("{\"value\":");
        return Integer.parseInt(held.substring("{\"value\":".length(), held.length() - 1));
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(PROGRAM))));
    }
}
