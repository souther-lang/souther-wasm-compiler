package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A field read off a set of alternatives every case of which spreads it.
 *
 * <p>Where a field lies is settled by the type a read is written against, except here: which case a
 * value turned out to be is a thing there has to be a value to know, and two cases need not put the
 * field in the same place. So the read asks the value, which carries the descriptor of what it was
 * made as, and that descriptor names its fields.
 */
class AFieldEveryCaseSpreadsIsReadOffTheValueTest {

    @Test
    void readsTheFieldOffEitherCase() {
        Running module = compiled();

        assertThat(answerOf(module, "staff.hiredOn", "{\"type\":\"Employed\",\"id\":7,"
                + "\"hiredOn\":\"2020-04-01\"}")).isEqualTo("{\"value\":\"2020-04-01\"}");
        assertThat(answerOf(module, "staff.hiredOn", "{\"type\":\"OnLeave\","
                + "\"hiredOn\":\"2019-01-15\",\"leaveFrom\":\"2026-01-01\",\"id\":3}"))
                .isEqualTo("{\"value\":\"2019-01-15\"}");
    }

    @Test
    void findsItWhereTheCaseKeepsItRatherThanWhereTheOtherDoes() {
        Running module = compiled();

        // The two cases spread the same data at different places in their own, so the shared
        // fields do not lie at the same position in both. A read settled at one case's position
        // answers the other case's field, and answers it quietly: both are values.
        assertThat(answerOf(module, "staff.number", "{\"type\":\"Employed\",\"id\":7,"
                + "\"hiredOn\":\"2020-04-01\"}")).isEqualTo("{\"value\":7}");
        assertThat(answerOf(module, "staff.number", "{\"type\":\"OnLeave\","
                + "\"hiredOn\":\"2019-01-15\",\"leaveFrom\":\"2026-01-01\",\"id\":3}"))
                .isEqualTo("{\"value\":3}");
    }

    @Test
    void goesOnReadingAFieldOffAShapeWhereTheTypeSaysWhereItLies() {
        Running module = compiled();

        assertThat(answerOf(module, "staff.onlyId", "{\"id\":11,\"hiredOn\":\"2020-04-01\"}"))
                .isEqualTo("{\"value\":11}");
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module staff

                data Common   = { id: Int, hiredOn: Date }
                data Employed = { ...Common }
                data OnLeave  = { leaveFrom: Date, ...Common }
                data Active   = Employed | OnLeave
                data Plain    = { id: Int, hiredOn: Date }

                behavior hiredOn : (who: Active) -> Date

                let hiredOn (who) = who.hiredOn

                behavior number : (who: Active) -> Int

                let number (who) = who.id

                behavior onlyId : (who: Plain) -> Int

                let onlyId (who) = who.id
                """))));
    }

    private static String answerOf(Running module, String export, String argument) {
        String written = "[" + argument + "]";
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
