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
import souther.compiler.program.CheckedProgram;
import souther.wasm.abi.RuntimeAbi;

/**
 * A behavior the model declares and something outside implements.
 *
 * <p>What crosses is what crosses the other way: the arguments as one array, the answer as one
 * value, both written as the documents a caller writes. So a host implements one the way it calls
 * one, and knows nothing of how this module holds a value.
 *
 * <p>The host is handed a number for which behavior it is and a buffer to write the answer into.
 * The buffer is this module's, because the bump pointer is this module's — a host that allocated
 * would be moving it.
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
    void asksAgainWithMoreRoomWhereTheAnswerDidNotFit() {
        String wide = "x".repeat(4000);
        Host host = Host.answering("""
                module wording

                behavior nameOf : (n: Int) -> String

                behavior echoed : (n: Int) -> String depends on nameOf

                let echoed (n, nameOf) = nameOf(n)
                """, arguments -> "\"" + wide + "\"");

        assertThat(host.answerOf("wording.echoed", "[1]"))
                .isEqualTo("{\"value\":\"" + wide + "\"}");
        assertThat(host.tries()).isGreaterThan(1);
    }

    /** A module with something outside standing behind the behaviors the model did not write. */
    private static final class Host {

        private final Instance instance;
        private int tries;

        private Host(Instance instance) {
            this.instance = instance;
        }

        static Host answering(String source, Function<String, String> implementation) {
            byte[] module = WasmCompiler.compile(CheckedProgram.of(List.of(source)));
            Host[] holder = new Host[1];
            HostFunction crossing = new HostFunction(
                    RuntimeAbi.IMPORT_MODULE,
                    RuntimeAbi.IMPORT_HOST_CALL,
                    List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                    List.of(ValType.I32),
                    (instance, arguments) -> {
                        holder[0].tries++;
                        String written = new String(
                                instance.memory().readBytes((int) arguments[1], (int) arguments[2]),
                                StandardCharsets.UTF_8);
                        byte[] answer = implementation.apply(written)
                                .getBytes(StandardCharsets.UTF_8);
                        if (answer.length <= arguments[4]) {
                            instance.memory().write((int) arguments[3], answer);
                        }
                        return new long[] {answer.length};
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
