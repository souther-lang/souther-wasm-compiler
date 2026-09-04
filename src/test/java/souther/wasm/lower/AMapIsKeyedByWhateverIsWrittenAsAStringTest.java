package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A map keyed by something other than text.
 *
 * <p>A map's external form is an object, whose member names are strings, so a type keys a map
 * exactly when it is written as a bare string. Every one that is is written the same way in key
 * position as anywhere else — a day as the day a calendar writes, a moment as the moment a
 * timestamp writes, a case of a set of alternatives as its name — so a key crosses through the
 * same reading and the same writing a value of that type does.
 */
class AMapIsKeyedByWhateverIsWrittenAsAStringTest {

    @Test
    void readsAndWritesADayKeyedMapAsTheDaysItNames() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.days", "{\"2026-09-04\":2,\"1970-01-01\":1}"))
                .isEqualTo(value("{\"1970-01-01\":1,\"2026-09-04\":2}"));
    }

    @Test
    void putsTheEntriesInTheOrderTheirNamesSort() {
        Running module = compiled();

        // The order a document's members stand in, which is the order their names sort — not the
        // order the values stand in, and for a set of alternatives those are two different orders.
        assertThat(answerOf(module, "keyed.kinds", "{\"Red\":1,\"Amber\":2,\"Green\":3}"))
                .isEqualTo(value("{\"Amber\":2,\"Green\":3,\"Red\":1}"));
    }

    @Test
    void namesOneEntryWhereTwoMemberNamesSpellOneKey() {
        Running module = compiled();

        // A moment written to three places and to six is one moment, so an object naming both
        // names one entry — and what stands is what the document said last.
        assertThat(answerOf(module, "keyed.moments",
                        "{\"2026-09-04T09:30:15.500Z\":1,\"2026-09-04T09:30:15.5Z\":2}"))
                .isEqualTo(value("{\"2026-09-04T09:30:15.500Z\":2}"));
    }

    @Test
    void findsAnEntryByWhatItsKeyIsWrittenAsRatherThanHowItWasSpelt() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.at",
                        "{\"2026-09-04T09:30:15.500Z\":7},\"2026-09-04T09:30:15.5Z\""))
                .isEqualTo(value("7"));
        assertThat(answerOf(module, "keyed.at",
                        "{\"2026-09-04T09:30:15.500Z\":7},\"2026-09-04T09:30:16Z\""))
                .isEqualTo(value("-1"));
    }

    @Test
    void keepsAnEntryPutInUnderTheKeyItWasPutInWith() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.plus", "{\"2026-09-04\":1},\"1970-01-01\""))
                .isEqualTo(value("{\"1970-01-01\":99,\"2026-09-04\":1}"));
        assertThat(answerOf(module, "keyed.plus", "{\"2026-09-04\":1},\"2026-09-04\""))
                .isEqualTo(value("{\"2026-09-04\":99}"));
    }

    @Test
    void saysSoForAMemberNameThatIsNoKeyOfThatType() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.days", "{\"2026-02-30\":1}"))
                .contains("\"expected\":\"Date\"");
        // A name that is no case of the set is not a mismatch of kind — it is a string where a
        // string belongs, naming nothing — so it answers with what it is.
        assertThat(answerOf(module, "keyed.kinds", "{\"Purple\":1}"))
                .contains("\"code\":\"not_allowed\"")
                .contains("\"path\":\"/0/Purple\"");
    }

    @Test
    void answersTheKeysAsValuesOfTheirOwnTypeRatherThanAsText() {
        Running module = compiled();

        // Map.keys of a day-keyed map is a list of days, so what comes back is a list of the text
        // a calendar writes and not the member names the document happened to carry.
        assertThat(answerOf(module, "keyed.firstOf", "{\"2026-09-04\":1,\"1970-01-01\":2}"))
                .isEqualTo(value("1970"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module keyed

                data Kind = Red | Amber | Green

                behavior days : (m: Map<Date, Int>) -> Map<Date, Int>

                let days (m) = m

                behavior kinds : (m: Map<Kind, Int>) -> Map<Kind, Int>

                let kinds (m) = m

                behavior moments : (m: Map<Instant, Int>) -> Map<Instant, Int>

                let moments (m) = m

                behavior at : (m: Map<Instant, Int>, k: Instant) -> Int

                let at (m, k) = Option.withDefault(-1, Map.get(k, m))

                behavior plus : (m: Map<Date, Int>, k: Date) -> Map<Date, Int>

                let plus (m, k) = Map.insert(k, 99, m)

                behavior firstOf : (m: Map<Date, Int>) -> Int

                let firstOf (m) = Option.withDefault(0,
                    Option.map(d -> Date.year(d), List.get(0, Map.keys(m))))
                """))));
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
