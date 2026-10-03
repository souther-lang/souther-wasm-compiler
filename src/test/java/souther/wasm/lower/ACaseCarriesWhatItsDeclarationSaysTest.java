package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * What a case of a sum carries is what its own declaration says: nothing for a unit, fields for a
 * shape, and itself for anything else — a newtype, or a primitive a behavior answers among other
 * cases (spec §sum-discrimination). The last keeps its own form under a key of its own beside the
 * tag, and is compared and hashed as what it is.
 *
 * <p>Each place that reads, writes, compares or hashes a case once told the three apart for itself,
 * and every one of them took anything not laid out as fields for a unit: a newtype case was read
 * as the number it wraps from the whole object, written as its tag alone, and taken to be equal to
 * every other value of its case, so a set of {@code Code(1)} and {@code Code(2)} held one. And a
 * comparison opened a {@code Code} beside a {@code Key} to its number, which the {@code Key} has no
 * case for.
 */
class ACaseCarriesWhatItsDeclarationSaysTest {

    private static final String SOURCE = """
            module carried

            data Code = Int

            data Missing

            data Key = Code | Missing

            behavior echo : (k: Key) -> Key

            let echo (k) = k

            behavior distinct : (xs: List<Key>) -> Int

            let distinct (xs) = Set.size(Set.fromList(xs))

            behavior made : (n: Int) -> List<Int>

            let made (n) = {
                let codes: List<Key> = [Code(1), Code(2), Code(1)]
                let one: Key = Code(1)
                let other: Key = Code(n)
                [Set.size(Set.fromList(codes)),
                 Map.size(List.fold((m, k) -> Map.insert(k, 1, m), Map.empty, codes)),
                 if one == Code(2) then 1 else 0,
                 if one == Code(1) then 1 else 0,
                 if other == one then 1 else 0]
            }

            behavior opened : (k: Key) -> Int

            let opened (k) = match k with
                | Code as c -> c.value
                | Missing -> -1

            behavior answered : (n: Int) -> Int | Missing

            let answered (n) = if n > 0 then n else Missing
            """;

    @Test
    void aNewtypeCaseIsReadAndWrittenWithWhatItWrapsUnderItsOwnKey() {
        Running module = compiled();

        assertThat(answerOf(module, "carried.echo", "[{\"type\":\"Code\",\"value\":2}]"))
                .isEqualTo("{\"value\":{\"type\":\"Code\",\"value\":2}}");
        assertThat(answerOf(module, "carried.echo", "[{\"type\":\"Missing\"}]"))
                .isEqualTo("{\"value\":{\"type\":\"Missing\"}}");
        assertThat(answerOf(module, "carried.opened", "[{\"type\":\"Code\",\"value\":5}]"))
                .isEqualTo("{\"value\":5}");
    }

    @Test
    void twoValuesOfANewtypeCaseAreAsEqualAsWhatTheyWrap() {
        Running module = compiled();

        assertThat(answerOf(module, "carried.distinct",
                "[[{\"type\":\"Code\",\"value\":1},{\"type\":\"Code\",\"value\":2},"
                        + "{\"type\":\"Code\",\"value\":1},{\"type\":\"Missing\"}]]"))
                .isEqualTo("{\"value\":3}");
        // A Code beside a Key is the Key it is, compared as one and not opened to its number.
        assertThat(answerOf(module, "carried.made", "[1]")).isEqualTo("{\"value\":[2,2,0,1,1]}");
    }

    @Test
    void aPrimitiveABehaviorAnswersAmongCasesKeepsItsOwnFormUnderItsOwnKey() {
        Running module = compiled();

        assertThat(answerOf(module, "carried.answered", "[3]"))
                .isEqualTo("{\"value\":{\"type\":\"Int\",\"value\":3}}");
        assertThat(answerOf(module, "carried.answered", "[0]"))
                .isEqualTo("{\"value\":{\"type\":\"Missing\"}}");
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(SOURCE))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
