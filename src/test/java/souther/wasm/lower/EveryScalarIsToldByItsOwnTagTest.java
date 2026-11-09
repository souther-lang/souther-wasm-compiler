package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * A scalar holds no descriptor, so a match tells one from another by the tag its cell holds, which
 * this compiler names for each scalar and the runtime gives each (issue #77). All nine in one union,
 * each asked for by its own arm: if two were swapped, a match would take one scalar for another and
 * every program with a single scalar in it would go on working.
 *
 * <p>Asked in an order and in the reverse of it, so that each is an arm that is tested at least once
 * and not only the arm that takes what no other does, and told every way a leaf is told.
 */
class EveryScalarIsToldByItsOwnTagTest {

    private static final List<String> KINDS = List.of(
            "Int", "String", "Bool", "Decimal", "Rational", "Date", "Time", "DateTime", "Instant");

    @Test
    void eachScalarTakesTheArmNamedForIt() {
        List<String> forward = new ArrayList<>(KINDS);
        List<String> reversed = new ArrayList<>(KINDS);
        Collections.reverse(reversed);
        for (List<String> order : List.of(forward, reversed)) {
            String arms = order.stream().map(kind -> "        | " + kind + " -> " + (KINDS.indexOf(kind) + 1) + "\n")
                    .reduce("", String::concat);
            String source = """
                    module scalars

                    behavior kind : (which: Int, d: Decimal, day: Date, t: Time, at: DateTime, moment: Instant) -> Int

                    let kind (which, d, day, t, at, moment) = {
                        let v: Int | String | Bool | Decimal | Rational | Date | Time | DateTime | Instant =
                            if which == 0 then which
                            else if which == 1 then "s"
                            else if which == 2 then true
                            else if which == 3 then d
                            else if which == 4 then which / 3
                            else if which == 5 then day
                            else if which == 6 then t
                            else if which == 7 then at
                            else moment
                        match v with
                    """ + arms + "}\n";
            for (LeafTelling telling : LeafTelling.values()) {
                Running module = Running.linked(Compiled.module(Compiled.program(List.of(source)), telling));
                for (int which = 0; which < KINDS.size(); which++) {
                    String arguments = "[" + which + ",1.5,\"2024-03-05\",\"10:20\",\"2024-03-05T10:20\","
                            + "\"1969-07-20T20:17:40Z\"]";
                    int address = module.staged(arguments);
                    long[] answer = module.callWithString("scalars.kind", address,
                            arguments.getBytes(StandardCharsets.UTF_8).length);
                    assertThat(new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8))
                            .as("%s, arms in the order %s, %s", telling, order.get(0), KINDS.get(which))
                            .isEqualTo("{\"value\":" + (which + 1) + "}");
                }
            }
        }
    }
}
