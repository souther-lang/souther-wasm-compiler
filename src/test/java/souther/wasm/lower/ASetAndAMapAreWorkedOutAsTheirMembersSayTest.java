package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * What a set and a map come to through every operation that makes one, over many of them.
 *
 * <p>A set of {@code Int} is written in ascending order and a map keyed by ASCII text in the order
 * its keys sort, so a {@link TreeSet} and a {@link TreeMap} say what each answer is. The runtime
 * finds a member by halving and puts two sets together in one walk down both; these hold that to
 * the same answers putting members in one at a time gave, including where members repeat.
 */
class ASetAndAMapAreWorkedOutAsTheirMembersSayTest {

    private static final String PROGRAM = """
            module working

            behavior together : (a: List<Int>, b: List<Int>) -> Set<Int>

            let together (a, b) = Set.union(Set.fromList(a), Set.fromList(b))

            behavior shared : (a: List<Int>, b: List<Int>) -> Set<Int>

            let shared (a, b) = Set.intersection(Set.fromList(a), Set.fromList(b))

            behavior apart : (a: List<Int>, b: List<Int>) -> Set<Int>

            let apart (a, b) = Set.difference(Set.fromList(a), Set.fromList(b))

            behavior changed : (a: List<Int>, put: Int, gone: Int) -> Set<Int>

            let changed (a, put, gone) = Set.remove(gone, Set.insert(put, Set.fromList(a)))

            behavior holds : (a: List<Int>, x: Int) -> Bool

            let holds (a, x) = Set.contains(x, Set.fromList(a))

            behavior paired : (written: List<String>) -> Map<String, Int>

            let paired (written) =
                Map.fromList(List.map(w -> (String.slice(0, 1, w), String.length(w)), written))
            """;

    @Test
    void answersWhatTheMembersOfTwoSetsSay() {
        Running module = compiled();
        Random random = new Random(20261003);

        for (int round = 0; round < 60; round++) {
            List<Integer> a = members(random);
            List<Integer> b = members(random);
            String arguments = json(a) + "," + json(b);

            TreeSet<Integer> union = new TreeSet<>(a);
            union.addAll(b);
            TreeSet<Integer> shared = new TreeSet<>(a);
            shared.retainAll(b);
            TreeSet<Integer> apart = new TreeSet<>(a);
            apart.removeAll(b);

            assertThat(answerOf(module, "working.together", arguments))
                    .describedAs(arguments).isEqualTo(value(json(union)));
            assertThat(answerOf(module, "working.shared", arguments))
                    .describedAs(arguments).isEqualTo(value(json(shared)));
            assertThat(answerOf(module, "working.apart", arguments))
                    .describedAs(arguments).isEqualTo(value(json(apart)));
        }
    }

    @Test
    void putsInTakesOutAndFindsOneMember() {
        Running module = compiled();
        Random random = new Random(1003);

        for (int round = 0; round < 60; round++) {
            List<Integer> a = members(random);
            int put = random.nextInt(40) - 20;
            int gone = random.nextInt(40) - 20;

            TreeSet<Integer> changed = new TreeSet<>(a);
            changed.add(put);
            changed.remove(gone);

            assertThat(answerOf(module, "working.changed", json(a) + "," + put + "," + gone))
                    .describedAs(a + " +" + put + " -" + gone).isEqualTo(value(json(changed)));
            assertThat(answerOf(module, "working.holds", json(a) + "," + put))
                    .describedAs(a + " ? " + put).isEqualTo(value(Boolean.toString(a.contains(put))));
        }
    }

    @Test
    void keepsTheLastValueOfAKeyWrittenMoreThanOnce() {
        Running module = compiled();
        Random random = new Random(310);

        for (int round = 0; round < 60; round++) {
            // Each entry is its key's letter padded to a length of its own, which is its value, so
            // which of two entries at one key stood is read off the answer.
            int many = random.nextInt(12);
            List<String> keys = new ArrayList<>();
            TreeMap<String, Integer> expected = new TreeMap<>();
            for (int i = 0; i < many; i++) {
                String key = String.valueOf((char) ('a' + random.nextInt(6)));
                keys.add(key + "x".repeat(i));
                expected.put(key, i + 1);
            }
            String written = expected.entrySet().stream()
                    .map(each -> "\"" + each.getKey() + "\":" + each.getValue())
                    .collect(Collectors.joining(",", "{", "}"));

            assertThat(answerOf(module, "working.paired", quotedJson(keys)))
                    .describedAs(keys.toString()).isEqualTo(value(written));
        }
    }

    /** Up to a dozen small numbers, some of them more than once. */
    private static List<Integer> members(Random random) {
        List<Integer> held = new ArrayList<>();
        int many = random.nextInt(12);
        for (int i = 0; i < many; i++) {
            held.add(random.nextInt(40) - 20);
        }
        return held;
    }

    private static String json(Iterable<Integer> numbers) {
        List<String> written = new ArrayList<>();
        numbers.forEach(each -> written.add(Integer.toString(each)));
        return "[" + String.join(",", written) + "]";
    }

    private static String quotedJson(List<String> texts) {
        return texts.stream().map(each -> "\"" + each + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(PROGRAM))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        String written = "[" + arguments + "]";
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(written);
        long[] answer = module.callWithString(
                export, address, written.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }
}
