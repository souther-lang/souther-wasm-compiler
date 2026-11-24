package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
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

    /**
     * A stage that takes half of forty leaves, told whether it takes the one running: by comparing
     * what it was made as with each of twenty, and by a lookup in a table of them. Each way is asked
     * for, since which the rule chooses for twenty leaves is not what this is about.
     */
    @Test
    void offersAStageWhatItTakesAmongManyLeavesWhicheverWayTheyAreTold() {
        int leaves = 40;
        String declared = java.util.stream.IntStream.range(0, leaves).mapToObj(i -> "data A" + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        String all = java.util.stream.IntStream.range(0, leaves).mapToObj(i -> "A" + i)
                .collect(java.util.stream.Collectors.joining(" | "));
        String half = java.util.stream.IntStream.range(0, leaves / 2).mapToObj(i -> "A" + i)
                .collect(java.util.stream.Collectors.joining(" | "));
        String picks = java.util.stream.IntStream.range(0, leaves - 1).mapToObj(i -> "if n == " + i + " then A" + i + " else ")
                .collect(java.util.stream.Collectors.joining()) + "A" + (leaves - 1);
        String source = "module routed\n\n" + declared + "\n\ndata Done\n\ndata All = " + all
                + "\n\ndata Half = " + half + "\n\nbehavior route : (n: Int) -> All\n\nlet route (n) = " + picks
                + "\n\nbehavior take : (h: Half) -> Done\n\nlet take (h) = Done\n\n"
                + "behavior pipeline = route >-> take\n";

        for (LeafTelling telling : LeafTelling.values()) {
            Running module = Running.linked(Compiled.module(Compiled.program(List.of(source)), telling));
            for (int n : new int[] {0, 1, 9, 19}) {
                assertThat(answerOf(module, "routed.pipeline", "[" + n + "]"))
                        .as("%s, taken, %d", telling, n).isEqualTo("{\"value\":\"Done\"}");
            }
            for (int n : new int[] {20, 21, 30, 39}) {
                assertThat(answerOf(module, "routed.pipeline", "[" + n + "]"))
                        .as("%s, left the main line, %d", telling, n).isEqualTo("{\"value\":\"A" + n + "\"}");
            }
        }
    }

    private static Running compiled() {
        return compiled(ROUTED);
    }

    private static Running compiled(String... sources) {
        return Running.linked(Compiled.module(Compiled.program(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
