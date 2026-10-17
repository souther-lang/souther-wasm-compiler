package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.ValType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.abi.RuntimeAbi;

/**
 * A behavior the model declares and something outside implements.
 *
 * <p>What crosses is what crosses the other way: the arguments as one array, the answer as one
 * value, both written as the documents a caller writes. So a host implements one the way it calls
 * one, and knows nothing of how this module holds a value.
 *
 * <p>The host is handed a number for which behavior it is, runs it once, and puts the answer in this
 * module's arena through the module's own allocator, answering where it is and how long. The
 * length is known before anything is allocated, so no answer is too long for where it goes.
 */
class AnInjectedBehaviorIsTheHostsToImplementTest {

    private static final String SOURCE = """
            module pricing

            data Rate = { per: Int }

            behavior rateOf : (name: String) -> Rate

            behavior twice : (name: String) -> Int depends on rateOf

            let twice (name, rateOf) = rateOf(name).per + rateOf(name).per
            """;

    @Test
    void reachesOutForWhatTheModelDidNotWrite() {
        List<String> asked = new ArrayList<>();
        Host host = Host.answering(SOURCE, arguments -> {
            asked.add(arguments);
            return "{\"per\":7}";
        });

        assertThat(host.answerOf("pricing.twice", "[\"ordinary\"]")).isEqualTo("{\"value\":14}");
        assertThat(asked).containsExactly("[\"ordinary\"]", "[\"ordinary\"]");
    }

    @Test
    void readsWhatCameBackAsTheTypeTheDeclarationAnswers() {
        Host host = Host.answering(SOURCE, arguments -> "{\"per\":3}");

        assertThat(host.answerOf("pricing.rateOf", "[\"any\"]"))
                .isEqualTo("{\"value\":{\"per\":3}}");
    }

    @Test
    void handsOverEveryArgumentAsTheArrayACallIs() {
        List<String> asked = new ArrayList<>();
        Host host = Host.answering("""
                module pricing

                behavior quoted : (name: String, count: Int, urgent: Bool) -> Int

                behavior asked : (name: String, count: Int) -> Int depends on quoted

                let asked (name, count, quoted) = quoted(name, count, true)
                """, arguments -> {
            asked.add(arguments);
            return "5";
        });

        assertThat(host.answerOf("pricing.asked", "[\"a\",2]")).isEqualTo("{\"value\":5}");
        assertThat(asked).containsExactly("[\"a\",2,true]");
    }

    @Test
    void reachesOutOnceWhateverTheLengthOfTheAnswer() {
        Host host = Host.answering(WORDING, arguments -> "\"" + "x".repeat(
                Integer.parseInt(arguments.substring(1, arguments.length() - 1))) + "\"");

        // Past the 256 bytes a buffer offered ahead of the answer once held, and past the 64 KiB
        // the memory starts with, which the answer has to grow it for.
        for (int length : new int[] {200, 255, 1000, 70_000}) {
            int before = host.tries();

            assertThat(host.answerOf("wording.echoed", "[" + length + "]"))
                    .isEqualTo("{\"value\":\"" + "x".repeat(length) + "\"}");
            assertThat(host.tries() - before).describedAs("tries for %d", length).isEqualTo(1);
        }
    }

    @Test
    void takesTheLengthOfAnAnswerInBytesAndNotInCharacters() {
        String wide = "日本語🍣".repeat(100);
        Host host = Host.answering(WORDING, arguments -> "\"" + wide + "\"");

        assertThat(host.answerOf("wording.echoed", "[1]"))
                .isEqualTo("{\"value\":\"" + wide + "\"}");
        assertThat(host.tries()).isEqualTo(1);
    }

    private static final String WORDING = """
            module wording

            behavior nameOf : (n: Int) -> String

            behavior echoed : (n: Int) -> String depends on nameOf

            let echoed (n, nameOf) = nameOf(n)
            """;

    /** A module with something outside standing behind the behaviors the model did not write. */
    private static final class Host {

        private final Instance instance;
        private int tries;

        private Host(Instance instance) {
            this.instance = instance;
        }

        static Host answering(String source, Function<String, String> implementation) {
            byte[] module = Compiled.module(Compiled.program(List.of(source)));
            Host[] holder = new Host[1];
            HostFunction crossing = new HostFunction(
                    RuntimeAbi.IMPORT_MODULE,
                    RuntimeAbi.IMPORT_HOST_CALL,
                    List.of(ValType.I32, ValType.I32, ValType.I32),
                    List.of(ValType.I64),
                    (instance, arguments) -> {
                        holder[0].tries++;
                        String written = new String(
                                instance.memory().readBytes((int) arguments[1], (int) arguments[2]),
                                StandardCharsets.UTF_8);
                        byte[] answer = implementation.apply(written)
                                .getBytes(StandardCharsets.UTF_8);
                        long into = instance.export(RuntimeAbi.ALLOC).apply(answer.length)[0];
                        instance.memory().write((int) into, answer);
                        return new long[] {(into & 0xffff_ffffL) | ((long) answer.length << 32)};
                    });
            holder[0] = new Host(Instance.builder(Parser.parse(module))
                    .withImportValues(ImportValues.builder().addFunction(crossing).build())
                    .build());
            return holder[0];
        }

        int tries() {
            return tries;
        }

        String answerOf(String export, String arguments) {
            byte[] utf8 = arguments.getBytes(StandardCharsets.UTF_8);
            int address = (int) instance.export(RuntimeAbi.ALLOC).apply(utf8.length)[0];
            instance.memory().write(address, utf8);
            long[] answer = instance.export(export).apply(address, utf8.length);
            return new String(
                    instance.memory().readBytes((int) answer[0], (int) answer[1]),
                    StandardCharsets.UTF_8);
        }
    }
}
