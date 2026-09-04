package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A behavior written as stages rather than as a body.
 *
 * <p>Each stage is applied to what the one before answered. A stage offered part of what is
 * running takes only the cases it accepts; anything else has left the main line, and the
 * composition answers with it rather than offering it to what follows.
 */
class ACompositionOffersEachStageWhatItTakesTest {

    private static final String ROUTED = """
            module demo

            data Order    = { total: Int }
            data Domestic = { total: Int }
            data Overseas = { total: Int }
            data Priced   = { total: Int }
            data Shipped  = { total: Int }

            behavior classify : (o: Order) -> Domestic | Overseas
                constructs Domestic, Overseas

            let classify (o) = {
                guard o.total <= 100 else Overseas { total = o.total }
                Domestic { total = o.total }
            }

            behavior priceIt : (d: Domestic) -> Priced constructs Priced

            let priceIt (d) = Priced { total = d.total * 2 }

            behavior shipIt : (p: Priced) -> Shipped constructs Shipped

            let shipIt (p) = Shipped { total = p.total + 1 }

            behavior process = classify >-> priceIt >-> shipIt
            """;

    @Test
    void runsEveryStageWhereTheRunningValueKeepsBeingOffered() {
        Running module = compiled();

        assertThat(answerOf(module, "demo.process", "[{\"total\":50}]"))
                .isEqualTo("{\"value\":{\"type\":\"Shipped\",\"total\":101}}");
    }

    @Test
    void answersWithWhatLeftTheMainLineRatherThanOfferingItOnward() {
        Running module = compiled();

        assertThat(answerOf(module, "demo.process", "[{\"total\":500}]"))
                .isEqualTo("{\"value\":{\"type\":\"Overseas\",\"total\":500}}");
    }

    @Test
    void takesTheCompositionsOwnArgumentsAtTheFirstStage() {
        Running module = compiled("""
                module counting

                behavior doubled : (n: Int, by: Int) -> Int

                let doubled (n, by) = n - by

                behavior raised : (n: Int) -> Int

                let raised (n) = n + 1

                behavior worked = doubled >-> raised
                """);

        assertThat(answerOf(module, "counting.worked", "[10,4]")).isEqualTo("{\"value\":7}");
    }

    private static Running compiled() {
        return compiled(ROUTED);
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
